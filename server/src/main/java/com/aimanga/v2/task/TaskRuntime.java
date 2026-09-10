package com.aimanga.v2.task;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.TaskMapper;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.atomic.AtomicInteger;

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

    /** 声明总步数(重置计数,用于重试) */
    public void begin(int total) {
        this.total = total;
        success.set(0);
        fail.set(0);
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
        TaskEntity latest = taskMapper.selectById(task.getId());
        if (latest == null) {
            throw new TaskStopSignal();
        }
        int status = latest.getStatus() == null ? TaskStatus.PENDING : latest.getStatus();
        if (status == TaskStatus.STOPPING || status == TaskStatus.STOPPED) {
            throw new TaskStopSignal();
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
        TaskEntity patch = new TaskEntity();
        patch.setId(task.getId());
        patch.setTotalCount(total);
        patch.setSuccessCount(success.get());
        patch.setFailCount(fail.get());
        patch.setProcessedCount(success.get() + fail.get());
        patch.setProgress(progress);
        taskMapper.updateById(patch);
        publisher.publishProgress(task, progress, success.get(), fail.get(), total);
    }
}
