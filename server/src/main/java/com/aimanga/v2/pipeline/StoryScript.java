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
            String narration,
            List<DialogueItem> dialogue,
            String visual) {
    }

    public record DialogueItem(String speaker, String line) {
    }
}
