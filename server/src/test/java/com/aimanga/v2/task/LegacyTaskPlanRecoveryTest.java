package com.aimanga.v2.task;

import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.TaskMapper;
import com.aimanga.v2.service.TaskPlanningService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class LegacyTaskPlanRecoveryTest {

    @Test
    void initializesAndEnqueuesLegacyPendingTaskBeforeWorkersCanClaimIt() {
        TaskMapper taskMapper = mock(TaskMapper.class);
        TaskPlanningService planningService = mock(TaskPlanningService.class);
        TaskQueue queue = mock(TaskQueue.class);
        TaskEntity task = task(41L, TaskStatus.PENDING);
        when(taskMapper.selectUnplannedPendingOrPaused()).thenReturn(List.of(task));

        new LegacyTaskPlanRecovery(taskMapper, planningService, queue).recoverUnplannedTasks();

        verify(planningService).initializePlan(task);
        verify(queue).enqueueIfAbsent(41L);
        verify(taskMapper, never()).failUnplanned(anyLong(), anyString());
    }

    @Test
    void keepsLegacyPausedTaskPausedAfterPlanning() {
        TaskMapper taskMapper = mock(TaskMapper.class);
        TaskPlanningService planningService = mock(TaskPlanningService.class);
        TaskQueue queue = mock(TaskQueue.class);
        TaskEntity task = task(42L, TaskStatus.PAUSED);
        when(taskMapper.selectUnplannedPendingOrPaused()).thenReturn(List.of(task));

        new LegacyTaskPlanRecovery(taskMapper, planningService, queue).recoverUnplannedTasks();

        verify(planningService).initializePlan(task);
        verifyNoInteractions(queue);
    }

    @Test
    void marksAmbiguousLegacyTaskForManualHandling() {
        TaskMapper taskMapper = mock(TaskMapper.class);
        TaskPlanningService planningService = mock(TaskPlanningService.class);
        TaskQueue queue = mock(TaskQueue.class);
        TaskEntity task = task(43L, TaskStatus.PENDING);
        when(taskMapper.selectUnplannedPendingOrPaused()).thenReturn(List.of(task));
        doThrow(new IllegalStateException("范围无法恢复")).when(planningService).initializePlan(task);

        new LegacyTaskPlanRecovery(taskMapper, planningService, queue).recoverUnplannedTasks();

        verify(taskMapper).failUnplanned(eq(43L), contains("范围无法恢复"));
        verifyNoInteractions(queue);
    }

    @Test
    void redisFailureAfterPlanningDoesNotFailTheMigratedTask() {
        TaskMapper taskMapper = mock(TaskMapper.class);
        TaskPlanningService planningService = mock(TaskPlanningService.class);
        TaskQueue queue = mock(TaskQueue.class);
        TaskEntity task = task(44L, TaskStatus.PENDING);
        when(taskMapper.selectUnplannedPendingOrPaused()).thenReturn(List.of(task));
        when(queue.enqueueIfAbsent(44L)).thenThrow(new IllegalStateException("redis down"));

        new LegacyTaskPlanRecovery(taskMapper, planningService, queue).recoverUnplannedTasks();

        verify(planningService).initializePlan(task);
        verify(taskMapper, never()).failUnplanned(anyLong(), anyString());
    }

    private static TaskEntity task(long id, int status) {
        TaskEntity task = new TaskEntity();
        task.setId(id);
        task.setStatus(status);
        task.setTaskType("BATCH");
        return task;
    }
}
