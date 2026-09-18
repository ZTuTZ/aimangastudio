package com.aimanga.v2.task;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.TaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 任务执行器(Phase 5.6 可靠性增强):
 * - 原子领取:生成 claim_token,UPDATE...WHERE status=0 防双 Worker/重试竞态;
 * - 30 秒心跳:看门狗据此区分"仍在执行"与"僵尸";
 * - 终态写入带执行锁校验:看门狗已接管的任务,原 Worker 的终态写入自动作废;
 * - 所有状态转换以 MySQL 为准。
 */
@Slf4j
@Component
public class TaskRunner {

    private static final long HEARTBEAT_INTERVAL_SECONDS = 30;

    private final TaskMapper taskMapper;
    private final TaskEventPublisher publisher;
    private final Map<String, TaskHandler> handlers;
    private final java.util.concurrent.ScheduledExecutorService heartbeatScheduler =
            java.util.concurrent.Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "task-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public TaskRunner(TaskMapper taskMapper, TaskEventPublisher publisher, List<TaskHandler> handlerList) {
        this.taskMapper = taskMapper;
        this.publisher = publisher;
        this.handlers = handlerList.stream().collect(Collectors.toMap(TaskHandler::type, Function.identity()));
        log.info("[task] 已注册任务处理器: {}", handlers.keySet());
    }

    public void run(long taskId) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        // Phase 5.6 原子领取:生成执行锁,仅当仍为排队中时领取成功
        String claimToken = java.util.UUID.randomUUID().toString().replace("-", "");
        if (taskMapper.claim(taskId, claimToken) == 0) {
            return;
        }
        TaskEntity running = taskMapper.selectById(taskId);
        running.setStartTime(LocalDateTime.now());
        running.setClaimToken(claimToken);
        publisher.publishStatus(running, TaskStatus.RUNNING, "任务开始");
        TaskRuntime runtime = new TaskRuntime(taskMapper, publisher, running);

        // 30 秒心跳:看门狗据此区分"仍在执行"与"僵尸"
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                () -> taskMapper.heartbeat(taskId, claimToken),
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        try {
            TaskHandler handler = handlers.get(running.getTaskType());
            if (handler == null) {
                throw new BusinessException(500, "该任务类型暂未实现: " + running.getTaskType());
            }
            handler.run(running, runtime);
            int fail = runtime.failCount();
            int success = runtime.successCount();
            if (fail > 0 && success > 0) {
                finish(running, claimToken, runtime, TaskStatus.PARTIAL, null);
            } else if (fail > 0) {
                finish(running, claimToken, runtime, TaskStatus.FAILED, "全部步骤失败");
            } else {
                finish(running, claimToken, runtime, TaskStatus.SUCCESS, null);
            }
        } catch (TaskStopSignal s) {
            log.info("[task] 任务被用户停止 taskId={}", taskId);
            finish(running, claimToken, runtime, TaskStatus.STOPPED, "已停止");
        } catch (TaskPauseSignal p) {
            // Phase 8.3:暂停 → Task=PAUSED,保留 payload/progress/counts,同一 Task 可继续
            pause(running, claimToken, runtime);
        } catch (BusinessException e) {
            log.warn("[task] 任务失败 taskId={}: {}", taskId, e.getMessage(), e);
            finish(running, claimToken, runtime, TaskStatus.FAILED, e.getMessage());
        } catch (Exception e) {
            log.error("[task] 任务异常 taskId={}", taskId, e);
            finish(running, claimToken, runtime, TaskStatus.FAILED, truncate(e.getMessage()));
        } finally {
            heartbeat.cancel(false);
        }
    }

    /** 暂停写入(Phase 8.3):RUNNING → PAUSED,带执行锁校验;保留 payload/进度/计数,清 claim/heartbeat */
    private void pause(TaskEntity task, String claimToken, TaskRuntime runtime) {
        int updated = taskMapper.pauseTask(task.getId(), claimToken);
        if (updated == 0) {
            log.warn("[task] 暂停写入被跳过(执行锁已失效) taskId={}", task.getId());
            return;
        }
        TaskEntity latest = taskMapper.selectById(task.getId());
        if (latest != null) {
            publisher.publishStatus(latest, TaskStatus.PAUSED, "已暂停(进行中的请求已保存)");
        }
        log.info("[task] 任务已暂停 taskId={} progress={} success={}/{}",
                task.getId(), runtime.progress(), runtime.successCount(), runtime.total());
    }

    /** 终态写入:带执行锁校验 —— 若看门狗已判定僵尸并重新入队,本次写入自动作废 */
    private void finish(TaskEntity task, String claimToken, TaskRuntime runtime, int status, String error) {
        String safeError = error == null ? "" : truncate(error);
        int progress = status == TaskStatus.SUCCESS || status == TaskStatus.PARTIAL ? 100 : runtime.progress();
        int updated = taskMapper.finishTask(task.getId(), claimToken, status, progress, safeError);
        if (updated == 0) {
            log.warn("[task] 终态写入被跳过(执行锁已失效,任务可能被看门狗接管) taskId={}", task.getId());
            return;
        }
        TaskEntity latest = taskMapper.selectById(task.getId());
        if (latest != null) {
            publisher.publishStatus(latest, status, safeError);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
