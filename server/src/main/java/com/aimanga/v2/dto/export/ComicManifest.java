package com.aimanga.v2.dto.export;

import java.util.List;

/**
 * comic-content-1.0 内容导出协议(Phase 7 实现,此处先定义契约)。
 * 是未来漫画 APP 导入的唯一兼容边界:不含 user_id/生产 status/task/提示词/失败日志等生产字段。
 * content_uid 必须来自 project.content_uid;chapters 按 chapter_no ASC,pages 按 page_no ASC。
 */
public record ComicManifest(
        String schemaVersion,
        String contentUid,
        String title,
        String tagline,
        String description,
        String coverUrl,
        String category,
        List<String> tags,
        Integer seriesStatus,
        String aspectRatio,
        String colorMode,
        Boolean complete,
        List<ComicManifestChapter> chapters) {

    public static final String SCHEMA_VERSION = "comic-content-1.0";
}
