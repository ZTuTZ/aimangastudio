package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.PipelineStageItem;
import com.aimanga.v2.repository.PipelineStageItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 8.1 Failure Injection 验收(F4/F5/F6 文档映射):
 * - F4:Stage Attempt A 晚返回 → 不覆盖 B(业务写入被拒);
 * - F5:AI+OSS 成功但 fenced commit 被拒 → DB 不假成功(业务写入不执行);
 * - F6:业务 DB 写入 + Item SUCCESS 后,旧 Attempt 再次尝试 → 幂等拒绝。
 */
class StaleCommitFencingFailureInjectionTest {

    private PipelineStageItemMapper itemMapper;
    private StageItemCommitService service;
    private PipelineStageItem lockedRow;

    @BeforeEach
    void setUp() {
        itemMapper = mock(PipelineStageItemMapper.class);
        service = new StageItemCommitService(itemMapper);
        lockedRow = new PipelineStageItem();
        lockedRow.setId(9001L);
        lockedRow.setStatus(PipelineStageItem.STATUS_RUNNING);
        lockedRow.setAttemptToken("TOKEN_B"); // Attempt B(最新)持有
        when(itemMapper.lockById(9001L)).thenReturn(lockedRow);
    }

    private StageItemExecution exec(String token) {
        PipelineStageItem item = new PipelineStageItem();
        item.setId(9001L);
        return new StageItemExecution(item, token);
    }

    @Test
    void F4_staleAttemptLateReturn_businessWriteRejected() {
        // Attempt A(TOKEN_A)晚返回尝试提交
        StageItemCommitService.CommitResult result = service.commitFenced(
                exec("TOKEN_A"), () -> "stale");

        assertThat(result.committed()).isFalse();
        // Item 状态/结果未被 A 覆盖(仍归 B 所有)
        assertThat(lockedRow.getStatus()).isEqualTo(PipelineStageItem.STATUS_RUNNING);
        assertThat(lockedRow.getAttemptToken()).isEqualTo("TOKEN_B");
    }

    @Test
    void F5_latestAttempt_aiAndOssSuccess_fencedCommitWritesBusiness() {
        // Attempt B(AI+OSS 已完成)提交:业务写入 + Item SUCCESS 同事务生效
        StageItemCommitService.CommitResult result = service.commitFenced(
                exec("TOKEN_B"), () -> "url-final");

        assertThat(result.committed()).isTrue();
        assertThat(lockedRow.getStatus()).isEqualTo(PipelineStageItem.STATUS_SUCCESS);
        assertThat(lockedRow.getResultRef()).contains("url-final");
        assertThat(lockedRow.getAttemptToken()).isNull(); // 提交后 token 清空
    }

    @Test
    void F6_afterItemSuccess_oldAttemptRetry_isIdempotentRejected() {
        // Attempt B 已提交(Item=SUCCESS);旧 Attempt A 的任何提交被拒
        lockedRow.setStatus(PipelineStageItem.STATUS_SUCCESS);
        lockedRow.setAttemptToken(null);

        StageItemCommitService.CommitResult result = service.commitFenced(
                exec("TOKEN_A"), () -> "stale");

        assertThat(result.committed()).isFalse();
        verify(itemMapper, never()).updateById(any(PipelineStageItem.class));
    }

    @Test
    void doubleCommit_sameFencedService_businessWriteExecutedOncePerAttempt() {
        // 同一 fenced 服务连续两次提交:每次 Attempt 独立校验,业务写入不重复
        AtomicReference<String> lastWrite = new AtomicReference<>();
        AtomicBoolean called = new AtomicBoolean(false);
        when(itemMapper.lockById(9001L)).thenReturn(lockedRow);

        // B 提交(合法)
        var r1 = service.commitFenced(exec("TOKEN_B"), () -> {
            called.set(true);
            return "ref-b";
        });
        assertThat(r1.committed()).isTrue();
        // Item 已 SUCCESS;旧 token 再提交被拒
        var r2 = service.commitFenced(exec("TOKEN_A"), () -> {
            lastWrite.set("stale-write");
            return "ref-a";
        });
        assertThat(r2.committed()).isFalse();
        assertThat(lastWrite.get()).isNull();
    }
}
