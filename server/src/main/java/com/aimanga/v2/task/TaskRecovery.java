package com.aimanga.v2.task;

import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 重启恢复:应用就绪后,把所有未完成任务(排队中/进行中/停止中)重置为排队中并重新入队。
 * 页级幂等与断点 current_no 保证不重复生成已完成内容。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRecovery implements ApplicationRunner {

    private final TaskMapper taskMapper;
    private final TaskQueue taskQueue;
    private final com.aimanga.v2.task.RedisSemaphores semaphores;

    @Override
    public void run(ApplicationArguments args) {
        // 1. 清除上次停机残留的毒丸(否则 Worker 被毒死,任务永远排队)
        taskQueue.purgePoison();
        // 2. 重置并发信号量(启动瞬间无在跑任务,停机泄漏的许可此时清零最安全)
        semaphores.resetAll();
        // 3. 未完成任务重新入队
        List<TaskEntity> active = taskMapper.selectList(new LambdaQueryWrapper<TaskEntity>()
                .in(TaskEntity::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.STOPPING)
                .orderByAsc(TaskEntity::getId));
        for (TaskEntity task : active) {
            TaskEntity patch = new TaskEntity();
            patch.setId(task.getId());
            patch.setStatus(TaskStatus.PENDING);
            patch.setError("");
            taskMapper.updateById(patch);
            taskQueue.enqueue(task.getId());
        }
        if (!active.isEmpty()) {
            log.info("[task] 重启恢复:已重新入队 {} 个未完成任务", active.size());
        }
    }
}
