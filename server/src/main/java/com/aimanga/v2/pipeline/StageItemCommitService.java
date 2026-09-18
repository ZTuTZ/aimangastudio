package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.PipelineStageItem;
import com.aimanga.v2.repository.PipelineStageItemMapper;
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
@RequiredArgsConstructor
public class StageItemCommitService {

    private final PipelineStageItemMapper itemMapper;
    private final AtomicLong staleRejected = new AtomicLong();

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
        PipelineStageItem locked = itemMapper.lockById(execution.item().getId());
        if (locked == null
                || locked.getStatus() == null
                || locked.getStatus() != PipelineStageItem.STATUS_RUNNING
                || !java.util.Objects.equals(locked.getAttemptToken(), execution.attemptToken())) {
            staleRejected.incrementAndGet();
            log.warn("[fence] stale_commit_rejected item={} attempt={} (已被更新的 Attempt 接管)",
                    execution.item().getId(), execution.attemptToken());
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
        locked.setUpdateTime(java.time.LocalDateTime.now());
        itemMapper.updateById(locked);
        return new CommitResult(true, resultRef);
    }
}
