package com.aimanga.v2.task;

import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.TaskMapper;
import com.aimanga.v2.service.TaskPlanningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Builds immutable plans for tasks created before the execution-plan migration. */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyTaskPlanRecovery {

    private final TaskMapper taskMapper;
    private final TaskPlanningService planningService;
    private final TaskQueue taskQueue;

    @EventListener(ApplicationReadyEvent.class)
    public void afterStartup() {
        recoverUnplannedTasks();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void recoverUnplannedTasks() {
        for (TaskEntity task : taskMapper.selectUnplannedPendingOrPaused()) {
            try {
                planningService.initializePlan(task);
                log.info("[task-plan] 旧任务执行计划迁移完成 taskId={} type={}", task.getId(), task.getTaskType());
            } catch (Exception e) {
                String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                String error = truncate("旧任务范围无法安全恢复,需要人工处理: " + detail);
                taskMapper.failUnplanned(task.getId(), error);
                log.error("[task-plan] 旧任务计划迁移失败 taskId={} type={}",
                        task.getId(), task.getTaskType(), e);
                continue;
            }
            if (task.getStatus() != null && task.getStatus() == TaskStatus.PENDING) {
                try {
                    taskQueue.enqueueIfAbsent(task.getId());
                } catch (RuntimeException e) {
                    log.warn("[task-plan] 计划已迁移但 Redis 暂不可用，等待数据库补偿 taskId={}", task.getId(), e);
                }
            }
        }
    }

    private static String truncate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
