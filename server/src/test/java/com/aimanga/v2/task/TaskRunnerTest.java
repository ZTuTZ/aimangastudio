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
        org.mockito.Mockito.when(handler.type()).thenReturn("MOCK");
        runner = new TaskRunner(mapper, publisher, List.of(handler));
        task = new TaskEntity();
        task.setId(1L);
        task.setUserId(9L);
        task.setTaskType("MOCK");
        task.setStatus(TaskStatus.PENDING);
        when(mapper.claim(1L)).thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(task);
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

        ArgumentCaptor<TaskEntity> captor = ArgumentCaptor.forClass(TaskEntity.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        TaskEntity last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(last.getProgress()).isEqualTo(100);
        assertThat(last.getEndTime()).isNotNull();
    }

    @Test
    void businessFailure_marksFailedWithError() throws Exception {
        doAnswer(inv -> { throw new BusinessException(502, "模型未返回图片"); })
                .when(handler).run(any(), any());

        runner.run(1L);

        ArgumentCaptor<TaskEntity> captor = ArgumentCaptor.forClass(TaskEntity.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        TaskEntity last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(last.getError()).isEqualTo("模型未返回图片");
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
            rt.checkStop(); // 此时 DB 状态已是 STOPPING → 抛停止信号
            return null;
        }).when(handler).run(any(), any());

        runner.run(1L);

        ArgumentCaptor<TaskEntity> captor = ArgumentCaptor.forClass(TaskEntity.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        TaskEntity last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(TaskStatus.STOPPED);
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

        ArgumentCaptor<TaskEntity> captor = ArgumentCaptor.forClass(TaskEntity.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        TaskEntity last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(TaskStatus.PARTIAL);
        assertThat(last.getProgress()).isEqualTo(100);
    }

    @Test
    void claimFailure_skipsHandler() throws Exception {
        when(mapper.claim(1L)).thenReturn(0);
        runner.run(1L);
        verify(handler, never()).run(any(), any());
    }

    @Test
    void unknownType_marksFailed() throws Exception {
        task.setTaskType("NOT_EXIST");
        runner.run(1L);
        ArgumentCaptor<TaskEntity> captor = ArgumentCaptor.forClass(TaskEntity.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        TaskEntity last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(last.getError()).contains("暂未实现");
    }
}
