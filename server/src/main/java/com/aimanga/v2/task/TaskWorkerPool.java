package com.aimanga.v2.task;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.TaskMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务 Worker 池:
 * - 线程数 = task_max_concurrency(全局并行任务数,层①),可热更新(重建池,旧池优雅退出);
 * - 每个线程循环:阻塞取任务 → 每用户并发许可(层②) → 领取(RUNNING) → 执行 → 释放许可;
 * - 领取不到许可的任务延迟 2s 重新入队;
 * - 池缩容通过毒丸让阻塞的 take() 退出,正在执行的任务自然完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskWorkerPool {

    private final TaskQueue taskQueue;
    private final TaskMapper taskMapper;
    private final TaskRunner taskRunner;
    private final RedisSemaphores semaphores;
    private final com.aimanga.v2.service.ConfigService configService;

    private volatile ExecutorService pool;
    private final AtomicInteger poolSize = new AtomicInteger();

    @PostConstruct
    public void start() {
        refresh(configService.getInt("task_max_concurrency", 5));
        log.info("[task] Worker 池已启动,全局并行任务数={}", poolSize.get());
    }

    /** 配置热更新入口(保存系统配置后调用) */
    public synchronized void refresh(int newSize) {
        int target = Math.max(1, Math.min(64, newSize));
        if (pool != null && poolSize.get() == target) {
            return;
        }
        ExecutorService old = pool;
        if (old != null) {
            taskQueue.poison(poolSize.get());
            old.shutdown();
        }
        pool = Executors.newFixedThreadPool(target, r -> {
            Thread t = new Thread(r, "task-worker");
            t.setDaemon(true);
            return t;
        });
        poolSize.set(target);
        for (int i = 0; i < target; i++) {
            pool.submit(this::workerLoop);
        }
        log.info("[task] Worker 池已重建,全局并行任务数={}", target);
    }

    @PreDestroy
    public void shutdown() {
        if (pool != null) {
            taskQueue.poison(poolSize.get());
            pool.shutdown();
        }
        log.info("[task] Worker 池已关闭");
    }

    private void workerLoop() {
        while (true) {
            long taskId;
            try {
                taskId = taskQueue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (taskId == TaskQueue.POISON_PILL) {
                return;
            }
            try {
                executeWithPermit(taskId);
            } catch (Exception e) {
                log.error("[task] worker 执行异常 taskId={}", taskId, e);
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
        if (!semaphores.tryAcquireUser(task.getUserId())) {
            taskQueue.enqueueDelayed(taskId, 2000);
            return;
        }
        try {
            taskRunner.run(taskId);
        } finally {
            semaphores.releaseUser(task.getUserId());
        }
    }
}
