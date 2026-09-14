package com.aimanga.v2.pipeline;

import java.util.List;

/**
 * 六段式脚本契约(SCRIPT 任务产物,与旧版教程规范对齐)。
 * 校验规则见 ScriptTaskHandler:非空/无空页/speaker 必须在 characters 内。
 */
public record StoryScript(
        String summary,
        String objective,
        String requirements,
        List<CharacterItem> characters,
        List<PageItem> pages,
        String tagline) {

    public record CharacterItem(
            String role,
            String name,
            String age,
            String hair,
            String accessories,
            String top,
            String bottom,
            String description) {
    }

    public record PageItem(
            Integer page,
            Integer sourceStartUnit,
            Integer sourceEndUnit,
            String narration,
            List<DialogueItem> dialogue,
            String visual,
            /** 本页引用的资产 ID(T6.1.3:AI 返回,非法 ID 由程序过滤,Java 匹配器再补充) */
            List<Long> assetIds) {
    }

    public record DialogueItem(String speaker, String line) {
    }
}
