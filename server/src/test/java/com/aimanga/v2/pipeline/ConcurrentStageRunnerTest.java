package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.PipelineStageItem;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.task.TaskRuntime;
import com.aimanga.v2.task.TaskExecutionOwner;
import com.aimanga.v2.task.TaskPauseSignal;
import com.aimanga.v2.task.TaskStopSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5.9 并发阶段引擎(Phase 5.11 更名 ConcurrentStageRunner)单元测试:
 * 并发上限 / 单元失败重试 / 重试耗尽终态失败 / 暂停不领取 / 停止传播 / 执行互斥锁。
 */
class ConcurrentStageRunnerTest {

    private static final Long PROJECT_ID = 100L;
    private static final String STAGE = "SHEET";
    private static final TaskExecutionOwner OWNER = new TaskExecutionOwner(91L, "TASK_CLAIM");

    private PipelineStageService stageService;
    private ConfigService configService;
    private TaskRuntime runtime;
    private ConcurrentStageRunner runner;
    private ImageWorkerPool workerPool;

    @BeforeEach
    void setUp() throws Exception {
        stageService = mock(PipelineStageService.class);
        configService = mock(ConfigService.class);
        runtime = mock(TaskRuntime.class);
        when(runtime.owner()).thenReturn(OWNER);
        // 真实 Worker 池(用 mock 配置驱动并发数);Runner 内部按余量领取,池线程数即并发上限
        when(configService.getInt(eq("image_generation_concurrency"), anyInt())).thenReturn(2);
        when(configService.getInt(eq("image_gen_max_retry"), anyInt())).thenReturn(1);
        when(configService.getInt(eq("image_queue_size"), anyInt())).thenReturn(50);
        when(configService.getInt(eq("script_item_concurrency"), anyInt())).thenReturn(2);
        when(configService.getInt(eq("script_gen_max_retry"), anyInt())).thenReturn(1);
        when(configService.getInt(eq("script_queue_size"), anyInt())).thenReturn(50);
        workerPool = new ImageWorkerPool(configService);
        workerPool.init();
        ScriptWorkerPool scriptPool = new ScriptWorkerPool(configService);
        scriptPool.init();
        // 执行互斥锁 mock:永远可获取(T5.11.3 专测单独覆盖)
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(lock.tryLock(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(true);
        when(redisson.getLock(anyString())).thenReturn(lock);
        runner = new ConcurrentStageRunner(stageService, workerPool, scriptPool, configService, redisson);

        when(stageService.isStagePaused(eq(PROJECT_ID), eq(STAGE))).thenReturn(false);
        when(stageService.resetOrphanedRunningItems(PROJECT_ID, STAGE)).thenReturn(0);
        when(stageService.claimItem(anyLong(), anyString(), any(TaskExecutionOwner.class))).thenReturn(true);
        // fenced 状态写默认成功(否则重试语义会变成 stale→重领 的无限循环)
        when(stageService.markItemRetry(anyLong(), anyString(), any(TaskExecutionOwner.class), anyString())).thenReturn(true);
        when(stageService.markItemFailed(anyLong(), anyString(), any(TaskExecutionOwner.class), anyString())).thenReturn(true);
        when(stageService.markItemSuccess(anyLong(), anyString(), any(TaskExecutionOwner.class), anyString())).thenReturn(true);
    }

    private PipelineStageItem item(long id, long businessId, int retryCount) {
        PipelineStageItem item = new PipelineStageItem();
        item.setId(id);
        item.setProjectId(PROJECT_ID);
        item.setStageType(STAGE);
        item.setBusinessType("ASSET");
        item.setBusinessId(businessId);
        item.setStatus(PipelineStageItem.STATUS_PENDING);
        item.setRetryCount(retryCount);
        return item;
    }

    @Test
    void concurrentGeneration_respectsConcurrencyLimit() {
        // 5 个 Item,并发=2:任意时刻同时执行的处理器不超过 2 个
        // (候选列表恒定返回全部 PENDING,已被领取的由 Runner 的 claimed 集合跳过,与真实 SQL 行为一致)
        when(stageService.getPendingItemIds(PROJECT_ID, STAGE, 64))
                .thenAnswer(inv -> List.of(1L, 2L, 3L, 4L, 5L));
        when(stageService.getItem(anyLong())).thenAnswer(inv -> item(inv.getArgument(0), inv.getArgument(0), 0));

        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        ConcurrentStageRunner.StageRunResult result = runner.run(PROJECT_ID, STAGE, StageRunScope.all(), runtime, it -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            Thread.sleep(200);
            active.decrementAndGet();
            return "{}";
        }, runner.imageEngine());

        assertThat(maxActive.get()).isLessThanOrEqualTo(2);
        assertThat(result.success()).isEqualTo(5);
        assertThat(result.failed()).isZero();
        verify(stageService, atLeastOnce()).updateStageProgress(PROJECT_ID, STAGE);
    }

    @Test
    void itemFailure_requeuesAndRetries_thenSucceeds() {
        // 第 1 次失败 → 回 PENDING 重试 → 第 2 次成功
        when(stageService.getPendingItemIds(PROJECT_ID, STAGE, 64))
                .thenAnswer(inv -> List.of(1L));
        when(stageService.getItem(1L)).thenReturn(item(1L, 11L, 0), item(1L, 11L, 1));

        AtomicInteger attempts = new AtomicInteger();
        ConcurrentStageRunner.StageRunResult result = runner.run(PROJECT_ID, STAGE, StageRunScope.all(), runtime, it -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException("模型未返回图片");
            }
            return "{\"assetId\":11}";
        }, runner.imageEngine());

