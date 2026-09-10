package com.aimanga.v2.pipeline.text;

/**
 * 原文索引单元:source_text 的纯视图(不改写、不清洗)。
 * 所有 unit 满足:first.startOffset==0;unit[i].endOffset==unit[i+1].startOffset;last.endOffset==sourceText.length()。
 */
public record SourceUnit(
        int index,       // 1-based
        int startOffset, // inclusive
        int endOffset,   // exclusive
        String text) {
}
