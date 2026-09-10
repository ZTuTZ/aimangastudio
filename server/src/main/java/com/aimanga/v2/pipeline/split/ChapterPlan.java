package com.aimanga.v2.pipeline.split;

/** 内存中的分话规划(写入 DB 前完成全部校验) */
public record ChapterPlan(
        int chapterNo,
        String title,
        String summary,
        int startOffset,
        int endOffset) {

    public int charCount() {
        return endOffset - startOffset;
    }
}
