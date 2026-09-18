package com.aimanga.v2.task;

/**
 * 任务状态机(与 task.status 列一致):
 * 0排队中 → 1进行中 → 2成功 | 3失败 | 4部分失败 | 5已停止;1 → 6停止中 → 5;
 * 1 → 7已暂停(Phase 8.3:暂停任务保留原 Task/payload,继续=同一 Task)。
 */
public final class TaskStatus {

    public static final int PENDING = 0;
    public static final int RUNNING = 1;
    public static final int SUCCESS = 2;
    public static final int FAILED = 3;
    public static final int PARTIAL = 4;
    public static final int STOPPED = 5;
    public static final int STOPPING = 6;
    public static final int PAUSED = 7;

    private TaskStatus() {
    }

    /** 终态:不会再变化 */
    public static boolean terminal(int status) {
        return status == SUCCESS || status == FAILED || status == PARTIAL || status == STOPPED;
    }

    /** 调度中:Worker/队列视角的活跃状态 */
    public static boolean active(int status) {
        return status == PENDING || status == RUNNING || status == STOPPING;
    }

    /** 执行中 */
    public static boolean executing(int status) {
        return status == RUNNING;
    }

    /** 可恢复(Phase 8.3:暂停任务) */
    public static boolean resumable(int status) {
        return status == PAUSED;
    }
}
