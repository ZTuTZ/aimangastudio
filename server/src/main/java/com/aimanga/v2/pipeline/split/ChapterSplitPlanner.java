package com.aimanga.v2.pipeline.split;

import com.aimanga.v2.ai.AiService;
import com.aimanga.v2.ai.PromptService;
import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.pipeline.PipelinePrompts;
import com.aimanga.v2.pipeline.text.SourceTextIndexer;
import com.aimanga.v2.pipeline.text.SourceUnit;
import com.aimanga.v2.service.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * 滚动小包分话规划器:
 * - 每包取不超过 split_pack_max_chars 的连续 Unit,交给 AI 只返回自然话边界(endUnit/title/summary);
 * - 非最后包的窗口尾部若剧情未自然完成,AI 可不返回该尾部边界,未确认 Unit 留到下一包;
 * - Java 按 offset 从原文切片得到 chapter.script_text,绝不让 AI 回传原文;
 * - 全部规划完成后必须通过连续覆盖硬校验(chapter 拼接 == source_text 逐字符一致)。
 */
@Slf4j
@Component
public class ChapterSplitPlanner {

    private final AiService aiService;
    private final PromptService promptService;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    public ChapterSplitPlanner(AiService aiService, PromptService promptService,
                               ConfigService configService, ObjectMapper objectMapper) {
        this.aiService = aiService;
        this.promptService = promptService;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    /** 漫画话目标长度(由 storyboard_page_count 推导:每页字数 × 页数) */
    public record ChapterLengthTarget(int targetChars, int minChars, int maxChars) {
    }

    public ChapterLengthTarget lengthTarget() {
        int pages = Math.max(1, configService.getInt("storyboard_page_count", 10));
        int perPage = configService.getInt("split_target_chars_per_page", 60);
        int minPerPage = configService.getInt("split_min_chars_per_page", 35);
        int maxPerPage = configService.getInt("split_max_chars_per_page", 90);
        return new ChapterLengthTarget(pages * perPage, pages * minPerPage, pages * maxPerPage);
    }

    /**
     * 滚动规划全部话边界(纯内存,不写库)。
     *
     * @param progressCallback 每确认一个包回调一次(0-99,用于任务进度)
     */
    public List<ChapterPlan> plan(String sourceText, List<SourceUnit> units, IntConsumer progressCallback) {
        int packMax = Math.max(1000, configService.getInt("split_pack_max_chars", 8000));
        ChapterLengthTarget target = lengthTarget();
        int unitCount = units.size();

        List<ChapterPlan> plans = new ArrayList<>();
        int cursor = 0;      // 下一个未确认 Unit 的下标(0-based)
        int chapterNo = 1;
        String previousContext = "";
        int packNo = 0;

        while (cursor < unitCount) {
            packNo++;
            // 1. 组包:从 cursor 起累计不超过 packMax 的 Unit
            int packEnd = cursor;
            int chars = 0;
            while (packEnd < unitCount) {
                int len = units.get(packEnd).text().length();
                if (chars > 0 && chars + len > packMax) {
                    break;
                }
                chars += len;
                packEnd++;
            }
            boolean lastPack = packEnd >= unitCount;

            // 2. AI 规划本包
            String numbered = SourceTextIndexer.toNumberedText(units, cursor, packEnd);
            String prompt = promptService.render("prompt_split", PipelinePrompts.DEFAULT_SPLIT, Map.of(
                    "target_chars", String.valueOf(target.targetChars()),
                    "min_chars", String.valueOf(target.minChars()),
                    "max_chars", String.valueOf(target.maxChars()),
                    "is_last_pack", lastPack ? "是" : "否",
                    "last_unit", String.format("U%04d", unitCount),
                    "previous_context", previousContext,
                    "text", numbered));
            List<ChapterBoundaryResult.Boundary> boundaries = planPack(prompt);

            // 3. 硬校验并确认:仅接受 (cursor, packEnd] 内严格递增的 endUnit
            List<Integer> confirmedEnds = new ArrayList<>();
            List<ChapterBoundaryResult.Boundary> confirmedBoundaries = new ArrayList<>();
            int last = cursor;
            for (ChapterBoundaryResult.Boundary b : boundaries) {
                if (b == null || b.endUnit() == null) continue;
                int endUnit = b.endUnit();
                if (endUnit <= last || endUnit > packEnd) {
                    log.warn("[split] 第 {} 包丢弃非法边界 endUnit={}(当前 cursor={},包尾={})", packNo, endUnit, cursor, packEnd);
                    continue;
                }
                confirmedEnds.add(endUnit);
                confirmedBoundaries.add(b);
                last = endUnit;
            }

            // 4. 防卡死:非最后包且无有效边界 → 在目标话长度附近找自然边界
            if (confirmedEnds.isEmpty() && !lastPack) {
                int fallback = fallbackBoundary(units, cursor, packEnd, target.targetChars());
                confirmedEnds.add(fallback);
                confirmedBoundaries.add(null);
                log.warn("[split] 第 {} 包 AI 未返回有效边界,fallback 到 U{}(段落/句末优先)", packNo, fallback);
            }

            // 5. 最后一包必须覆盖到最后一个 Unit:不足则 Java 补一个尾部边界
            if (lastPack && (confirmedEnds.isEmpty() || confirmedEnds.get(confirmedEnds.size() - 1) != unitCount)) {
                log.warn("[split] 最后一包 AI 未覆盖到末 Unit,Java 补齐尾部边界 U{}", unitCount);
                confirmedEnds.add(unitCount);
                confirmedBoundaries.add(null);
            }

            // 6. 确认边界 → 生成计划(scriptText 由 Java 按 offset 从原文截取)
            int start = cursor;
            for (int i = 0; i < confirmedEnds.size(); i++) {
                int endUnit = confirmedEnds.get(i);
                ChapterBoundaryResult.Boundary boundary = confirmedBoundaries.get(i);
                String title = boundary != null && boundary.title() != null && !boundary.title().isBlank()
                        ? boundary.title().trim() : "第" + chapterNo + "话";
                String summary = boundary == null || boundary.summary() == null ? "" : boundary.summary().trim();
                plans.add(new ChapterPlan(chapterNo, title, summary,
                        units.get(start).startOffset(), units.get(endUnit - 1).endOffset()));
                chapterNo++;
                start = endUnit;
            }

            // 7. 上一话上下文(供下一包滚动连续性)
            ChapterPlan lastConfirmed = plans.get(plans.size() - 1);
            previousContext = "上一话「" + lastConfirmed.title() + "」"
                    + (lastConfirmed.summary().isBlank() ? "" : " — " + lastConfirmed.summary());

            cursor = start;
            if (progressCallback != null && !lastPack) {
                progressCallback.accept((int) Math.min(99L, cursor * 100L / unitCount));
            }
        }

        validateCoverage(sourceText, plans);
        return plans;
    }

    /** fallback 边界:目标话长度附近,优先句末 */
    static int fallbackBoundary(List<SourceUnit> units, int cursor, int packEnd, int targetChars) {
        int accumulated = 0;
        int bestSentenceEnd = -1;
        for (int i = cursor; i < packEnd; i++) {
            accumulated += units.get(i).text().length();
            String tail = units.get(i).text().stripTrailing();
            boolean sentenceEnd = tail.endsWith("。") || tail.endsWith("！") || tail.endsWith("？")
                    || tail.endsWith("…") || tail.endsWith(".");
            if (accumulated >= targetChars) {
                return sentenceEnd ? i + 1 : (bestSentenceEnd > cursor ? bestSentenceEnd + 1 : i + 1);
            }
            if (sentenceEnd) bestSentenceEnd = i;
        }
        if (bestSentenceEnd > cursor) return bestSentenceEnd + 1;
        return Math.min(cursor + 1, packEnd);
    }

    /** 每包 AI 调用:最多重试 2 次(共 3 次尝试) */
    private List<ChapterBoundaryResult.Boundary> planPack(String prompt) {
        BusinessException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                ChapterBoundaryResult result = aiService.chatJson("text", prompt, ChapterBoundaryResult.class);
                if (result.chapters() != null) {
                    return result.chapters();
                }
                last = new BusinessException(502, "AI 返回 chapters 为空");
            } catch (BusinessException e) {
                last = e;
            }
        }
        throw last;
    }

    /** 覆盖硬校验:chapter 拼接必须与原文逐字符一致,不一致禁止写库 */
    public static void validateCoverage(String sourceText, List<ChapterPlan> plans) {
        if (plans.isEmpty()) {
            throw new BusinessException(502, "拆话规划为空");
        }
        if (plans.get(0).startOffset() != 0) {
            throw new BusinessException(502, "拆话覆盖校验失败:第一话未从原文开头开始");
        }
        for (int i = 0; i < plans.size() - 1; i++) {
            if (plans.get(i).endOffset() != plans.get(i + 1).startOffset()) {
                throw new BusinessException(502, "拆话覆盖校验失败:第" + plans.get(i).chapterNo()
                        + "话与第" + plans.get(i + 1).chapterNo() + "话之间存在缺口或重叠");
            }
        }
        if (plans.get(plans.size() - 1).endOffset() != sourceText.length()) {
            throw new BusinessException(502, "拆话覆盖校验失败:最后一话未覆盖到原文末尾");
        }
        StringBuilder rebuilt = new StringBuilder();
        for (ChapterPlan plan : plans) {
            rebuilt.append(sourceText, plan.startOffset(), plan.endOffset());
        }
        if (!rebuilt.toString().equals(sourceText)) {
            throw new BusinessException(502, "拆话覆盖校验失败:各话拼接结果与原文不一致");
        }
        log.info("[split] 覆盖校验通过:{} 话,拼接 == 原文({} 字) 逐字符一致", plans.size(), sourceText.length());
    }
}
