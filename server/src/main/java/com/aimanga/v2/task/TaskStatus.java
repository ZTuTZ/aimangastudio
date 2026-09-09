package com.aimanga.v2.task;

/**
 * 任务状态机(与 task.status 列一致):
 * 0排队中 → 1进行中 → 2成功 | 3失败 | 4部分失败 | 5已停止;1 → 6停止中 → 5。
 */
public final class TaskStatus {

    public static final int PENDING = 0;
    public static final int RUNNING = 1;
    public static final int SUCCESS = 2;
    public static final int FAILED = 3;
    public static final int PARTIAL = 4;
    public static final int STOPPED = 5;
    public static final int STOPPING = 6;

    private TaskStatus() {
    }

    public static boolean terminal(int status) {
        return status == SUCCESS || status == FAILED || status == PARTIAL || status == STOPPED;
    }

    public static boolean active(int status) {
        return status == PENDING || status == RUNNING || status == STOPPING;
    }
}
