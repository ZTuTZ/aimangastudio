package com.aimanga.v2.task;

/** 任务被用户停止时,处理器内部抛出以中断执行(由 TaskRunner 捕获并落终态 STOPPED) */
public class TaskStopSignal extends RuntimeException {

    public TaskStopSignal() {
        super("任务已停止");
    }
}
