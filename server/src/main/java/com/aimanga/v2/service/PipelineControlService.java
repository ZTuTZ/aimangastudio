package com.aimanga.v2.service;

import com.aimanga.v2.pipeline.PipelineStageService;
import com.aimanga.v2.repository.TaskMapper;
import com.aimanga.v2.task.TaskQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

/** 持久化流水线控制意图；队列写入只在数据库事务提交后发生。 */
@Service
@RequiredArgsConstructor
public class PipelineControlService {

    private final PipelineStageService stageService;
    private final TaskMapper taskMapper;
    private final TaskQueue taskQueue;

    @Transactional
    public int pauseProject(Long projectId) {
        stageService.pauseProject(projectId);
        return taskMapper.pausePendingForProject(projectId);
    }

    @Transactional
    public int resumeProject(Long projectId) {
        stageService.resumeProject(projectId);
        List<Long> resumed = new ArrayList<>();
        for (Long taskId : taskMapper.selectProjectPausedTaskIds(projectId)) {
            if (taskMapper.resumeProjectPausedTask(taskId) == 1) {
                resumed.add(taskId);
            }
        }
        afterCommit(() -> resumed.forEach(taskQueue::enqueue));
        return resumed.size();
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
