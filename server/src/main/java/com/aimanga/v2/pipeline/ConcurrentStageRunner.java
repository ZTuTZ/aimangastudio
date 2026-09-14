package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.PipelineStageItem;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.task.TaskRuntime;
import com.aimanga.v2.task.TaskStopSignal;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通用阶段并发引擎(Phase 5.9 并发生图引擎,T5.11.6 基类化):Stage Item Pool → Worker Thread Pool → AI API。
 *
 * 架构:Pipeline 仍只有一个 Stage(不拆 200 个 Task),并发单元是 Stage Item,
 * 由本引擎按并发上限(热更新)把 PENDING Item 喂给指定 Worker 池。
 *
 * - 原子领取(§5):UPDATE...WHERE status=PENDING,影响行数=1 才执行,杜绝同一页重复生成;
 * - 失败重试(§6):单 Item 失败 → retry_count+1 → 回到 PENDING 重新执行,超过 max_retry 终态失败,不影响其他 Item;
 * - 暂停支持(§7):阶段 PAUSED 时不再领取新 Item,已领取的等待返回并保存结果;
 * - 重启恢复(§8):启动时回收孤儿 RUNNING Item(SUCCESS 跳过、PENDING 重新执行);
 * - 幂等顺序(§9):处理器内部先保存 OSS → 更新业务 URL → 才标 Item SUCCESS;
 * - 进度统计(§10):每个 Item 终态后刷新 Stage 的 total/success/failed/progress;
 * - 执行互斥(T5.11.3/T5.11.4):同一 project+stage 同时只允许一个活跃 Runner(Redisson RLock),
 *   resetRunningItems 仅在持有锁时执行,不会回收其他存活 Runner 的在跑 Item。
 */
@Slf4j
@Component
public class ConcurrentStageRunner {

    private static final long POLL_INTERVAL_MS = 500;
    private static final long DRAIN_TIMEOUT_MS = 15 * 60 * 1000;
    private static final int FEED_BATCH = 64;

    private final PipelineStageService stageService;
    private final ImageWorkerPool imageWorkerPool;
    private final ScriptWorkerPool scriptWorkerPool;
    private final ConfigService configService;
    private final RedissonClient redissonClient;

    public ConcurrentStageRunner(PipelineStageService stageService, ImageWorkerPool imageWorkerPool,
                                 ScriptWorkerPool scriptWorkerPool, ConfigService configService,
                                 RedissonClient redissonClient) {
        this.stageService = stageService;
        this.imageWorkerPool = imageWorkerPool;
        this.scriptWorkerPool = scriptWorkerPool;
        this.configService = configService;
        this.redissonClient = redissonClient;
    }

    /** 单 Item 处理器:输入 Item,输出结果引用 JSON;抛异常触发重试/终态失败 */
    @FunctionalInterface
    public interface StageItemProcessor {
        String process(PipelineStageItem item) throws Exception;
    }

    /**
     * 执行引擎规格:决定用哪个 Worker 池、并发与重试配置键(T5.11.6)。
     * 生图阶段用 imageEngine(),脚本阶段用 scriptEngine(),互不占用资源。
     */
    public record StageEngine(AbstractStageWorkerPool pool, String concurrencyKey, int concurrencyDefault,
                              int concurrencyMax, String retryKey, int retryDefault) {}

    public StageEngine imageEngine() {
        return new StageEngine(imageWorkerPool, "image_generation_concurrency", 5, 32, "image_gen_max_retry", 3);
    }

    public StageEngine scriptEngine() {
        return new StageEngine(scriptWorkerPool, "script_item_concurrency", 5, 32, "script_gen_max_retry", 3);
    }

    /** 引擎执行结果 */
    public record StageRunResult(int success, int failed, int retried, boolean paused) {}

    public StageRunResult run(Long projectId, String stageType, TaskRuntime runtime, StageItemProcessor processor,
                              StageEngine engine) {
        // T5.11.3/T5.11.4:同一 project+stage 同时只允许一个活跃 Runner。
        // leaseTime=-1 → Redisson 看门狗自动续期,进程死亡后锁自动释放,重启恢复可接管。
        RLock stageLock = redissonClient.getLock("aimanga:v2:stage-run:" + projectId + ":" + stageType);
        try {
            if (!stageLock.tryLock(0, -1, TimeUnit.SECONDS)) {
                throw new BusinessException(409, "该作品「" + stageType + "」阶段已有生成任务在执行中,请等待完成后再发起");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(409, "该作品「" + stageType + "」阶段已有生成任务在执行中,请等待完成后再发起");
        }
        try {
            return runLocked(projectId, stageType, runtime, processor, engine);
        } finally {
            stageLock.unlock();
        }
    }

    /** 持有阶段执行锁后的主流程 */
    private StageRunResult runLocked(Long projectId, String stageType, TaskRuntime runtime,
                                     StageItemProcessor processor, StageEngine engine) {
        // 吸取配置热更新(外部改库/配置中心保存后,下一次执行即生效)
        engine.pool().refresh();
        int concurrency = Math.max(1, Math.min(engine.concurrencyMax(),
                configService.getInt(engine.concurrencyKey(), engine.concurrencyDefault())));
        int maxRetry = Math.max(0, Math.min(10, configService.getInt(engine.retryKey(), engine.retryDefault())));

        // §8 重启恢复:回收孤儿 RUNNING(上次进程崩溃/被杀的残留)。
        // 此刻已持有 project+stage 唯一执行锁(T5.11.4):不存在其他存活 Runner,重置安全。
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

        log.info("[stage] {} {} 并发执行开始: 并发={}, maxRetry={}", projectId, stageType, concurrency, maxRetry);

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
                    engine.pool().submit(() -> {
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
        log.info("[stage] {} {} 并发执行结束: 成功={} 失败={} 重试={} 暂停={}",
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

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskStopSignal();
        }
    }
}