        assertThat(attempts.get()).isEqualTo(2);
        verify(stageService).markItemRetry(eq(1L), anyString(), eq(OWNER), contains("模型未返回图片"));
        verify(stageService).markItemSuccess(eq(1L), anyString(), eq(OWNER), contains("assetId"));
        verify(stageService, never()).markItemFailed(anyLong(), anyString(), any(TaskExecutionOwner.class), anyString());
        assertThat(result.success()).isEqualTo(1);
        assertThat(result.retried()).isEqualTo(1);
    }

    @Test
    void itemFailure_exhaustsRetry_marksTerminalFailed() {
        // max_retry=1:失败两次后终态失败,不再重试
        when(stageService.getPendingItemIds(PROJECT_ID, STAGE, 64))
                .thenAnswer(inv -> List.of(1L));
        when(stageService.getItem(1L)).thenReturn(item(1L, 11L, 0), item(1L, 11L, 1));

        AtomicInteger attempts = new AtomicInteger();
        ConcurrentStageRunner.StageRunResult result = runner.run(PROJECT_ID, STAGE, StageRunScope.all(), runtime, it -> {
            attempts.incrementAndGet();
            throw new RuntimeException("通道超时");
        }, runner.imageEngine());

        assertThat(attempts.get()).isEqualTo(2);
        verify(stageService).markItemRetry(eq(1L), anyString(), eq(OWNER), anyString());
        verify(stageService).markItemFailed(eq(1L), anyString(), eq(OWNER), contains("通道超时"));
        verify(runtime).stepFail(anyString());
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.success()).isZero();
    }

    @Test
    void stagePaused_throwsPauseSignal_claimsNothing() {
        // Phase 8.3:阶段暂停 → Runner 抛 TaskPauseSignal,Task 置 PAUSED(同一 Task 继续复用)
        when(stageService.isStagePaused(PROJECT_ID, STAGE)).thenReturn(true);

        assertThatThrownBy(() -> runner.run(PROJECT_ID, STAGE, StageRunScope.all(), runtime, it -> "{}", runner.imageEngine()))
                .isInstanceOf(TaskPauseSignal.class);
        verify(stageService, never()).getPendingItemIds(anyLong(), anyString(), anyInt());
        verify(stageService, never()).claimItem(anyLong(), anyString(), any(TaskExecutionOwner.class));
    }

    @Test
    void userStop_propagatesStopSignal() {
        org.mockito.Mockito.doThrow(new TaskStopSignal()).when(runtime).checkStop();

        assertThatThrownBy(() -> runner.run(PROJECT_ID, STAGE, StageRunScope.all(), runtime, it -> "{}", runner.imageEngine()))
                .isInstanceOf(TaskStopSignal.class);
        verify(stageService, never()).claimItem(anyLong(), anyString(), any(TaskExecutionOwner.class));
    }

    @Test
    void orphanRecovery_resetsRunningItemsBeforeRun() {
        when(stageService.resetOrphanedRunningItems(PROJECT_ID, STAGE)).thenReturn(3);
        when(stageService.getPendingItemIds(PROJECT_ID, STAGE, 64)).thenReturn(List.of());

        runner.run(PROJECT_ID, STAGE, StageRunScope.all(), runtime, it -> "{}", runner.imageEngine());

        verify(stageService).resetOrphanedRunningItems(PROJECT_ID, STAGE);
    }

    @Test
    void inFlightFailureDuringStop_releasesItemBackToPending() {
        // Worker 执行中任务被停止 → checkStop 在 Worker 内抛出 → Item 归还 PENDING(下次继续),不丢失
        when(stageService.getPendingItemIds(PROJECT_ID, STAGE, 64))
                .thenAnswer(inv -> List.of(1L));
        when(stageService.getItem(1L)).thenReturn(item(1L, 11L, 0));
        // 第 1 次(主循环喂 Item 前)放行,第 2 次起(Worker 内)抛停止信号
        org.mockito.Mockito.doNothing().doThrow(new TaskStopSignal()).when(runtime).checkStop();

        assertThatThrownBy(() -> runner.run(PROJECT_ID, STAGE, StageRunScope.all(), runtime, it -> "{}", runner.imageEngine()))
                .isInstanceOf(TaskStopSignal.class);

        verify(stageService, atLeastOnce()).releaseItem(org.mockito.ArgumentMatchers.eq(1L), anyString(), eq(OWNER));
        verify(stageService, never()).markItemSuccess(anyLong(), anyString(), any(TaskExecutionOwner.class), anyString());
    }

    @Test
    void concurrentRunnerOnSameStage_rejectedByMutex() throws Exception {
        // T5.11.3:同一 project+stage 第二个 Runner 抢不到执行锁 → 409,不回收任何 Item
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(lock.tryLock(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(false);
        when(redisson.getLock(anyString())).thenReturn(lock);
        ScriptWorkerPool scriptPool = new ScriptWorkerPool(configService);
        scriptPool.init();
        ConcurrentStageRunner second = new ConcurrentStageRunner(stageService, workerPool, scriptPool, configService, redisson);

        assertThatThrownBy(() -> second.run(PROJECT_ID, STAGE, StageRunScope.all(), runtime, it -> "{}", second.imageEngine()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有生成任务在执行中");
        verify(stageService, never()).resetOrphanedRunningItems(anyLong(), anyString());
        verify(stageService, never()).claimItem(anyLong(), anyString(), any(TaskExecutionOwner.class));
    }
}
