package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.PipelineStageItem;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.task.TaskRuntime;
import com.aimanga.v2.task.TaskStopSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发生图引擎(Phase 5.9):Stage Item Pool → Worker Thread Pool → AI Image API。
 *
 * 架构:Pipeline 仍只有一个 Stage(不拆 200 个 Task),并发单元是 Stage Item,
 * 由本引擎按并发上限(image_generation_concurrency,热更新)把 PENDING Item 喂给生图 Worker 池。
 *
 * - 原子领取(§5):UPDATE...WHERE status=PENDING,影响行数=1 才执行,杜绝同一页重复生成;
 * - 失败重试(§6):单 Item 失败 → retry_count+1 → 回到 PENDING 重新执行,超过 max_retry 终态失败,不影响其他 Item;
 * - 暂停支持(§7):阶段 PAUSED 时不再领取新 Item,已领取的等待返回并保存结果;
 * - 重启恢复(§8):启动时回收孤儿 RUNNING Item(SUCCESS 跳过、PENDING 重新执行);
 *   进程崩溃的检测由任务层 claim_token+心跳+看门狗完成,恢复后 Handler 重跑本引擎;
 * - 幂等顺序(§9):处理器内部先保存 OSS → 更新业务 URL → 才标 Item SUCCESS;
 * - 进度统计(§10):每个 Item 终态后刷新 Stage 的 total/success/failed/progress。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageStageRunner {

    private static final long POLL_INTERVAL_MS = 500;
    private static final long DRAIN_TIMEOUT_MS = 15 * 60 * 1000;
    private static final int FEED_BATCH = 64;

    private final PipelineStageService stageService;
    private final ImageWorkerPool workerPool;
    private final ConfigService configService;

    /** 单 Item 处理器:输入 Item,输出结果引用 JSON;抛异常触发重试/终态失败 */
    @FunctionalInterface
    public interface StageItemProcessor {
        String process(PipelineStageItem item) throws Exception;
    }

    /** 引擎执行结果 */
    public record StageRunResult(int success, int failed, int retried, boolean paused) {}

    public StageRunResult run(Long projectId, String stageType, TaskRuntime runtime, StageItemProcessor processor) {
        // 吸取配置热更新(外部改库/配置中心保存后,下一次执行即生效)
        workerPool.refresh();
        int concurrency = currentConcurrency();
        int maxRetry = Math.max(0, Math.min(10, configService.getInt("image_gen_max_retry", 3)));

        // §8 重启恢复:回收孤儿 RUNNING(上次进程崩溃/被杀的残留)。
        // 任务层 claim_token 保证同一阶段任务同时只有一个 Runner,启动时重置是安全的。
        int recovered = stageService.resetRunningItems(projectId, stageType);
        if (recovered > 0) {
            log.warn("[stage] {} {} 回收 {} 个孤儿 RUNNING Item → 重新排队", projectId, stageType, recovered);
        }

        Set<Long> claimed = ConcurrentHashMap.newKeySet();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger retried = new AtomicInteger();
        AtomicBoolean paused = new AtomicBoolean(false);
        AtomicBoolean stopped = new AtomicBoolean(false);

        log.info("[stage] {} {} 并发生图开始: 并发={}, maxRetry={}", projectId, stageType, concurrency, maxRetry);

        // 主循环:领 Item → 喂池子 → 等待,直至无可领取且无在跑 / 暂停 / 停止
        while (true) {
            try {
                runtime.checkStop();
            } catch (TaskStopSignal s) {
                stopped.set(true);
                break;
            }
            if (stageService.isStagePaused(projectId, stageType)) {
                paused.set(true); // §7 不再领取新 Item,在跑的等待返回后保存结果
                break;
            }
            int capacity = concurrency - inFlight.get();
            while (capacity > 0) {
                PipelineStageItem item = claimNext(projectId, stageType, claimed);
                if (item == null) {
                    break;
                }
                inFlight.incrementAndGet();
                try {
                    workerPool.submit(() -> {
                        try {
                            processItem(projectId, stageType, runtime, processor, item, maxRetry,
                                    claimed, inFlight, success, failed, retried, paused, stopped);
                        } catch (Throwable t) {
                            // 兜底:processItem 内部已捕获 Exception,这里只防 Error 静默丢失(inFlight 已在其 finally 归还)
                            log.error("[stage] {} Item {} Worker 未捕获异常", stageType, item.getId(), t);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    // 理论不可达(Runner 按余量领取,队列不积压);兜底:归还 Item,下轮重领
                    inFlight.decrementAndGet();
                    claimed.remove(item.getId());
                    stageService.releaseItem(item.getId());
                    log.warn("[stage] {} 提交失败已归还 Item {}: {}", stageType, item.getId(), e.getMessage());
                    break;
                }
                capacity--;
            }
            if (inFlight.get() == 0) {
                break; // 无可领取且无在跑 → 全部 Item 已到终态
            }
            sleep(POLL_INTERVAL_MS);
        }

        // 等待在跑 Item 收尾(暂停/停止时也等待):已领取的请求等待返回并保存结果,不浪费已花的 API 费用
        awaitDrain(inFlight);

        if (stopped.get()) {
            throw new TaskStopSignal();
        }
        StageRunResult result = new StageRunResult(success.get(), failed.get(), retried.get(), paused.get());
        log.info("[stage] {} {} 并发生图结束: 成功={} 失败={} 重试={} 暂停={}",
                projectId, stageType, result.success(), result.failed(), result.retried(), result.paused());
        return result;
    }

    /** 原子领取下一个待执行 Item(§5):领取失败(被抢)自动换下一个候选 */
    private PipelineStageItem claimNext(Long projectId, String stageType, Set<Long> claimed) {
        List<Long> candidates = stageService.getPendingItemIds(projectId, stageType, FEED_BATCH);
        for (Long id : candidates) {
            if (!claimed.add(id)) {
                continue;
            }
            if (stageService.claimItem(id)) {
                PipelineStageItem item = stageService.getItem(id);
                if (item != null) {
                    return item;
                }
            }
            claimed.remove(id);
        }
        return null;
    }

    /** Worker 执行体:单 Item 处理 → 成功/重试/终态失败(§6),不抛出异常(异常在内部消化为 Item 状态) */
    private void processItem(Long projectId, String stageType, TaskRuntime runtime, StageItemProcessor processor,
                             PipelineStageItem item, int maxRetry, Set<Long> claimed, AtomicInteger inFlight,
                             AtomicInteger success, AtomicInteger failed, AtomicInteger retried,
                             AtomicBoolean paused, AtomicBoolean stopped) {
        try {
            runtime.checkStop();
            if (paused.get() || stopped.get()) {
                // 领取后阶段被暂停/任务被停止:归还 Item,下次继续
                stageService.releaseItem(item.getId());
                return;
            }
            String resultRef = processor.process(item);
            stageService.markItemSuccess(item.getId(), resultRef); // §9 处理器内部已先保存 OSS/业务数据
            success.incrementAndGet();
            runtime.stepSuccess();
            stageService.updateStageProgress(projectId, stageType);
        } catch (TaskStopSignal s) {
            stageService.releaseItem(item.getId());
            claimed.remove(item.getId());
        } catch (Exception e) {
            int retryCount = item.getRetryCount() == null ? 0 : item.getRetryCount();
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (retryCount < maxRetry) {
                // §6 失败重试:retry_count+1 → 回到 PENDING,下一轮重新领取执行
                stageService.markItemRetry(item.getId(), message);
                claimed.remove(item.getId());
                retried.incrementAndGet();
                log.warn("[stage] {} Item {} 第 {} 次失败将重试: {}", stageType, item.getId(), retryCount + 1, message);
            } else {
                stageService.markItemFailed(item.getId(), message);
                failed.incrementAndGet();
                runtime.stepFail(message);
                stageService.updateStageProgress(projectId, stageType);
                log.warn("[stage] {} Item {} 重试 {} 次后仍失败: {}", stageType, item.getId(), retryCount, message);
            }
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** 等待在跑 Item 全部收尾(有超时保护,超时后处理器仍在后台自行完成) */
    private void awaitDrain(AtomicInteger inFlight) {
        long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        while (inFlight.get() > 0) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("[stage] 等待在跑 Item 收尾超时({} 分钟),交由后台线程自行完成", DRAIN_TIMEOUT_MS / 60000);
                return;
            }
            sleep(300);
        }
    }

    private int currentConcurrency() {
        return Math.max(1, Math.min(32, configService.getInt("image_generation_concurrency", 5)));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskStopSignal();
        }
    }
}
