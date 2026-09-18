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
 * 重启恢复(Phase 8.4 §6.7 重写,多实例安全):
 * - PENDING  → enqueueIfAbsent(补入队;marker 去重);
 * - RUNNING  → 不修改!等多实例 lease/看门狗接管(本实例启动不得重置其他实例正在执行的任务);
 * - STOPPING → 不转 PENDING(用户停止意图不可丢失;Worker 失联由看门狗判定 → STOPPED);
 * - PAUSED   → 不动(用户不点继续,永不自动恢复)。
 * 原先"启动把 RUNNING/STOPPING 全改 PENDING"的单实例思路已废弃。
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
        // 信号量重置(临时保留;Phase 8.6 Limiter 上线后移除 —— 多实例不能清他实例限制状态)
        semaphores.resetAll();
        // 3. 仅补齐 PENDING 的入队(marker 去重;RUNNING/STOPPING/PAUSED 一律不动)
        List<TaskEntity> pending = taskMapper.selectList(new LambdaQueryWrapper<TaskEntity>()
                .eq(TaskEntity::getStatus, TaskStatus.PENDING)
                .orderByAsc(TaskEntity::getId));
        int enqueued = 0;
        for (TaskEntity task : pending) {
            if (taskQueue.enqueueIfAbsent(task.getId())) {
                enqueued++;
            }
        }
        log.info("[recovery] 启动恢复: PENDING 补入队 {}/{},RUNNING/STOPPING/PAUSED 交由 lease/watchdog 管理",
                enqueued, pending.size());
    }
}
