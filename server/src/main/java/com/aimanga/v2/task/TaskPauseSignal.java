package com.aimanga.v2.task;

/** 阶段被用户暂停时抛出(Phase 8.3):TaskRunner 捕获后 Task → PAUSED,保留 payload/进度,原 Task 可继续 */
public class TaskPauseSignal extends RuntimeException {

    public TaskPauseSignal() {
        super("任务已暂停");
    }
}
