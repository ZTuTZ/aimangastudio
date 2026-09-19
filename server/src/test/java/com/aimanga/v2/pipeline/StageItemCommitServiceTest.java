package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.PipelineStageItem;
import com.aimanga.v2.repository.PipelineStageItemMapper;
import com.aimanga.v2.repository.TaskMapper;
import com.aimanga.v2.task.TaskExecutionOwner;
import com.aimanga.v2.model.TaskEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 8.1 Attempt Fencing 单测:
 * 旧 Attempt 晚返回时业务写入被拒绝;最新 Attempt 正常提交。
 */
class StageItemCommitServiceTest {

    private PipelineStageItemMapper itemMapper;
    private TaskMapper taskMapper;
    private StageItemCommitService service;
    private PipelineStageItem locked;

    @BeforeEach
    void setUp() {
        itemMapper = mock(PipelineStageItemMapper.class);
        taskMapper = mock(TaskMapper.class);
        service = new StageItemCommitService(itemMapper, taskMapper);
        locked = new PipelineStageItem();
        locked.setId(9001L);
        locked.setStatus(PipelineStageItem.STATUS_RUNNING);
        locked.setAttemptToken("TOKEN_B"); // 最新 Attempt B 持有
        locked.setOwnerTaskId(77L);
        locked.setOwnerTaskClaimToken("TASK_CLAIM_B");
        when(itemMapper.lockById(9001L)).thenReturn(locked);
        TaskEntity owner = new TaskEntity();
        owner.setId(77L);
        owner.setClaimToken("TASK_CLAIM_B");
        when(taskMapper.lockActiveClaim(77L, "TASK_CLAIM_B")).thenReturn(owner);
    }

    private StageItemExecution exec(String token) {
        PipelineStageItem item = new PipelineStageItem();
        item.setId(9001L);
        return new StageItemExecution(item, token, new TaskExecutionOwner(77L, "TASK_CLAIM_B"));
    }

    @Test
    void staleAttempt_businessWritesRejected() {
        // Attempt A(旧 token)晚返回:业务写入不得执行
        AtomicInteger businessWrites = new AtomicInteger();
        StageItemCommitService.CommitResult result = service.commitFenced(
                exec("TOKEN_A"), () -> {
                    businessWrites.incrementAndGet();
                    return "{\"should\":\"not-write\"}";
                });

        assertThat(result.committed()).isFalse();
        assertThat(businessWrites.get()).isZero();
        assertThat(service.staleRejectedCount()).isEqualTo(1);
        // Item 状态不被改动(仍归 Attempt B 所有)
        assertThat(locked.getStatus()).isEqualTo(PipelineStageItem.STATUS_RUNNING);
        verify(itemMapper, never()).updateById(org.mockito.ArgumentMatchers.any(PipelineStageItem.class));
    }

    @Test
    void latestAttempt_businessWritesAndItemSuccess() {
        AtomicInteger businessWrites = new AtomicInteger();
        StageItemCommitService.CommitResult result = service.commitFenced(
                exec("TOKEN_B"), () -> {
                    businessWrites.incrementAndGet();
                    return "{\"pageId\":300}";
                });

        assertThat(result.committed()).isTrue();
        assertThat(result.resultRef()).isEqualTo("{\"pageId\":300}");
        assertThat(businessWrites.get()).isEqualTo(1);
        // Item 在同一事务内被置 SUCCESS 并清空 token
        assertThat(locked.getStatus()).isEqualTo(PipelineStageItem.STATUS_SUCCESS);
        assertThat(locked.getAttemptToken()).isNull();
        assertThat(locked.getFinishTime()).isNotNull();
    }

    @Test
    void staleAttempt_nullTokenItem_alsoRejected() {
        locked.setAttemptToken(null); // 已被 resetRunningItems 回收
        StageItemCommitService.CommitResult result = service.commitFenced(
                exec("TOKEN_A"), () -> "should-not-run");
        assertThat(result.committed()).isFalse();
        assertThat(service.staleRejectedCount()).isEqualTo(1);
    }

    @Test
    void revokedTaskClaim_rejectsOtherwiseCurrentStageAttempt() {
        // Stage Item 的 attempt 仍有效，但 Task lease 已被看门狗接管：不能写正式业务结果。
        when(taskMapper.lockActiveClaim(77L, "TASK_CLAIM_B")).thenReturn(null);
        AtomicInteger businessWrites = new AtomicInteger();

        StageItemCommitService.CommitResult result = service.commitFenced(exec("TOKEN_B"), () -> {
            businessWrites.incrementAndGet();
            return "must-not-write";
        });

        assertThat(result.committed()).isFalse();
        assertThat(businessWrites.get()).isZero();
        assertThat(locked.getStatus()).isEqualTo(PipelineStageItem.STATUS_RUNNING);
        verify(itemMapper, never()).updateById(any(PipelineStageItem.class));
    }

    @Test
    void revokedTaskClaim_alsoRejectsTemporaryBusinessStatusWrite() {
        when(taskMapper.lockActiveClaim(77L, "TASK_CLAIM_B")).thenReturn(null);
        AtomicInteger businessWrites = new AtomicInteger();

        boolean written = service.runWhileOwned(exec("TOKEN_B"), businessWrites::incrementAndGet);

        assertThat(written).isFalse();
        assertThat(businessWrites.get()).isZero();
    }
}
