package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.PipelineStageItem;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.task.TaskRuntime;
import com.aimanga.v2.task.TaskStopSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * Phase 5.9 并发生图引擎单元测试:
 * 并发上限 / 单元失败重试 / 重试耗尽终态失败 / 暂停不领取 / 停止传播。
 */
class ImageStageRunnerTest {

    private static final Long PROJECT_ID = 100L;
    private static final String STAGE = "SHEET";

    private PipelineStageService stageService;
    private ConfigService configService;
    private TaskRuntime runtime;
    private ImageStageRunner runner;
    private ImageWorkerPool workerPool;

    @BeforeEach
    void setUp() {
        stageService = mock(PipelineStageService.class);
        configService = mock(ConfigService.class);
        runtime = mock(TaskRuntime.class);
        // 真实 Worker 池(用 mock 配置驱动并发数);Runner 内部按余量领取,池线程数即并发上限
        when(configService.getInt(eq("image_generation_concurrency"), anyInt())).thenReturn(2);
        when(configService.getInt(eq("image_gen_max_retry"), anyInt())).thenReturn(1);
        when(configService.getInt(eq("image_queue_size"), anyInt())).thenReturn(50);
        workerPool = new ImageWorkerPool(configService);
        workerPool.init();
        runner = new ImageStageRunner(stageService, workerPool, configService);

        when(stageService.isStagePaused(eq(PROJECT_ID), eq(STAGE))).thenReturn(false);
        when(stageService.resetRunningItems(PROJECT_ID, STAGE)).thenReturn(0);
        when(stageService.claimItem(anyLong())).thenReturn(true);
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
        ImageStageRunner.StageRunResult result = runner.run(PROJECT_ID, STAGE, runtime, it -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            Thread.sleep(200);
            active.decrementAndGet();
            return "{}";
        });

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
        ImageStageRunner.StageRunResult result = runner.run(PROJECT_ID, STAGE, runtime, it -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException("模型未返回图片");
            }
            return "{\"assetId\":11}";
        });

        assertThat(attempts.get()).isEqualTo(2);
        verify(stageService).markItemRetry(eq(1L), contains("模型未返回图片"));
        verify(stageService).markItemSuccess(eq(1L), contains("assetId"));
        verify(stageService, never()).markItemFailed(anyLong(), anyString());
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
        ImageStageRunner.StageRunResult result = runner.run(PROJECT_ID, STAGE, runtime, it -> {
            attempts.incrementAndGet();
            throw new RuntimeException("通道超时");
        });

        assertThat(attempts.get()).isEqualTo(2);
        verify(stageService).markItemRetry(eq(1L), anyString());
        verify(stageService).markItemFailed(eq(1L), contains("通道超时"));
        verify(runtime).stepFail(anyString());
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.success()).isZero();
    }

    @Test
    void stagePaused_claimsNothing() {
        when(stageService.isStagePaused(PROJECT_ID, STAGE)).thenReturn(true);

        ImageStageRunner.StageRunResult result = runner.run(PROJECT_ID, STAGE, runtime, it -> "{}");

        assertThat(result.paused()).isTrue();
        verify(stageService, never()).getPendingItemIds(anyLong(), anyString(), anyInt());
        verify(stageService, never()).claimItem(anyLong());
    }

    @Test
    void userStop_propagatesStopSignal() {
        org.mockito.Mockito.doThrow(new TaskStopSignal()).when(runtime).checkStop();

        assertThatThrownBy(() -> runner.run(PROJECT_ID, STAGE, runtime, it -> "{}"))
                .isInstanceOf(TaskStopSignal.class);
        verify(stageService, never()).claimItem(anyLong());
    }

    @Test
    void orphanRecovery_resetsRunningItemsBeforeRun() {
        when(stageService.resetRunningItems(PROJECT_ID, STAGE)).thenReturn(3);
        when(stageService.getPendingItemIds(PROJECT_ID, STAGE, 64)).thenReturn(List.of());

        runner.run(PROJECT_ID, STAGE, runtime, it -> "{}");

        verify(stageService).resetRunningItems(PROJECT_ID, STAGE);
    }

    @Test
    void inFlightFailureDuringStop_releasesItemBackToPending() {
        // Worker 执行中任务被停止 → checkStop 在 Worker 内抛出 → Item 归还 PENDING(下次继续),不丢失
        when(stageService.getPendingItemIds(PROJECT_ID, STAGE, 64))
                .thenAnswer(inv -> List.of(1L));
        when(stageService.getItem(1L)).thenReturn(item(1L, 11L, 0));
        // 第 1 次(主循环喂 Item 前)放行,第 2 次起(Worker 内)抛停止信号
        org.mockito.Mockito.doNothing().doThrow(new TaskStopSignal()).when(runtime).checkStop();

        assertThatThrownBy(() -> runner.run(PROJECT_ID, STAGE, runtime, it -> "{}"))
                .isInstanceOf(TaskStopSignal.class);

        verify(stageService, atLeastOnce()).releaseItem(1L);
        verify(stageService, never()).markItemSuccess(anyLong(), anyString());
    }
}
