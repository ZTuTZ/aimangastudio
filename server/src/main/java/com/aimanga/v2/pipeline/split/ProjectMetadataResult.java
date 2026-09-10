package com.aimanga.v2.pipeline.split;

import java.util.List;

/** 作品元数据契约(拆话完成后的独立小请求,替代旧 SPLIT 大请求中的 metadata) */
public record ProjectMetadataResult(
        String tagline,
        String description,
        String category,
        List<String> tags,
        Integer seriesStatus) {
}
