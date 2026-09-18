package com.aimanga.v2.pipeline;

import java.util.Collection;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Stage Run 执行范围(Phase 8.2):
 * 区分「项目的 Stage Item 注册表」与「某一个 Task 本次允许执行的 Item」。
 *
 * - businessIds=null:无范围限制(项目级全量跑,如 feature_auto_sheet 链式任务/整部 BATCH);
 * - businessIds 非空:Runner 的 claimNext 只允许领取该集合内的 Item ——
 *   单话/多话/单页任务即使项目里还有其他 PENDING Item 也不会顺手执行。
 */
public record StageRunScope(String businessType, Set<Long> businessIds) {

    /** 项目级全量(不限制 businessId) */
    public static StageRunScope all() {
        return new StageRunScope(null, null);
    }

    /** 页级范围(LAYOUT/IMAGE/后处理) */
    public static StageRunScope pages(Collection<Long> pageIds) {
        if (pageIds == null) {
            return all();
        }
        return new StageRunScope("PAGE", new LinkedHashSet<>(pageIds));
    }

    public static StageRunScope page(Long pageId) {
        return pages(List.of(pageId));
    }

    /** 资产范围(SHEET/ASSET_REF 手动勾选) */
    public static StageRunScope assets(Collection<Long> assetIds) {
        return new StageRunScope("ASSET", new LinkedHashSet<>(assetIds));
    }

    /** 章节范围(SCRIPT:businessId=chapterId) */
    public static StageRunScope chapters(Collection<Long> chapterIds) {
        return new StageRunScope("CHAPTER", new LinkedHashSet<>(chapterIds));
    }

    public boolean isUnbounded() {
        return businessIds == null;
    }
}
