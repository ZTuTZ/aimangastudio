package com.aimanga.v2.pipeline.split;

import java.util.List;

/** SPLIT 边界契约:AI 只返回 endUnit/title/summary,绝不返回 scriptText/完整原文 */
public record ChapterBoundaryResult(List<Boundary> chapters) {

    public record Boundary(
            Integer endUnit,
            String title,
            String summary) {
    }
}
