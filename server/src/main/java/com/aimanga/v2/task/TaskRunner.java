package com.aimanga.v2.task;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.TaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

    private final TaskMapper taskMapper;
    private final TaskEventPublisher publisher;
    private final Map<String, TaskHandler> handlers;
    private final com.aimanga.v2.service.ConfigService configService;
    private final TaskQueue taskQueue;
    private final RedisConcurrencyLimiter concurrencyLimiter;
    private final OperationalMetrics metrics;
    private final java.util.concurrent.ScheduledExecutorService heartbeatScheduler;

    /** 本 JVM 实例 ID(Phase 8.4 多实例标识) */
    private static final String INSTANCE_ID = java.util.UUID.randomUUID().toString();

    @org.springframework.beans.factory.annotation.Autowired
    public TaskRunner(TaskMapper taskMapper, TaskEventPublisher publisher, List<TaskHandler> handlerList,
                      com.aimanga.v2.service.ConfigService configService, TaskQueue taskQueue,
                      RedisConcurrencyLimiter concurrencyLimiter, OperationalMetrics metrics) {
        this.taskMapper = taskMapper;
        this.publisher = publisher;
        this.handlers = handlerList.stream().collect(Collectors.toMap(TaskHandler::type, Function.identity()));
        this.configService = configService;
        this.taskQueue = taskQueue;
        this.concurrencyLimiter = concurrencyLimiter;
        this.metrics = metrics;
        int heartbeatThreads = Math.max(1, Math.min(8, configService.getInt("task_heartbeat_threads", 2)));
        this.heartbeatScheduler = java.util.concurrent.Executors.newScheduledThreadPool(heartbeatThreads, r -> {
            Thread t = new Thread(r, "task-heartbeat");
            t.setDaemon(true);
            return t;
        });
        log.info("[task] 已注册任务处理器: {} (instance={})", handlers.keySet(), INSTANCE_ID);
    }

    TaskRunner(TaskMapper taskMapper, TaskEventPublisher publisher, List<TaskHandler> handlerList,
               com.aimanga.v2.service.ConfigService configService, TaskQueue taskQueue) {
        this(taskMapper, publisher, handlerList, configService, taskQueue,
                null, new OperationalMetrics());
    }

    public void run(long taskId) {
        run(taskId, null, null);
    }

    public void run(long taskId, Long permitUserId, String permitToken) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        // Phase 5.6 原子领取:生成执行锁,仅当仍为排队中时领取成功
        String claimToken = java.util.UUID.randomUUID().toString().replace("-", "");
        int leaseSeconds = configService.getInt("task_lease_seconds", 90);
        if (taskMapper.claim(taskId, claimToken, INSTANCE_ID, leaseSeconds) == 0) {
            return;
        }
        TaskEntity running = taskMapper.selectById(taskId);
        running.setStartTime(LocalDateTime.now());
        running.setClaimToken(claimToken);
        publisher.publishStatus(running, TaskStatus.RUNNING, "任务开始");
        TaskRuntime runtime = new TaskRuntime(taskMapper, publisher, running);

        // 心跳错误必须自行捕获；否则 ScheduledFuture 会永久取消，合法 STOPPING drain 会被误杀。
        long heartbeatIntervalSeconds = Math.max(1, Math.min(30,
                configService.getInt("task_heartbeat_interval_seconds", Math.max(1, leaseSeconds / 3))));
        AtomicLong localLeaseDeadline = new AtomicLong(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(leaseSeconds));
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
                    renewHeartbeat(taskId, claimToken, leaseSeconds, runtime, localLeaseDeadline,
                            permitUserId, permitToken);
                },
                heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS);

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
            if (latest.getStatus() != null && latest.getStatus() == TaskStatus.PENDING) {
                safeEnqueue(latest.getId());
                publisher.publishStatus(latest, TaskStatus.PENDING, "暂停意图已取消,继续原任务");
            } else {
                publisher.publishStatus(latest, TaskStatus.PAUSED, "已暂停(进行中的请求已保存)");
            }
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
            publisher.publishStatus(latest, latest.getStatus(), latest.getError());
        }
    }

    private void renewHeartbeat(long taskId, String claimToken, int leaseSeconds, TaskRuntime runtime,
                                AtomicLong localLeaseDeadline, Long permitUserId, String permitToken) {
        try {
            if (taskMapper.heartbeat(taskId, claimToken, leaseSeconds) == 1) {
                if (concurrencyLimiter != null && permitUserId != null && permitToken != null
                        && !concurrencyLimiter.renewUser(permitUserId, permitToken)) {
                    runtime.markOwnershipLost();
                    metrics.permitRenewFailure();
                    metrics.ownershipLost();
                    if (taskMapper.requeueAfterPermitLoss(taskId, claimToken, "用户并发许可续租失败") == 1) {
                        safeEnqueue(taskId);
                    }
                    log.warn("[task] 用户并发许可续租失败,停止继续执行 taskId={}", taskId);
                    return;
                }
                localLeaseDeadline.set(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(leaseSeconds));
                return;
            }
            runtime.markOwnershipLost();
            metrics.ownershipLost();
            log.warn("[task] 心跳续租失败(执行锁已失效) taskId={}", taskId);
        } catch (RuntimeException e) {
            // 定时任务不能让异常逃出，否则后续心跳会被调度器取消。
            log.warn("[task] 心跳写库异常 taskId={}: {}", taskId, e.getMessage());
            metrics.heartbeatFailure();
            if (System.currentTimeMillis() >= localLeaseDeadline.get()) {
                runtime.markOwnershipLost();
                metrics.ownershipLost();
                log.warn("[task] 本地租约到期且心跳持续失败，停止继续执行 taskId={}", taskId);
            }
        }
    }

    @PreDestroy
    void shutdownHeartbeatScheduler() {
        heartbeatScheduler.shutdownNow();
    }

    private void safeEnqueue(Long taskId) {
        try {
            taskQueue.enqueue(taskId);
        } catch (RuntimeException e) {
            log.warn("[task] Redis 入队失败，任务保留 PENDING 等待数据库补偿 taskId={}", taskId, e);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
