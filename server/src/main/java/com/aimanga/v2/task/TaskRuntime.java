package com.aimanga.v2.task;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.TaskMapper;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.Objects;

/**
 * 任务运行时:向处理器暴露进度汇报与停止感知。
 * 每次进度变化写库(计数/进度)并发布事件,保证任务页与 SSE 实时可见。
 */
@RequiredArgsConstructor
public class TaskRuntime {

    private final TaskMapper taskMapper;
    private final TaskEventPublisher publisher;
    private final TaskEntity task;

    private final AtomicInteger success = new AtomicInteger();
    private final AtomicInteger fail = new AtomicInteger();
    private volatile int total;
    private volatile int progress;
    private volatile boolean ownershipLost;

    /** 声明总步数(重置计数,用于重试) */
    public void begin(int total) {
        if (hasPlan()) {
            restore(Math.max(total, task.getTotalCount() == null ? 0 : task.getTotalCount()),
                    task.getSuccessCount() == null ? 0 : task.getSuccessCount(),
                    task.getFailCount() == null ? 0 : task.getFailCount());
            return;
        }
        this.total = total;
        success.set(0);
        fail.set(0);
        persist();
    }

    /** Resume keeps completed units and reconstructs cumulative progress from persisted plan counters. */
    public void restore(int total, int completed, int failed) {
        this.total = Math.max(0, total);
        success.set(Math.max(0, completed));
        fail.set(Math.max(0, failed));
        int done = success.get() + fail.get();
        this.progress = this.total == 0 ? 0 : (int) Math.min(99L, done * 100L / this.total);
        persist();
    }

    public int total() {
        return total;
    }

    public int successCount() {
        return success.get();
    }

    public int failCount() {
        return fail.get();
    }

    public int progress() {
        return progress;
    }

    public int planVersion() {
        return task.getPlanVersion() == null ? 0 : task.getPlanVersion();
    }

    public boolean hasPlan() {
        return task.getPlanInitializedAt() != null && planVersion() > 0;
    }

    /** 当前运行 Task 的 fencing 所有者，供其领取的 Stage Item 绑定。 */
    public TaskExecutionOwner owner() {
        return new TaskExecutionOwner(task.getId(), task.getClaimToken());
    }

    /** 心跳无法再确认租约时由 TaskRunner 标记；后续不再发起新的业务请求。 */
    public void markOwnershipLost() {
        ownershipLost = true;
    }

    public void stepSuccess() {
        success.incrementAndGet();
        recalcAndPersist();
    }

    public void stepFail(String reason) {
        fail.incrementAndGet();
        recalcAndPersist();
    }

    /** 手动推进度(页级并发等场景自行计算) */
    public void setProgress(int percent) {
        this.progress = Math.max(0, Math.min(99, percent));
        persist();
    }

    /**
     * 每步前调用:用户已请求停止(或任务已被置为停止)时抛出 TaskStopSignal 中断处理器。
     */
    public void checkStop() {
        if (ownershipLost) {
            throw new TaskStopSignal();
        }
        TaskEntity latest = taskMapper.selectById(task.getId());
        if (latest == null) {
            throw new TaskStopSignal();
        }
        if (!Objects.equals(task.getClaimToken(), latest.getClaimToken())) {
            throw new TaskStopSignal();
        }
        if (latest.getLeaseUntil() != null && latest.getLeaseUntil().isBefore(java.time.LocalDateTime.now())) {
            throw new TaskStopSignal();
        }
        int status = latest.getStatus() == null ? TaskStatus.PENDING : latest.getStatus();
        if (status == TaskStatus.STOPPING || status == TaskStatus.STOPPED) {
            throw new TaskStopSignal();
        }
    }

    /** 运行中的任务读到持久化暂停意图后停止领取新工作，由 Runner 等待在途请求收尾。 */
    public void checkPauseRequested() {
        if (taskMapper.isEffectivePauseRequested(task.getId(), task.getClaimToken())) {
            throw new TaskPauseSignal();
        }
    }

    private void recalcAndPersist() {
        if (total > 0) {
            int done = success.get() + fail.get();
            this.progress = (int) Math.min(99L, done * 100L / total);
        }
        persist();
    }

    private void persist() {
        int updated = taskMapper.updateProgress(task.getId(), task.getClaimToken(), total, success.get(), fail.get(),
                success.get() + fail.get(), progress);
        if (updated == 0) {
            throw new TaskStopSignal();
        }
        publisher.publishProgress(task, progress, success.get(), fail.get(), total);
    }
}
