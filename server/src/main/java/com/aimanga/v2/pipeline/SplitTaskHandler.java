package com.aimanga.v2.pipeline;

import com.aimanga.v2.ai.AiService;
import com.aimanga.v2.ai.PromptService;
import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.event.ProjectCreatedEvent;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.task.TaskHandler;
import com.aimanga.v2.task.TaskRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * SPLIT 拆话任务:
 * 1. AI 拆话(严格按原文顺序,2-8 话)并同时生成作品元数据(tagline/description/category/tags/seriesStatus);
 * 2. 元数据仅回填空字段(payload.refreshMetadata=true 时强制覆盖);content_uid 永不触碰;
 * 3. 重建话记录(要求作品尚无任何页,防止覆盖已生成内容);
 * 4. 链式入队:每话一个 SCRIPT 任务(feature_auto_split 关闭时仍可手动触发本任务)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SplitTaskHandler implements TaskHandler {

    public static final String TYPE = "SPLIT";
    private static final int MAX_SOURCE_CHARS = 30000;

    private final PipelineContext ctx;
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
            ctx.enqueue(event.project().getId(), null, TYPE, "{}");
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

        String text = safeSource(project);
        if (text.isBlank()) {
            throw new BusinessException(400, "作品没有故事原文,无法拆话");
        }

        runtime.begin(2);

        String prompt = promptService.render("prompt_split", PipelinePrompts.DEFAULT_SPLIT,
                Map.of("text", text.length() > MAX_SOURCE_CHARS ? text.substring(0, MAX_SOURCE_CHARS) : text));
        ChapterSplitResult result = executeWithRetry(prompt);
        runtime.stepSuccess();

        // 重建话
        for (Chapter chapter : existing) {
            ctx.chapterMapper.deleteById(chapter.getId());
        }
        List<ChapterSplitResult.ChapterItem> items = result.chapters().stream()
                .sorted(Comparator.comparingInt(c -> c.chapterNo() == null ? 999 : c.chapterNo()))
                .toList();
        for (int i = 0; i < items.size(); i++) {
            ChapterSplitResult.ChapterItem item = items.get(i);
            Chapter chapter = new Chapter();
            chapter.setProjectId(project.getId());
            chapter.setChapterNo(item.chapterNo() == null ? i + 1 : item.chapterNo());
            chapter.setTitle(item.title() == null || item.title().isBlank() ? "第" + (i + 1) + "话" : item.title().trim());
            chapter.setScriptText(item.scriptText());
            chapter.setStatus(com.aimanga.v2.model.Chapter.STATUS_PENDING);
            chapter.setPageCount(0);
            chapter.setCreateTime(LocalDateTime.now());
            ctx.chapterMapper.insert(chapter);
            // 链式入队:每话一个 SCRIPT 任务(并行度受任务系统四层并发控制)
            ctx.enqueue(project.getId(), chapter.getId(), "SCRIPT", "{}");
        }
        applyMetadata(project, result.metadata(), refreshMetadata);
        runtime.stepSuccess();
        log.info("[split] 作品 {} 拆话完成: {} 话,元数据已回填(仅空字段={})", project.getId(), items.size(), !refreshMetadata);
    }

    /** AI 拆话 + 契约校验,失败自动重试 1 次 */
    private ChapterSplitResult executeWithRetry(String prompt) {
        BusinessException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            ChapterSplitResult result;
            try {
                result = aiService.chatJson("text", prompt, ChapterSplitResult.class);
            } catch (BusinessException e) {
                last = e;
                continue;
            }
            List<String> problems = validate(result);
            if (problems.isEmpty()) {
                return result;
            }
            last = new BusinessException(502, "拆话结果不符合契约: " + String.join("; ", problems));
        }
        throw last;
    }

    private List<String> validate(ChapterSplitResult result) {
        List<String> problems = new java.util.ArrayList<>();
        if (result.chapters() == null || result.chapters().isEmpty()) {
            problems.add("chapters 为空");
        } else {
            for (ChapterSplitResult.ChapterItem item : result.chapters()) {
                if (item.scriptText() == null || item.scriptText().isBlank()) {
                    problems.add("第" + item.chapterNo() + "话 scriptText 为空");
                }
            }
        }
        return problems;
    }

    /** 元数据回填:refreshMetadata=true 强制覆盖;否则仅填空字段 */
    private void applyMetadata(Project project, ChapterSplitResult.Metadata metadata, boolean refresh) {
        if (metadata == null) {
            return;
        }
        Project patch = new Project();
        patch.setId(project.getId());
        boolean changed = false;
        if (refresh || blank(project.getTagline()) && notBlank(metadata.tagline())) {
            patch.setTagline(clip(metadata.tagline(), 64));
            changed = true;
        }
        if (refresh || blank(project.getDescription()) && notBlank(metadata.description())) {
            patch.setDescription(metadata.description());
            changed = true;
        }
        if (refresh || blank(project.getCategory()) && notBlank(metadata.category())) {
            patch.setCategory(PipelineUtils.normalizeCategory(metadata.category()));
            changed = true;
        }
        if (metadata.tags() != null && !metadata.tags().isEmpty()
                && (refresh || blankTags(project.getTags()))) {
            try {
                patch.setTags(objectMapper.writeValueAsString(metadata.tags()));
                changed = true;
            } catch (Exception ignored) {
                // 序列化失败则跳过 tags
            }
        }
        Integer series = metadata.seriesStatus();
        if (series != null && (series == Project.SERIES_ONGOING || series == Project.SERIES_COMPLETED)) {
            if (refresh || (series == Project.SERIES_ONGOING && project.getSeriesStatus() == Project.SERIES_COMPLETED)) {
                patch.setSeriesStatus(series);
                changed = true;
            }
        }
        if (changed) {
            patch.setUpdateTime(LocalDateTime.now());
            ctx.projectMapper.updateById(patch);
        }
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

    private String safeSource(Project project) {
        return ProjectSource.safe(project);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean blankTags(String s) {
        return blank(s) || "[]".equals(s.trim());
    }

    private static boolean notBlank(String s) {
        return !blank(s);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 作品原文来源(供 Split/Asset 共用) */
    public static final class ProjectSource {
        public static String safe(Project project) {
            return project.getSourceText() == null ? "" : project.getSourceText();
        }
    }
}
