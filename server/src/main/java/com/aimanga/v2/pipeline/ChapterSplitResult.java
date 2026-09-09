package com.aimanga.v2.pipeline;

import java.util.List;

/**
 * 拆话契约(SPLIT 任务产物):一次 AI 调用同时产出作品元数据与分话结果。
 * metadata 仅回填 project 的空字段(除非 payload.refreshMetadata=true)。
 */
public record ChapterSplitResult(
        Metadata metadata,
        List<ChapterItem> chapters) {

    public record Metadata(
            String tagline,
            String description,
            String category,
            List<String> tags,
            Integer seriesStatus) {
    }

    public record ChapterItem(
            Integer chapterNo,
            String title,
            String scriptText) {
    }
}
