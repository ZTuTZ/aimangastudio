package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.PipelineStageItem;
import com.aimanga.v2.repository.PipelineStageItemMapper;
import com.aimanga.v2.repository.TaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Fenced Commit 服务(Phase 8.1 §3.7/§3.8):
 * AI → OSS 完成后,在一个短事务内完成:
 *
 *   行锁锁定 Stage Item(FOR UPDATE)
 *   → 校验 status=RUNNING 且 attempt_token=当前 Attempt
 *   → 不满足:返回 committed=false(旧 Attempt 的业务结果不允许写库)
 *   → 满足:执行业务表写入(同一事务) + Stage Item → SUCCESS
 *
 * 语义:at-least-once 执行 + fenced commit = effectively-once 可见结果。
 * AI/OSS 不进入事务;旧 Attempt 已产生的 OSS orphan 暂时允许。
 *
 * stale_commit_rejected 计数可通过 staleRejectedCount() 观察(Phase 8.10 指标)。
 */
@Slf4j
@Service
public class StageItemCommitService {

    private final PipelineStageItemMapper itemMapper;
    private final TaskMapper taskMapper;
    private final AtomicLong staleRejected = new AtomicLong();

    public StageItemCommitService(PipelineStageItemMapper itemMapper, TaskMapper taskMapper) {
        this.itemMapper = itemMapper;
        this.taskMapper = taskMapper;
    }

    /** 供监控读取(Phase 8.10) */
    public long staleRejectedCount() {
        return staleRejected.get();
    }

    /** 提交结果:committed=false 表示当前 Attempt 已失效,业务写入被拒绝 */
    public record CommitResult(boolean committed, String resultRef) {}

    /**
     * Fenced 提交:businessWrites 在同一事务内执行(业务表写入 + 生成记录),
     * 返回业务侧组装的 resultRef。若 Attempt 已失效则完全不执行业务写入。
     */
    @Transactional
    public CommitResult commitFenced(StageItemExecution execution, Supplier<String> businessWrites) {
        if (!ownsCurrentTask(execution)) {
            return new CommitResult(false, null);
        }
        PipelineStageItem locked = itemMapper.lockById(execution.item().getId());
        if (!ownsCurrentItem(execution, locked)) {
            return new CommitResult(false, null);
        }
        // 注意:Supplier 只允许执行一次(业务写入有副作用,双写即事故)
        String resultRef = businessWrites.get();
        if (resultRef == null) {
            resultRef = "{}";
        }
        locked.setStatus(PipelineStageItem.STATUS_SUCCESS);
        locked.setResultRef(resultRef);
        locked.setFinishTime(java.time.LocalDateTime.now());
        locked.setAttemptToken(null);
        locked.setOwnerTaskId(null);
        locked.setOwnerTaskClaimToken(null);
        locked.setUpdateTime(java.time.LocalDateTime.now());
        itemMapper.updateById(locked);
        return new CommitResult(true, resultRef);
    }

    /** 对 RUNNING/FAILED 等临时业务状态也执行双层 fencing，但不改变 Item 终态。 */
    @Transactional
    public boolean runWhileOwned(StageItemExecution execution, Runnable businessWrites) {
        if (!ownsCurrentTask(execution)) {
            return false;
        }
        PipelineStageItem locked = itemMapper.lockById(execution.item().getId());
        if (!ownsCurrentItem(execution, locked)) {
            return false;
        }
        businessWrites.run();
        return true;
    }

    private boolean ownsCurrentTask(StageItemExecution execution) {
        if (execution.taskOwner() == null
                || taskMapper.lockActiveClaim(execution.taskOwner().taskId(), execution.taskOwner().claimToken()) == null) {
            reject(execution, "Task 已被接管或不再可提交");
            return false;
        }
        return true;
    }

    private boolean ownsCurrentItem(StageItemExecution execution, PipelineStageItem locked) {
        if (locked == null
                || locked.getStatus() == null
                || locked.getStatus() != PipelineStageItem.STATUS_RUNNING
                || !java.util.Objects.equals(locked.getAttemptToken(), execution.attemptToken())
                || !java.util.Objects.equals(locked.getOwnerTaskId(), execution.taskOwner().taskId())
                || !java.util.Objects.equals(locked.getOwnerTaskClaimToken(), execution.taskOwner().claimToken())) {
            reject(execution, "Stage Item 已被更新的 Attempt 接管");
            return false;
        }
        return true;
    }

    private CommitResult reject(StageItemExecution execution, String reason) {
        staleRejected.incrementAndGet();
        log.warn("[fence] stale_commit_rejected item={} attempt={}: {}",
                execution.item().getId(), execution.attemptToken(), reason);
        return new CommitResult(false, null);
    }
}
