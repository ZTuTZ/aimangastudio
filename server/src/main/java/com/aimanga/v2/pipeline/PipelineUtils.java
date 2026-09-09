package com.aimanga.v2.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 流水线工具:契约校验辅助与文本规范化。
 */
public final class PipelineUtils {

    /** SPLIT 元数据的建议分类(category 不在范围内时归为「其他」) */
    public static final Set<String> CATEGORIES = Set.of(
            "古风", "都市", "恋爱", "悬疑", "科幻", "奇幻", "热血", "搞笑", "治愈", "校园");

    private static final String PUNCTUATION = "。，、！？；：…—·“”‘’「」『』（）()《》<>!?,.;:~～\\s";

    private PipelineUtils() {
    }

    /** category 规范化:空/未知 → 其他 */
    public static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return "其他";
        }
        String trimmed = category.trim();
        for (String allowed : CATEGORIES) {
            if (allowed.equals(trimmed)) {
                return trimmed;
            }
        }
        return "其他";
    }

    /**
     * 去标点(旁白/对白契约:无标点,空格断句)。
     * 模型偶发带标点,此处自动清洗,保证产出符合“无标点”契约而不是反复失败。
     */
    public static String stripPunctuation(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (PUNCTUATION.indexOf(c) >= 0) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().replaceAll(" {2,}", " ").trim();
    }

    /** 页脚本合成展示文本:旁白 + 对白 + 画面(与旧版格式一致) */
    public static String composeSceneDescription(String narration, List<StoryScript.DialogueItem> dialogue, String visual) {
        List<String> parts = new ArrayList<>();
        if (narration != null && !narration.isBlank()) {
            parts.add("旁白：" + narration.trim());
        }
        if (dialogue != null && !dialogue.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (StoryScript.DialogueItem d : dialogue) {
                String speaker = d.speaker() == null ? "" : d.speaker();
                String line = d.line() == null ? "" : d.line();
                lines.add(speaker + "：" + line);
            }
            parts.add("对白：\n" + String.join("\n", lines));
        }
        if (visual != null && !visual.isBlank()) {
            parts.add("画面：" + visual.trim());
        }
        return String.join("\n\n", parts);
    }

    /** 角色资产描述合成 */
    public static String buildCharacterDescription(StoryScript.CharacterItem c) {
        List<String> parts = new ArrayList<>();
        if (notBlank(c.age())) {
            // AI 常返回 "24岁"/"24" 两种形态,避免出现 "24岁岁"
            parts.add(c.age().trim().endsWith("岁") ? c.age().trim() : c.age().trim() + "岁");
        }
        if (notBlank(c.hair())) parts.add(c.hair());
        if (notBlank(c.accessories())) parts.add(c.accessories());
        if (notBlank(c.top()) || notBlank(c.bottom())) {
            parts.add((safe(c.top()) + (notBlank(c.top()) && notBlank(c.bottom()) ? "+" : "") + safe(c.bottom())));
        }
        if (notBlank(c.description())) parts.add(c.description());
        return String.join(",", parts);
    }

    /** 从对白行文本("角色：台词")解析为 JSON 字符串 */
    public static List<StoryScript.DialogueItem> parseDialogueLines(String text) {
        List<StoryScript.DialogueItem> items = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return items;
        }
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int idx = trimmed.indexOf('：');
            if (idx < 0) idx = trimmed.indexOf(':');
            if (idx > 0) {
                items.add(new StoryScript.DialogueItem(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim()));
            } else {
                items.add(new StoryScript.DialogueItem("", trimmed));
            }
        }
        return items;
    }

    public static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public static String safe(String s) {
        return s == null ? "" : s;
    }
}
