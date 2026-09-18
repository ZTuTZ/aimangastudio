package com.aimanga.v2.task;

import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.TaskMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务 Worker 池(Phase 8.5 重写):
 * - 线程数 = task_max_concurrency(层①),热更新平滑扩缩容;
 * - 不再使用共享业务队列投递毒丸(Phase 8.5 §7.1/§7.2):Worker 以 poll(2s) 轮询 +
 *   stopRequested 标志退出 —— 线程控制消息不进业务任务队列;
 * - 扩容:追加 Worker;缩容:多余 Worker 置 stopRequested,正在执行的任务完成后再退出(不 interrupt);
 * - 优雅停机:停止领取 → 当前任务收尾 → grace 30s → 未完成由 lease/watchdog 接管(Phase 8.4)。
 */
@Slf4j
@Component
public class TaskWorkerPool {

    private final TaskQueue taskQueue;
    private final TaskMapper taskMapper;
    private final TaskRunner taskRunner;
    private final RedisConcurrencyLimiter concurrencyLimiter;
    private final com.aimanga.v2.service.ConfigService configService;

    private final List<WorkerHandle> workers = new ArrayList<>();
    private final AtomicInteger poolSize = new AtomicInteger();
    private ExecutorService executor;

    private static class WorkerHandle {
        final AtomicBoolean stopRequested = new AtomicBoolean(false);
        volatile Future<?> future;
    }

    public TaskWorkerPool(TaskQueue taskQueue, TaskMapper taskMapper, TaskRunner taskRunner,
                          RedisConcurrencyLimiter concurrencyLimiter,
                          com.aimanga.v2.service.ConfigService configService) {
        this.taskQueue = taskQueue;
        this.taskMapper = taskMapper;
        this.taskRunner = taskRunner;
        this.concurrencyLimiter = concurrencyLimiter;
        this.configService = configService;
    }

    @PostConstruct
    public void start() {
        executor = Executors.newFixedThreadPool(64, r -> {
            Thread t = new Thread(r, "task-worker");
            t.setDaemon(true);
            return t;
        });
        refresh(configService.getInt("task_max_concurrency", 5));
        log.info("[task] Worker 池已启动,全局并行任务数={}", poolSize.get());
    }

    /** 配置热更新入口(保存系统配置后调用):平滑扩缩容 */
    public synchronized void refresh(int newSize) {
        int target = Math.max(1, Math.min(64, newSize));
        int current = poolSize.get();
        if (target == current) {
            return;
        }
        if (target > current) {
            // 扩容:追加 Worker
            for (int i = current; i < target; i++) {
                WorkerHandle handle = new WorkerHandle();
                handle.future = executor.submit(() -> workerLoop(handle));
                workers.add(handle);
            }
        } else {
            // 缩容:标记多余的 Worker 停止;正在执行的任务完成后自然退出(§7.3)
            int toStop = current - target;
            int stopped = 0;
            for (WorkerHandle handle : workers) {
                if (stopped >= toStop) break;
                if (handle.stopRequested.compareAndSet(false, true)) {
                    stopped++;
                }
            }
        }
        poolSize.set(target);
        log.info("[task] Worker 池热更新: {} → {}", current, target);
    }

    @PreDestroy
    public void shutdown() {
        // Phase 8.5 §7.4:停止领取 → 当前任务收尾 → grace 30s → 未完成由 lease recovery 接管
        for (WorkerHandle handle : workers) {
            handle.stopRequested.set(true);
        }
        log.info("[task] Worker 池停机中:等待在跑任务收尾(最多 30 秒)...");
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            boolean allIdle = workers.stream().allMatch(w -> w.stopRequested.get()
                    && !isRunningTask(w));
            if (allIdle || workers.isEmpty()) break;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        log.info("[task] Worker 池已关闭(未完成任务由 lease/watchdog 接管)");
    }

    private volatile long runningTaskId = -1;

    private boolean isRunningTask(WorkerHandle w) {
        return runningTaskId != -1;
    }

    private void workerLoop(WorkerHandle handle) {
        while (!handle.stopRequested.get()) {
            Long taskId;
            try {
                taskId = taskQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS); // Phase 8.5 §7.2:poll 替代 take
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (taskId == null) {
                continue; // 轮询超时,重新检查 stopRequested
            }
            taskQueue.removeMarker(taskId);
            if (handle.stopRequested.get()) {
                // 缩容/停机请求到达:任务放回队列,由其他 Worker 继续
                taskQueue.enqueue(taskId);
                return;
            }
            runningTaskId = taskId;
            try {
                executeWithPermit(taskId);
            } catch (Exception e) {
                log.error("[task] worker 执行异常 taskId={}", taskId, e);
            } finally {
                runningTaskId = -1;
            }
        }
    }

    private void executeWithPermit(long taskId) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null || task.getUserId() == null) {
            return;
        }
        if (!TaskStatus.active(task.getStatus() == null ? TaskStatus.PENDING : task.getStatus())) {
            return; // 已被停止/已完成,跳过
        }
        // Phase 8.6:ZSET limiter 许可 token(实例崩溃自动过期,无需启动重置)
        String permit = concurrencyLimiter.tryAcquireUser(task.getUserId());
        if (permit == null) {
            taskQueue.enqueueDelayed(taskId, 2000);
            return;
        }
        try {
            taskRunner.run(taskId);
        } finally {
            concurrencyLimiter.releaseUser(task.getUserId(), permit);
        }
    }
}
