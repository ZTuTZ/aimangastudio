package com.aimanga.v2.pipeline;

import com.aimanga.v2.service.ConfigService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生图 Worker 池(Phase 5.9 §3/§4):
 * - Stage Item 的执行载体,固定大小 ThreadPoolExecutor(禁止 new Thread);
 * - 并发数 = image_generation_concurrency(默认 5),保存配置后 refresh() 平滑扩缩容;
 *   扩容立即生效,缩容让多余空闲线程自然退出(allowCoreThreadTimeOut);
 * - 队列容量 = image_queue_size(默认 50,启动时读取);Runner 的"按余量领取"策略保证
 *   队列不会积压超过并发数,不会触发拒绝;
 * - 该池是全局的:所有作品的生图阶段共享同一并发上限,与 ai_image_concurrency(层④)叠加限流。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageWorkerPool {

    private final ConfigService configService;

    private ThreadPoolExecutor pool;
    private final AtomicInteger threadSeq = new AtomicInteger();
    private volatile int currentSize;
    private volatile int queueSize;

    @PostConstruct
    public void init() {
        queueSize = clamp(configService.getInt("image_queue_size", 50), 10, 1000);
        refresh();
        log.info("[image] 生图 Worker 池已启动: 并发={}, 队列={}", currentSize, queueSize);
    }

    /** 配置热更新入口(保存系统配置后调用):按目标并发平滑扩缩容 */
    public synchronized void refresh() {
        int target = clamp(configService.getInt("image_generation_concurrency", 5), 1, 32);
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
        // 平滑扩缩容:扩容立即生效;缩容后多余 Worker 空闲 60s 自动退出
        pool.setMaximumPoolSize(target);
        pool.setCorePoolSize(target);
        log.info("[image] 生图并发 {} → {}(热更新)", currentSize, target);
        currentSize = target;
    }

    public void submit(Runnable task) {
        ThreadPoolExecutor executor = pool;
        if (executor == null) {
            throw new IllegalStateException("生图 Worker 池尚未初始化");
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
            // 优雅停机:等待在跑的生图请求完成(结果已保存的不浪费),超时部分交给重启恢复接管
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("[image] 生图 Worker 池 30 秒内未完成,剩余 Item 将由重启恢复接管");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        log.info("[image] 生图 Worker 池已关闭");
    }

    private Thread newThread(Runnable r) {
        Thread t = new Thread(r, "image-gen-" + threadSeq.incrementAndGet());
        t.setDaemon(true);
        return t;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
