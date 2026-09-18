package com.aimanga.v2.pipeline;

import com.aimanga.v2.service.ConfigService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 阶段 Worker 池基类(Phase 5.11 T5.11.6):
 * ConcurrentStageRunner 的执行载体,固定大小 ThreadPoolExecutor(禁止 new Thread)。
 * 并发数可热更新平滑扩缩容(扩容立即生效,缩容空闲线程自然退出);
 * Runner 的"按余量领取"策略保证队列不会积压超过并发数。
 *
 * 子类:ImageWorkerPool(生图) / ScriptWorkerPool(脚本) —— 互不占用,
 * SCRIPT 文本请求不再挤占生图 Worker 池。
 */
@Slf4j
public abstract class AbstractStageWorkerPool {

    protected final ConfigService configService;
    private final AtomicInteger threadSeq = new AtomicInteger();
    private ThreadPoolExecutor pool;
    private volatile int currentSize;
    private volatile int queueSize;

    protected AbstractStageWorkerPool(ConfigService configService) {
        this.configService = configService;
    }

    /** 并发数配置键 */
    protected abstract String concurrencyKey();

    protected abstract int concurrencyDefault();

    /** 并发上限(防呆) */
    protected abstract int concurrencyMax();

    /** 队列容量配置键 */
    protected abstract String queueSizeKey();

    protected abstract int queueSizeDefault();

    /** 线程名前缀 */
    protected abstract String threadNamePrefix();

    @PostConstruct
    public void init() {
        queueSize = clamp(configService.getInt(queueSizeKey(), queueSizeDefault()), 10, 1000);
        refresh();
        log.info("[stage-pool] {} Worker 池已启动: 并发={}, 队列={}", threadNamePrefix(), currentSize, queueSize);
    }

    /** 配置热更新入口(保存系统配置后调用):按目标并发平滑扩缩容 */
    public synchronized void refresh() {
        int target = clamp(configService.getInt(concurrencyKey(), concurrencyDefault()), 1, concurrencyMax());
        if (pool == null) {
            pool = new ThreadPoolExecutor(target, target, 60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(queueSize), this::newThread);
            pool.allowCoreThreadTimeOut(true);
            currentSize = target;
            return;
        }
        if (target == currentSize) {
            return;
        }
        // 平滑扩缩容(Phase 8.5 §7.5:顺序敏感)
        // 扩容:先 max 后 core;缩容:先 core 后 max(避免 core > max 抛 IllegalArgumentException)
        if (target > currentSize) {
            pool.setMaximumPoolSize(target);
            pool.setCorePoolSize(target);
        } else {
            pool.setCorePoolSize(target);
            pool.setMaximumPoolSize(target);
        }
        log.info("[stage-pool] {} 并发 {} → {}(热更新)", threadNamePrefix(), currentSize, target);
        currentSize = target;
    }

    public void submit(Runnable task) {
        ThreadPoolExecutor executor = pool;
        if (executor == null) {
            throw new IllegalStateException(threadNamePrefix() + " Worker 池尚未初始化");
        }
        executor.submit(task);
    }

    @PreDestroy
    public void shutdown() {
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            // 优雅停机:等待在跑的请求完成(结果已保存的不浪费),超时部分交给重启恢复接管
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("[stage-pool] {} Worker 池 30 秒内未完成,剩余 Item 将由重启恢复接管", threadNamePrefix());
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        log.info("[stage-pool] {} Worker 池已关闭", threadNamePrefix());
    }

    private Thread newThread(Runnable r) {
        Thread t = new Thread(r, threadNamePrefix() + "-" + threadSeq.incrementAndGet());
        t.setDaemon(true);
        return t;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
