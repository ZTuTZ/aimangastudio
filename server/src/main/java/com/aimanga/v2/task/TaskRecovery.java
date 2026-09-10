package com.aimanga.v2.task;

import com.aimanga.v2.model.PipelineStage;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.pipeline.PipelineStageService;
import com.aimanga.v2.repository.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 重启恢复(Phase 5.7 增强):
 * 1. 清除毒丸残留 + 重置信号量;
 * 2. 查询 Pipeline Stage 确定恢复点 —— 已 SUCCESS 的阶段跳过;
 * 3. 未完成任务重新入队。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRecovery implements ApplicationRunner {

    private final TaskMapper taskMapper;
    private final TaskQueue taskQueue;
    private final com.aimanga.v2.task.RedisSemaphores semaphores;
    private final PipelineStageService stageService;

    @Override
    public void run(ApplicationArguments args) {
        // 1. 清除毒丸
        taskQueue.purgePoison();
        // 2. 重置信号量
        semaphores.resetAll();
        // 3. 未完成任务重新入队(保留 pipeline stage,Handler 内部根据已有数据跳过已完成步骤)
        List<TaskEntity> active = taskMapper.selectList(new LambdaQueryWrapper<TaskEntity>()
                .in(TaskEntity::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.STOPPING)
                .orderByAsc(TaskEntity::getId));
        for (TaskEntity task : active) {
            taskMapper.update(null, new LambdaUpdateWrapper<TaskEntity>()
                    .eq(TaskEntity::getId, task.getId())
                    .set(TaskEntity::getStatus, TaskStatus.PENDING)
                    .set(TaskEntity::getError, "")
                    .set(TaskEntity::getClaimToken, null)
                    .set(TaskEntity::getHeartbeatTime, null));
            taskQueue.enqueue(task.getId());
        }
        if (!active.isEmpty()) {
            log.info("[task] 重启恢复:已重新入队 {} 个未完成任务(已完成 Pipeline Stage 不会被重跑)", active.size());
        }
    }
}
