package com.aimanga.v2.pipeline;

/** 旧 Attempt 的提交被 fencing 拒绝(Item 已被更新的 Attempt 接管)。Runner 捕获后不计失败、不重试。 */
public class StaleCommitRejectedException extends RuntimeException {

    private final Long stageItemId;

    public StaleCommitRejectedException(Long stageItemId) {
        super("stale_commit_rejected: item " + stageItemId + " 已被更新的 Attempt 接管");
        this.stageItemId = stageItemId;
    }

    public Long getStageItemId() {
        return stageItemId;
    }
}
