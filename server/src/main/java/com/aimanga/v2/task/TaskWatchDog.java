package com.aimanga.v2.task;

import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.TaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 僵尸任务看门狗(Phase 8.4 §6.5 双超时):
 * ① 租约超时(lease_until < NOW)→ Worker 失联,回收重试/失败;
 * ② 执行超时(start_time + max_execution_seconds < NOW)→ Worker 有心跳但本次 Attempt 运行过久;
 * ③ STOPPING 僵尸(Phase 8.4 §6.6)→ Worker 失联,直接 STOPPED(用户停止意图不可丢失)。
 * 旧执行晚回来由 Phase 8.1 fencing 拦截,不覆盖最新结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskWatchDog {

    private final TaskMapper taskMapper;
    private final TaskQueue taskQueue;

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void detectZombies() {
        // ① 心跳超时(原有路径)
        for (TaskEntity zombie : taskMapper.selectZombies()) {
            handle(zombie, "执行超时(心跳超过 " + zombie.getTimeoutSeconds() + " 秒,判定 Worker 已失联)");
        }
        // ② 租约过期 / 执行超时(Phase 8.4)
        for (TaskEntity task : taskMapper.selectLeaseOrExecutionTimeout()) {
            boolean leaseExpired = task.getLeaseUntil() != null
                    && task.getLeaseUntil().isBefore(java.time.LocalDateTime.now());
            handle(task, leaseExpired
                    ? "租约过期(Worker 失联,Phase 8.4)"
                    : "执行超时(超过 max_execution_seconds=" + task.getMaxExecutionSeconds() + " 秒,Phase 8.4)");
        }
        // ③ STOPPING 僵尸 → STOPPED(§6.6)
        for (TaskEntity task : taskMapper.selectStoppingZombies()) {
            if (task.getClaimToken() == null) continue;
            int updated = taskMapper.stopZombie(task.getId(), task.getClaimToken());
            if (updated > 0) {
                log.warn("[watchdog] STOPPING 僵尸任务判定 STOPPED taskId={}", task.getId());
            }
        }
    }

    private void handle(TaskEntity task, String error) {
        String token = task.getClaimToken();
        if (token == null) return;
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        int maxRetry = task.getMaxRetryCount() == null ? 3 : task.getMaxRetryCount();
        if (retryCount < maxRetry) {
            int updated = taskMapper.requeueRevoked(task.getId(), token, error);
            if (updated > 0) {
                log.warn("[watchdog] 任务回收重新排队 taskId={} retry={}/{}: {}",
                        task.getId(), retryCount + 1, maxRetry, error);
                try {
                    taskQueue.enqueue(task.getId());
                } catch (RuntimeException e) {
                    log.warn("[watchdog] Redis 返队失败，任务保留 PENDING 等待补偿 taskId={}", task.getId(), e);
                }
            }
        } else {
            int updated = taskMapper.failRevoked(task.getId(), token, error);
            if (updated > 0) {
                log.error("[watchdog] 任务超过最大重试次数,判定失败 taskId={} retry={}/{}",
                        task.getId(), retryCount, maxRetry);
            }
        }
    }
}
