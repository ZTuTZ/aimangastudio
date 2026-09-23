package com.aimanga.v2.service;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.ProjectMapper;
import com.aimanga.v2.repository.TaskMapper;
import com.aimanga.v2.task.TaskEventPublisher;
import com.aimanga.v2.task.TaskQueue;
import com.aimanga.v2.task.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskAdmissionServiceTest {

    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final TaskPlanningService planningService = mock(TaskPlanningService.class);
    private final TaskQueue taskQueue = mock(TaskQueue.class);
    private final TaskEventPublisher publisher = mock(TaskEventPublisher.class);
    private final ConfigService configService = mock(ConfigService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private TaskAdmissionService service;

    @BeforeEach
    void setUp() {
        service = new TaskAdmissionService(projectMapper, taskMapper, planningService,
                taskQueue, publisher, configService, objectMapper);
        when(configService.getInt("task_max_execution_seconds", 3600)).thenReturn(3600);
        doAnswer(invocation -> {
            invocation.<TaskEntity>getArgument(0).setId(101L);
            return 1;
        }).when(taskMapper).insert(any(TaskEntity.class));
    }

    @Test
    void planningFailureNeverQueuesOrPublishesTheHalfCreatedTask() {
        Project project = project(7L, false);
        when(projectMapper.lockByIds(List.of(7L))).thenReturn(List.of(project));
        doThrow(new IllegalStateException("plan failed")).when(planningService).initializePlan(any());

        assertThatThrownBy(() -> service.admit(command("{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("plan failed");

        verify(taskMapper).insert(any(TaskEntity.class));
        verify(taskQueue, never()).enqueue(anyLong());
        verify(publisher, never()).publishCreated(any());
    }

    @Test
    void samePayloadReusesExistingTaskAndDifferentPayloadIsRejected() {
        Project project = project(7L, false);
        TaskEntity active = new TaskEntity();
        active.setId(55L);
        active.setPayload("{}");
        when(projectMapper.lockByIds(List.of(7L))).thenReturn(List.of(project));
        when(taskMapper.selectOne(any())).thenReturn(active);

        TaskAdmissionService.AdmissionResult reused = service.admit(command("{}"));
        assertThat(reused.created()).isFalse();
        assertThat(reused.task()).isSameAs(active);

        assertThatThrownBy(() -> service.admit(command("{\"force\":true}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("范围重叠");
        verify(taskMapper, never()).insert(any(TaskEntity.class));
    }

    @Test
    void pausedProjectCreatesAPlannedButNotQueuedTask() {
        Project project = project(7L, true);
        when(projectMapper.lockByIds(List.of(7L))).thenReturn(List.of(project));

        TaskAdmissionService.AdmissionResult result = service.admit(command("{}"));

        assertThat(result.created()).isTrue();
        assertThat(result.task().getStatus()).isEqualTo(TaskStatus.PAUSED);
        verify(planningService).initializePlan(result.task());
        verify(taskQueue, never()).enqueue(anyLong());
        verify(publisher).publishCreated(result.task());
    }

    @Test
    void exportLocksAnchorAndEveryTargetInAscendingOrder() {
        Project p3 = project(3L, false);
        Project p7 = project(7L, false);
        Project p9 = project(9L, false);
        when(projectMapper.lockByIds(any())).thenReturn(List.of(p3, p7, p9));

        service.admit(new TaskAdmissionService.AdmissionCommand(
                1L, 7L, null, "EXPORT", "{\"projectIds\":[9,3,7,3]}"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(projectMapper).lockByIds(ids.capture());
        assertThat(ids.getValue()).containsExactly(3L, 7L, 9L);
    }

    private TaskAdmissionService.AdmissionCommand command(String payload) {
        return new TaskAdmissionService.AdmissionCommand(1L, 7L, null, "SCRIPT", payload);
    }

    private static Project project(long id, boolean paused) {
        Project project = new Project();
        project.setId(id);
        project.setUserId(1L);
        project.setPauseRequested(paused);
        return project;
    }
}
