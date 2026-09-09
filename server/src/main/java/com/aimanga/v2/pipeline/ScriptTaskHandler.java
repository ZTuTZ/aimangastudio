package com.aimanga.v2.pipeline;

import com.aimanga.v2.ai.AiService;
import com.aimanga.v2.ai.PromptService;
import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.Asset;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.pipeline.StoryScript.CharacterItem;
import com.aimanga.v2.pipeline.StoryScript.DialogueItem;
import com.aimanga.v2.task.TaskHandler;
import com.aimanga.v2.task.TaskRuntime;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SCRIPT 六段式脚本任务(每话一个):
 * AI 生成 → 契约校验(非空/无空页/speaker 必须在角色集内,失败自动重试 1 次) → 重写该话全部页
 * (旁白/对白去标点规范化,合成 scene_description) → 角色 upsert 到资产库 → 话状态=脚本就绪
 * → 全部话就绪时按 feature_auto_asset 入队 ASSET。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScriptTaskHandler implements TaskHandler {

    public static final String TYPE = "SCRIPT";

    private final PipelineContext ctx;
    private final AiService aiService;
    private final PromptService promptService;
    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(TaskEntity task, TaskRuntime runtime) {
        if (task.getChapterId() == null) {
            throw new BusinessException(400, "SCRIPT 任务缺少 chapterId");
        }
        Chapter chapter = ctx.chapterMapper.selectById(task.getChapterId());
        if (chapter == null) {
            throw new BusinessException(404, "话不存在: " + task.getChapterId());
        }
        Project project = ctx.project(chapter.getProjectId());
        String text = ctx.chapterText(project, chapter);
        if (text.isBlank()) {
            throw new BusinessException(400, "本话没有故事原文,无法生成脚本");
        }
        int pageCount = ctx.configService.getInt("storyboard_page_count", 10);
        String style = ctx.stylePromptOf(project);

        runtime.begin(3);
        String prompt = promptService.render("prompt_script", PipelinePrompts.DEFAULT_STORYBOARD, Map.of(
                "text", text,
                "page_count", String.valueOf(pageCount),
                "style", style));
        StoryScript script = executeWithRetry(prompt);
        runtime.stepSuccess();

        // 写页(重建该话全部页)
        List<PageEntity> existing = ctx.pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                .eq(PageEntity::getChapterId, chapter.getId()));
        for (PageEntity page : existing) {
            ctx.pageMapper.deleteById(page.getId());
        }
        List<StoryScript.PageItem> pages = script.pages();
        for (int i = 0; i < pages.size(); i++) {
            StoryScript.PageItem item = pages.get(i);
            PageEntity page = new PageEntity();
            page.setProjectId(project.getId());
            page.setChapterId(chapter.getId());
            page.setPageNo(i + 1);
            page.setNarration(PipelineUtils.stripPunctuation(item.narration()));
            String dialogueJson;
            try {
                List<DialogueItem> normalized = new ArrayList<>();
                if (item.dialogue() != null) {
                    for (DialogueItem d : item.dialogue()) {
                        normalized.add(new DialogueItem(PipelineUtils.safe(d.speaker()).trim(),
                                PipelineUtils.stripPunctuation(d.line())));
                    }
                }
                dialogueJson = objectMapper.writeValueAsString(normalized);
            } catch (Exception e) {
                dialogueJson = "[]";
            }
            page.setDialogue(dialogueJson);
            page.setVisual(item.visual());
            page.setSceneDescription(PipelineUtils.composeSceneDescription(
                    PipelineUtils.stripPunctuation(item.narration()), item.dialogue(), item.visual()));
            page.setGenerateStatus(PageEntity.GEN_PENDING);
            page.setGenerateRecords("[]");
            page.setCreateTime(LocalDateTime.now());
            ctx.pageMapper.insert(page);
        }
        runtime.stepSuccess();

        // 角色 upsert 到资产库
        upsertCharacters(project.getId(), script);
        runtime.stepSuccess();

        // 话状态 → 脚本就绪
        Chapter chapterPatch = new Chapter();
        chapterPatch.setId(chapter.getId());
        chapterPatch.setStatus(Chapter.STATUS_SCRIPT_READY);
        chapterPatch.setPageCount(pages.size());
        chapterPatch.setUpdateTime(LocalDateTime.now());
        ctx.chapterMapper.updateById(chapterPatch);

        // 全部话就绪 → 链式入队 ASSET(整本一次)
        boolean allReady = ctx.chaptersOf(project.getId()).stream()
                .allMatch(c -> c.getStatus() != null && c.getStatus() >= Chapter.STATUS_SCRIPT_READY);
        if (allReady && ctx.feature("feature_auto_asset")) {
            ctx.enqueue(project.getId(), null, AssetTaskHandler.TYPE, "{}");
        }
        log.info("[script] 话 {} 脚本完成: {} 页,角色 {} 个", chapter.getId(), pages.size(),
                script.characters() == null ? 0 : script.characters().size());
    }

    /** AI 生成 + 契约校验(非空/无空页),失败自动重试 1 次 */
    private StoryScript executeWithRetry(String prompt) {
        BusinessException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            StoryScript script;
            try {
                script = aiService.chatJson("text", prompt, StoryScript.class);
            } catch (BusinessException e) {
                last = e;
                continue;
            }
            List<String> problems = validate(script);
            if (problems.isEmpty()) {
                return normalize(script);
            }
            last = new BusinessException(502, "脚本不符合契约: " + String.join("; ", problems));
        }
        throw last;
    }

    private List<String> validate(StoryScript script) {
        List<String> problems = new ArrayList<>();
        if (script.pages() == null || script.pages().isEmpty()) {
            problems.add("pages 为空");
            return problems;
        }
        for (StoryScript.PageItem item : script.pages()) {
            boolean blank = blank(item.narration())
                    && (item.dialogue() == null || item.dialogue().isEmpty())
                    && blank(item.visual());
            if (blank) {
                problems.add("第" + item.page() + "页为空页面");
            }
        }
        return problems;
    }

    /**
     * 规范化:
     * 1. 旁白/对白去标点(无标点契约);
     * 2. 对白 speaker 不在角色表时(模型常把"书页上的字"等旁白式来源放进对白)自动并入旁白,
     *    保证气泡只属于真实角色 —— 相比硬失败,这样能保证流水线在真实模型输出下稳定运行。
     */
    private StoryScript normalize(StoryScript script) {
        Set<String> names = new HashSet<>();
        if (script.characters() != null) {
            for (CharacterItem c : script.characters()) {
                if (notBlank(c.name())) names.add(c.name().trim());
                if (notBlank(c.role())) names.add(c.role().trim());
            }
        }
        List<StoryScript.PageItem> pages = new ArrayList<>();
        for (StoryScript.PageItem item : script.pages()) {
            String narration = PipelineUtils.stripPunctuation(item.narration());
            List<DialogueItem> dialogue = new ArrayList<>();
            if (item.dialogue() != null) {
                for (DialogueItem d : item.dialogue()) {
                    String speaker = PipelineUtils.safe(d.speaker()).trim();
                    String line = PipelineUtils.stripPunctuation(d.line());
                    if (names.isEmpty() || names.contains(speaker) || names.stream().anyMatch(n -> n.contains(speaker) || speaker.contains(n))) {
                        dialogue.add(new DialogueItem(speaker, line));
                    } else {
                        narration = (narration.isBlank() ? "" : narration + " ") + speaker + " " + line;
                    }
                }
            }
            pages.add(new StoryScript.PageItem(item.page(), narration, dialogue, item.visual()));
        }
        return new StoryScript(script.summary(), script.objective(), script.requirements(),
                script.characters(), pages, script.tagline());
    }

    private void upsertCharacters(Long projectId, StoryScript script) {
        if (script.characters() == null) {
            return;
        }
        for (CharacterItem c : script.characters()) {
            String name = notBlank(c.name()) ? c.name().trim() : (notBlank(c.role()) ? c.role().trim() : "");
            if (name.isBlank()) {
                continue;
            }
            String structured = writeStructured(c);
            String description = PipelineUtils.buildCharacterDescription(c);
            Asset existing = ctx.assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                    .eq(Asset::getProjectId, projectId)
                    .eq(Asset::getAssetType, Asset.TYPE_CHARACTER)
                    .eq(Asset::getName, name));
            if (existing != null) {
                Asset patch = new Asset();
                patch.setId(existing.getId());
                if (blank(existing.getStructured()) || "{}".equals(existing.getStructured())) {
                    patch.setStructured(structured);
                }
                if (blank(existing.getDescription()) && notBlank(description)) {
                    patch.setDescription(description);
                }
                patch.setUpdateTime(LocalDateTime.now());
                ctx.assetMapper.updateById(patch);
                continue;
            }
            Asset asset = new Asset();
            asset.setProjectId(projectId);
            asset.setAssetType(Asset.TYPE_CHARACTER);
            asset.setName(name);
            asset.setAliases(writeAliases(c.role()));
            asset.setDescription(description);
            asset.setStructured(structured);
            asset.setGenStatus(Asset.GEN_IDLE);
            asset.setCreateTime(LocalDateTime.now());
            ctx.assetMapper.insert(asset);
        }
    }

    private String writeStructured(CharacterItem c) {
        try {
            return objectMapper.writeValueAsString(new AssetExtractResult.Structured(
                    PipelineUtils.safe(c.role()), PipelineUtils.safe(c.age()), PipelineUtils.safe(c.hair()),
                    PipelineUtils.safe(c.accessories()), PipelineUtils.safe(c.top()), PipelineUtils.safe(c.bottom())));
        } catch (Exception e) {
            return "{}";
        }
    }

    private String writeAliases(String role) {
        try {
            List<String> aliases = notBlank(role) ? List.of(role.trim()) : List.of();
            return objectMapper.writeValueAsString(aliases);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean notBlank(String s) {
        return !blank(s);
    }
}
