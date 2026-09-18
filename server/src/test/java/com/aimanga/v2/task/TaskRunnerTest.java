package com.aimanga.v2.task;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRunnerTest {

    private TaskMapper mapper;
    private TaskEventPublisher publisher;
    private TaskHandler handler;
    private TaskRunner runner;
    private TaskEntity task;

    @BeforeEach
    void setUp() {
        mapper = mock(TaskMapper.class);
        publisher = mock(TaskEventPublisher.class);
        handler = mock(TaskHandler.class);
        when(handler.type()).thenReturn("MOCK");
        com.aimanga.v2.service.ConfigService configService = mock(com.aimanga.v2.service.ConfigService.class);
        when(configService.getInt(org.mockito.ArgumentMatchers.eq("task_lease_seconds"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(90);
        runner = new TaskRunner(mapper, publisher, List.of(handler), configService);
        task = new TaskEntity();
        task.setId(1L);
        task.setUserId(9L);
        task.setTaskType("MOCK");
        task.setStatus(TaskStatus.PENDING);
        when(mapper.claim(org.mockito.ArgumentMatchers.eq(1L), anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(task);
        when(mapper.updateProgress(eq(1L), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(1);
    }

    @Test
    void successFlow_writesTerminalState() throws Exception {
        doAnswer(inv -> {
            TaskRuntime rt = inv.getArgument(1);
            rt.begin(2);
            rt.stepSuccess();
            rt.stepSuccess();
            return null;
        }).when(handler).run(any(), any());

        runner.run(1L);

        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mapper, atLeastOnce()).finishTask(eq(1L), anyString(), statusCaptor.capture(), anyInt(), any());
        assertThat(statusCaptor.getValue()).isEqualTo(TaskStatus.SUCCESS);
    }

    @Test
    void businessFailure_marksFailedWithError() throws Exception {
        doAnswer(inv -> { throw new BusinessException(502, "模型未返回图片"); })
                .when(handler).run(any(), any());

        runner.run(1L);

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mapper, atLeastOnce()).finishTask(eq(1L), anyString(), statusCaptor.capture(), anyInt(), errorCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(TaskStatus.FAILED);
        assertThat(errorCaptor.getValue()).isEqualTo("模型未返回图片");
    }

    @Test
    void stopSignal_marksStopped() throws Exception {
        TaskEntity stopping = new TaskEntity();
        stopping.setId(1L);
        stopping.setStatus(TaskStatus.STOPPING);
        when(mapper.selectById(1L)).thenReturn(task, task, stopping);
        doAnswer(inv -> {
            TaskRuntime rt = inv.getArgument(1);
            rt.begin(5);
            rt.stepSuccess();
            rt.checkStop();
            return null;
        }).when(handler).run(any(), any());

        runner.run(1L);

        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mapper, atLeastOnce()).finishTask(eq(1L), anyString(), statusCaptor.capture(), anyInt(), any());
        assertThat(statusCaptor.getValue()).isEqualTo(TaskStatus.STOPPED);
    }

    @Test
    void mixedSteps_marksPartial() throws Exception {
        doAnswer(inv -> {
            TaskRuntime rt = inv.getArgument(1);
            rt.begin(2);
            rt.stepSuccess();
            rt.stepFail("boom");
            return null;
        }).when(handler).run(any(), any());

        runner.run(1L);

        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> progressCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mapper, atLeastOnce()).finishTask(eq(1L), anyString(), statusCaptor.capture(), progressCaptor.capture(), any());
        assertThat(statusCaptor.getValue()).isEqualTo(TaskStatus.PARTIAL);
        assertThat(progressCaptor.getValue()).isEqualTo(100);
    }

    @Test
    void claimFailure_skipsHandler() throws Exception {
        when(mapper.claim(org.mockito.ArgumentMatchers.eq(1L), anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(0);
        runner.run(1L);
        verify(handler, never()).run(any(), any());
    }

    @Test
    void unknownType_marksFailed() {
        task.setTaskType("NOT_EXIST");
        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        runner.run(1L);
        verify(mapper, atLeastOnce()).finishTask(eq(1L), anyString(), statusCaptor.capture(), anyInt(), errorCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(TaskStatus.FAILED);
        assertThat(errorCaptor.getValue()).contains("暂未实现");
    }

    @Test
    void progressWritesAreFencedByTheClaimToken() throws Exception {
        doAnswer(inv -> {
            TaskRuntime rt = inv.getArgument(1);
            rt.begin(1);
            return null;
        }).when(handler).run(any(), any());

        runner.run(1L);

        verify(mapper, never()).updateById(any(TaskEntity.class));
        verify(mapper, atLeastOnce()).updateProgress(eq(1L), anyString(), eq(1), eq(0), eq(0), eq(0), eq(0));
    }
}
