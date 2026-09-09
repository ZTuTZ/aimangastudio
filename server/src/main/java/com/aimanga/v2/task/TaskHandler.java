package com.aimanga.v2.task;

import com.aimanga.v2.model.TaskEntity;

/**
 * 任务处理器接口:每类任务一个实现(Spring Bean),TaskRunner 按类型分发。
 * 实现约定:
 * - 长任务在每一步前调用 runtime.checkStop(),感知用户停止;
 * - 通过 runtime.begin(total)/stepSuccess()/stepFail() 汇报进度(写库 + 推送事件);
 * - 抛出任意异常 = 任务失败(保留已成功计数,终态 FAILED);部分成功由 failCount>0 判定 PARTIAL。
 */
public interface TaskHandler {

    /** 与 task.task_type 对应,如 SPLIT/SCRIPT/BATCH/MOCK */
    String type();

    void run(TaskEntity task, TaskRuntime runtime) throws Exception;
}
