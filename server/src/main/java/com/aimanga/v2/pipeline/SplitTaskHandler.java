package com.aimanga.v2.pipeline;

import com.aimanga.v2.ai.AiService;
import com.aimanga.v2.ai.PromptService;
import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.event.ProjectCreatedEvent;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.pipeline.split.ChapterPlan;
import com.aimanga.v2.pipeline.split.ChapterRebuildService;
import com.aimanga.v2.pipeline.split.ChapterSplitPlanner;
import com.aimanga.v2.pipeline.split.ProjectMetadataResult;
import com.aimanga.v2.pipeline.text.SourceTextIndexer;
import com.aimanga.v2.pipeline.text.SourceUnit;
import com.aimanga.v2.task.TaskHandler;
import com.aimanga.v2.task.TaskRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * SPLIT 拆话任务(Phase 5.5 滚动小包版):
 * 1. SourceTextIndexer 本地索引原文(80~500 字/unit,offset 连续);
 * 2. ChapterSplitPlanner 滚动小包(≤ split_pack_max_chars)让 AI 只返回话边界(endUnit/title/summary);
 * 3. Java 按 offset 从原文切片得到 script_text,覆盖校验(拼接 == 原文 逐字符一致)后才写库;
 * 4. 元数据 = 拆话完成后的独立小请求(仅标题+分话规划+首尾摘录),失败不影响已拆好的话;
 * 5. content_uid 永不触碰;链式入队 ASSET(feature_auto_asset)或直接 SCRIPT×N。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SplitTaskHandler implements TaskHandler {

    public static final String TYPE = "SPLIT";

    private final PipelineContext ctx;
    private final ChapterSplitPlanner planner;
    private final ChapterRebuildService chapterRebuildService;
    private final AiService aiService;
    private final PromptService promptService;
    private final ObjectMapper objectMapper;
    private final com.aimanga.v2.service.ConfigService configService;

    @Override
    public String type() {
        return TYPE;
    }

    /** 新作品创建/导入后自动入队拆话(feature_auto_split) */
    @EventListener
    public void onProjectCreated(ProjectCreatedEvent event) {
        if (configService.getInt("feature_auto_split", 1) == 1) {
            ctx.enqueueUnique(event.project().getId(), null, TYPE, "{}");
        }
    }

    @Override
    public void run(TaskEntity task, TaskRuntime runtime) {
        Project project = ctx.project(task.getProjectId());
        boolean refreshMetadata = isRefreshMetadata(task.getPayload());

        // 防覆盖:任一话已有页,拒绝重新拆话
        List<Chapter> existing = ctx.chaptersOf(project.getId());
        for (Chapter chapter : existing) {
            if (ctx.pageCountOf(chapter.getId()) > 0) {
                throw new BusinessException(409, "作品已存在生成内容(第" + chapter.getChapterNo()
                        + "话已有页面),不能重新拆话;如需重跑请逐话使用「重新生成脚本」");
            }
        }

        String sourceText = safeSource(project);
        if (sourceText.isBlank()) {
            throw new BusinessException(400, "作品没有故事原文,无法拆话");
        }
        List<SourceUnit> units = SourceTextIndexer.index(sourceText);
        log.info("[split] 作品 {} 原文 {} 字 → {} 个 SourceUnit", project.getId(), sourceText.length(), units.size());

        runtime.begin(3);

        // 1. 滚动小包规划(AI 只回边界,Java 按 offset 切片)
        List<ChapterPlan> plans = planner.plan(sourceText, units, runtime::setProgress);
        runtime.stepSuccess();

        // 2. 覆盖校验通过后一次性重建话(事务)
        List<Chapter> chapters = chapterRebuildService.rebuild(project.getId(), sourceText, plans);
        runtime.stepSuccess();

        // 3. 元数据(独立小请求,失败仅告警,不影响拆话结果)
        String warning = applyMetadata(project, sourceText, plans, refreshMetadata);
        runtime.stepSuccess();
        if (warning != null) {
            TaskEntity patch = new TaskEntity();
            patch.setId(task.getId());
            try {
                patch.setResult(objectMapper.writeValueAsString(Map.of("metadataWarning", warning)));
            } catch (Exception ignored) {
                // JSON 序列化失败则不记录
            }
            ctx.taskMapper.updateById(patch);
            log.warn("[split] 作品 {} 元数据生成失败(不影响拆话): {}", project.getId(), warning);
        }

        // 4. 链式入队:SPLIT → ASSET(feature_auto_asset)或直接 SCRIPT×N
        if (ctx.feature("feature_auto_asset")) {
            ctx.enqueueUnique(project.getId(), null, AssetTaskHandler.TYPE, "{}");
        } else {
            for (Chapter chapter : chapters) {
                ctx.enqueueUnique(project.getId(), chapter.getId(), ScriptTaskHandler.TYPE, "{}");
            }
        }
        log.info("[split] 作品 {} 拆话完成: {} 话", project.getId(), chapters.size());
    }

    /** 元数据独立小请求;失败返回告警文本(不抛异常,保证拆话结果不丢) */
    private String applyMetadata(Project project, String sourceText, List<ChapterPlan> plans, boolean refresh) {
        boolean needsFill = refresh
                || blank(project.getTagline()) || blank(project.getDescription())
                || blank(project.getCategory()) || blankTags(project.getTags());
        if (!needsFill) {
            return null;
        }
        ProjectMetadataResult metadata;
        try {
            StringBuilder chaptersSummary = new StringBuilder();
            for (ChapterPlan plan : plans) {
                chaptersSummary.append("第").append(plan.chapterNo()).append("话 ")
                        .append(plan.title())
                        .append(plan.summary().isBlank() ? "" : " — " + plan.summary())
                        .append('\n');
            }
            String head = sourceText.substring(0, Math.min(300, sourceText.length()));
            String tail = sourceText.substring(Math.max(0, sourceText.length() - 300));
            String prompt = promptService.render("prompt_metadata", PipelinePrompts.DEFAULT_METADATA, Map.of(
                    "title", safe(project.getTitle()),
                    "chapters", chaptersSummary.toString(),
                    "head", head,
                    "tail", tail));
            metadata = aiService.chatJson("text", prompt, ProjectMetadataResult.class);
        } catch (Exception e) {
            return "元数据生成失败(可稍后人工补填或重刷): " + e.getMessage();
        }
        try {
            Project patch = new Project();
            patch.setId(project.getId());
            if (refresh || blank(project.getTagline())) {
                patch.setTagline(clip(metadata.tagline(), 64));
            }
            if (refresh || blank(project.getDescription())) {
                patch.setDescription(metadata.description());
            }
            if (refresh || blank(project.getCategory())) {
                patch.setCategory(PipelineUtils.normalizeCategory(metadata.category()));
            }
            if (refresh || blankTags(project.getTags())) {
                patch.setTags(objectMapper.writeValueAsString(metadata.tags() == null ? List.of() : metadata.tags()));
            }
            if (metadata.seriesStatus() != null
                    && (metadata.seriesStatus() == Project.SERIES_ONGOING || metadata.seriesStatus() == Project.SERIES_COMPLETED)) {
                if (refresh || metadata.seriesStatus() == Project.SERIES_ONGOING) {
                    patch.setSeriesStatus(metadata.seriesStatus());
                }
            }
            patch.setUpdateTime(LocalDateTime.now());
            ctx.projectMapper.updateById(patch);
        } catch (Exception e) {
            return "元数据写库失败: " + e.getMessage();
        }
        return null;
    }

    private boolean isRefreshMetadata(String payload) {
        if (payload == null || payload.isBlank()) {
            return false;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(payload);
            return node.path("refreshMetadata").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static String safeSource(Project project) {
        return project.getSourceText() == null ? "" : project.getSourceText();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean blankTags(String s) {
        return blank(s) || "[]".equals(s.trim());
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
