package com.aimanga.v2.pipeline;

/**
 * Stage Item 执行上下文(Phase 8.1):Item + 本次 Attempt 的 fencing token。
 * 所有 processor 必须通过 StageItemCommitService 使用该上下文提交正式业务结果。
 */
public record StageItemExecution(
        com.aimanga.v2.model.PipelineStageItem item,
        String attemptToken
) {
}
