package com.aimanga.v2.task;

import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.TaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 僵尸任务看门狗(Phase 5.6):
 * 每 30 秒扫描"进行中但心跳超时"的任务(Worker 崩溃/强杀导致):
 * - retry_count < max_retry_count → 重新排队(自动重试次数 +1,执行锁/心跳清空);
 * - 超过次数 → 失败。
 * 原 Worker 若仍存活,其终态写入会因执行锁失效被自动跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskWatchDog {

    private final TaskMapper taskMapper;
    private final TaskQueue taskQueue;

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void detectZombies() {
        List<TaskEntity> zombies = taskMapper.selectZombies();
        for (TaskEntity zombie : zombies) {
            String token = zombie.getClaimToken();
            if (token == null) continue;
            int retryCount = zombie.getRetryCount() == null ? 0 : zombie.getRetryCount();
            int maxRetry = zombie.getMaxRetryCount() == null ? 3 : zombie.getMaxRetryCount();
            String error = "执行超时(心跳超过 " + zombie.getTimeoutSeconds() + " 秒,判定 Worker 已失联)";
            if (retryCount < maxRetry) {
                int updated = taskMapper.requeueZombie(zombie.getId(), token, error);
                if (updated > 0) {
                    log.warn("[watchdog] 僵尸任务重新排队 taskId={} retry={}/{}", zombie.getId(), retryCount + 1, maxRetry);
                    taskQueue.enqueue(zombie.getId());
                }
            } else {
                int updated = taskMapper.failZombie(zombie.getId(), token, error);
                if (updated > 0) {
                    log.error("[watchdog] 僵尸任务超过最大重试次数,判定失败 taskId={} retry={}/{}",
                            zombie.getId(), retryCount, maxRetry);
                }
            }
        }
    }
}
