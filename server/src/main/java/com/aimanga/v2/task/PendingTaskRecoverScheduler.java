package com.aimanga.v2.task;

import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.TaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 任务补偿调度器(Phase 5.6):
 * MySQL 是任务唯一事实 —— 每 60 秒扫描"排队中超过 60 秒"的任务重新入队,
 * 解决 Redis 队列消息丢失/毒丸误杀/Worker 崩溃导致的排队任务无人消费。
 * 重复入队天然安全:领取为原子操作,同一任务只会被执行一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingTaskRecoverScheduler {

    private final TaskMapper taskMapper;
    private final TaskQueue taskQueue;
    private final OperationalMetrics metrics;

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void recoverStalePendingTasks() {
        List<TaskEntity> stale = taskMapper.selectStalePending();
        for (TaskEntity task : stale) {
            try {
                if (taskQueue.enqueueIfAbsent(task.getId())) {
                    metrics.queueRecovery();
                    log.info("[task] Redis 补偿:排队超时的任务重新入队 taskId={} type={}", task.getId(), task.getTaskType());
                }
            } catch (RuntimeException e) {
                log.warn("[task] Redis 补偿暂时失败，任务继续保留 PENDING taskId={}", task.getId(), e);
            }
        }
    }
}
