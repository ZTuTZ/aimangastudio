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
import com.aimanga.v2.pipeline.text.SourceTextIndexer;
import com.aimanga.v2.pipeline.text.SourceUnit;
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
import java.util.stream.Collectors;

/**
 * SCRIPT 六段式脚本任务(Phase 5.5 版,每话一个):
 * - 注入全书标准资产上下文({assets}),禁止重新设计已有角色;
 * - 本话原文按 SourceUnit 编号,每页携带 sourceStartUnit/sourceEndUnit;
 * - 页级 Source Spine 硬校验:第1页从 U0001 起、连续覆盖到最后 Unit、无 gap/overlap/倒序;
 * - speaker 合法集 = 全局角色 name+aliases + 本话新角色;未知说话人并入旁白;
 * - upsert 角色按 canonical name/aliases 命中,只补缺失字段,绝不覆盖非空人工值;
 * - 全部话就绪 → enqueueUnique SHEET(feature_auto_sheet)或直接推进作品「待出图」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScriptTaskHandler implements TaskHandler {

    public static final String TYPE = "SCRIPT";

    private final PipelineContext ctx;
    private final AiService aiService;
    private final PromptService promptService;
    private final AssetContextService assetContextService;
    private final ObjectMapper objectMapper;
    private final PipelineStageService stageService;

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

        // 本话原文 SourceUnit 索引 + 页数自适应
        List<SourceUnit> units = SourceTextIndexer.index(text);
        int unitCount = units.size();
        String numbered = SourceTextIndexer.toNumberedText(units, 0, unitCount);
        int pageTarget = Math.max(1, ctx.configService.getInt("storyboard_page_count", 10));
        int minPerPage = ctx.configService.getInt("split_min_chars_per_page", 35);
        int targetPerPage = ctx.configService.getInt("split_target_chars_per_page", 60);
        int actualPages = text.length() < pageTarget * minPerPage
                ? Math.max(1, (int) Math.ceil((double) text.length() / targetPerPage))
                : pageTarget;
        actualPages = Math.min(actualPages, pageTarget);

        String assetsContext = assetContextService.buildScriptAssetContext(project, chapter);
        String style = ctx.stylePromptOf(project);

        runtime.begin(3);
        String prompt = promptService.render("prompt_script", PipelinePrompts.DEFAULT_STORYBOARD, Map.of(
                "assets", assetsContext,
                "page_count", String.valueOf(actualPages),
                "style", style,
                "text", numbered));
        StoryScript script = executeWithRetry(prompt, unitCount, project.getId());
        runtime.stepSuccess();

        // 写页(重建该话全部页;SourceUnit 仅作校验锚点,不入库)
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

        // 角色 upsert(全局资产 canonical 优先,alias 命中不建第二卡)
        upsertCharacters(project.getId(), script);
        runtime.stepSuccess();

        // 话状态 → 脚本就绪
        Chapter chapterPatch = new Chapter();
        chapterPatch.setId(chapter.getId());
        chapterPatch.setStatus(Chapter.STATUS_SCRIPT_READY);
        chapterPatch.setPageCount(pages.size());
        chapterPatch.setUpdateTime(LocalDateTime.now());
        ctx.chapterMapper.updateById(chapterPatch);

        // 全部话就绪 → SHEET(去重)或直接推进「待出图」
        boolean allReady = ctx.chaptersOf(project.getId()).stream()
                .allMatch(c -> c.getStatus() != null && c.getStatus() >= Chapter.STATUS_SCRIPT_READY);
        if (allReady) {
            stageService.markSuccess(project.getId(), PipelineStageService.STAGE_SCRIPT);
            if (ctx.feature("feature_auto_sheet")) {
                ctx.enqueueUnique(project.getId(), null, SheetTaskHandler.TYPE, "{}");
            } else {
                advanceProjectIfPreparing(project.getId());
            }
        }
        log.info("[script] 话 {} 脚本完成: {} 页(实际目标 {}),Source Spine 校验通过", chapter.getId(), pages.size(), actualPages);
    }

    /** AI 生成 + 契约校验(非空/无空页/页级 Source Spine),失败自动重试 1 次 */
    private StoryScript executeWithRetry(String prompt, int unitCount, Long projectId) {
        BusinessException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            StoryScript script;
            try {
                script = aiService.chatJson("text", prompt, StoryScript.class);
            } catch (BusinessException e) {
                last = e;
                continue;
            }
            // 程序侧修复:AI 定切点,Java 强制页区间连续对齐(首页 U0001、消除缺口、末页覆盖末 Unit)
            StoryScript repaired = repairSpine(script, unitCount);
            List<String> problems = validate(repaired, unitCount);
            if (problems.isEmpty()) {
                return normalize(repaired, globalSpeakers(projectId));
            }
            last = new BusinessException(502, "脚本不符合契约: " + String.join("; ", problems));
        }
        throw last;
    }

    private List<String> validate(StoryScript script, int unitCount) {
        List<String> problems = new ArrayList<>();
        if (script.pages() == null || script.pages().isEmpty()) {
            problems.add("pages 为空");
            return problems;
        }
        for (int i = 0; i < script.pages().size(); i++) {
            StoryScript.PageItem item = script.pages().get(i);
            boolean blank = blank(item.narration())
                    && (item.dialogue() == null || item.dialogue().isEmpty())
                    && blank(item.visual());
            if (blank) {
                problems.add("第" + item.page() + "页为空页面");
            }
            if (item.page() == null || item.page() != i + 1) {
                problems.add("页序号不连续(第" + (i + 1) + "个元素 page=" + item.page() + ")");
            }
        }
        problems.addAll(validateSpine(script.pages(), unitCount));
        return problems;
    }

    /** 页级 Source Spine 硬校验(Phase 5.5 T5.5.7) */
    static List<String> validateSpine(List<StoryScript.PageItem> pages, int unitCount) {
        List<String> problems = new ArrayList<>();
        for (StoryScript.PageItem item : pages) {
            if (item.sourceStartUnit() == null || item.sourceEndUnit() == null) {
                problems.add("第" + item.page() + "页缺少 sourceStartUnit/sourceEndUnit");
                return problems;
            }
            if (item.sourceStartUnit() < 1 || item.sourceEndUnit() < item.sourceStartUnit() || item.sourceEndUnit() > unitCount) {
                problems.add("第" + item.page() + "页覆盖区间非法:[" + item.sourceStartUnit() + "," + item.sourceEndUnit() + "]");
                return problems;
            }
        }
        if (pages.get(0).sourceStartUnit() != 1) {
            problems.add("第1页必须从 U0001 开始");
        }
        for (int i = 0; i < pages.size() - 1; i++) {
            if (pages.get(i + 1).sourceStartUnit() != pages.get(i).sourceEndUnit() + 1) {
                problems.add("第" + pages.get(i).page() + "页与第" + pages.get(i + 1).page() + "页之间存在缺口/重叠/倒序");
                return problems;
            }
        }
        if (pages.get(pages.size() - 1).sourceEndUnit() != unitCount) {
            problems.add("最后一页未覆盖到最后一个 Unit(U" + unitCount + ")");
        }
        return problems;
    }

    /**
     * 页区间程序修复:AI 负责切点,程序负责覆盖完整性(确定性事实):
     * 按 sourceStartUnit 排序 → 首页强制 U0001 → 消除页间缺口/重叠 → 末页强制覆盖到最后 Unit。
     * 无法修复(区间重叠且无法对齐)时返回原样,由校验失败触发重试。
     */
    static StoryScript repairSpine(StoryScript script, int unitCount) {
        if (script.pages() == null || script.pages().isEmpty()) {
            return script;
        }
        for (StoryScript.PageItem item : script.pages()) {
            if (item.sourceStartUnit() == null || item.sourceEndUnit() == null) {
                return script; // 缺锚点不可修复
            }
        }
        List<StoryScript.PageItem> sorted = new ArrayList<>(script.pages());
        sorted.sort(java.util.Comparator.comparingInt(StoryScript.PageItem::sourceStartUnit));
        List<StoryScript.PageItem> repaired = new ArrayList<>();
        int expected = 1;
        for (int i = 0; i < sorted.size(); i++) {
            StoryScript.PageItem item = sorted.get(i);
            int start = i == 0 ? 1 : expected;
            int end = Math.max(item.sourceEndUnit(), start);
            if (i == sorted.size() - 1) {
                end = Math.max(end, unitCount); // 末页必须覆盖到最后
            }
            if (end > unitCount) {
                end = unitCount;
            }
            if (start > end) {
                return script; // 重叠到无法对齐,交给重试
            }
            repaired.add(new StoryScript.PageItem(item.page(), start, end,
                    item.narration(), item.dialogue(), item.visual()));
            expected = end + 1;
        }
        // 末页若仍未到 unitCount(排除了末页强制的场景),由最后一段补齐
        if (repaired.get(repaired.size() - 1).sourceEndUnit() != unitCount) {
            StoryScript.PageItem lastItem = repaired.get(repaired.size() - 1);
            repaired.set(repaired.size() - 1, new StoryScript.PageItem(lastItem.page(),
                    lastItem.sourceStartUnit(), unitCount, lastItem.narration(), lastItem.dialogue(), lastItem.visual()));
        }
        return new StoryScript(script.summary(), script.objective(), script.requirements(),
                script.characters(), repaired, script.tagline());
    }

    /** speaker 合法集:全局角色 name+aliases + 本话新角色 name/role */
    private Set<String> globalSpeakers(Long projectId) {
        Set<String> speakers = new HashSet<>();
        List<Asset> characters = ctx.assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, projectId)
                .eq(Asset::getAssetType, Asset.TYPE_CHARACTER));
        for (Asset asset : characters) {
            if (notBlank(asset.getName())) speakers.add(asset.getName().trim());
            speakers.addAll(parseAliases(asset.getAliases()));
        }
        return speakers;
    }

    /** 规范化:去标点;未知 speaker 并入旁白 */
    private StoryScript normalize(StoryScript script, Set<String> allowedSpeakers) {
        List<StoryScript.PageItem> pages = new ArrayList<>();
        for (StoryScript.PageItem item : script.pages()) {
            String narration = PipelineUtils.stripPunctuation(item.narration());
            List<DialogueItem> dialogue = new ArrayList<>();
            if (item.dialogue() != null) {
                for (DialogueItem d : item.dialogue()) {
                    String speaker = PipelineUtils.safe(d.speaker()).trim();
                    String line = PipelineUtils.stripPunctuation(d.line());
                    if (allowedSpeakers.isEmpty() || allowedSpeakers.contains(speaker)
                            || allowedSpeakers.stream().anyMatch(n -> n.contains(speaker) || speaker.contains(n))) {
                        dialogue.add(new DialogueItem(speaker, line));
                    } else {
                        narration = (narration.isBlank() ? "" : narration + " ") + speaker + " " + line;
                    }
                }
            }
            pages.add(new StoryScript.PageItem(item.page(), item.sourceStartUnit(), item.sourceEndUnit(),
                    narration, dialogue, item.visual()));
        }
        return new StoryScript(script.summary(), script.objective(), script.requirements(),
                script.characters(), pages, script.tagline());
    }

    /** 角色 upsert:canonical name/aliases 命中不建第二卡;只补缺失字段,不覆盖非空值 */
    private void upsertCharacters(Long projectId, StoryScript script) {
        if (script.characters() == null) {
            return;
        }
        List<Asset> existingCharacters = ctx.assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, projectId)
                .eq(Asset::getAssetType, Asset.TYPE_CHARACTER));
        for (CharacterItem c : script.characters()) {
            String name = notBlank(c.name()) ? c.name().trim() : (notBlank(c.role()) ? c.role().trim() : "");
            if (name.isBlank()) {
                continue;
            }
            String structured = writeStructured(c);
            String description = PipelineUtils.buildCharacterDescription(c);
            Asset existing = null;
            for (Asset asset : existingCharacters) {
                List<String> aliases = parseAliases(asset.getAliases());
                boolean hit = asset.getName().equalsIgnoreCase(name)
                        || aliases.stream().anyMatch(a -> a.equalsIgnoreCase(name))
                        || (notBlank(c.role()) && (asset.getName().equalsIgnoreCase(c.role().trim())
                            || aliases.stream().anyMatch(a -> a.equalsIgnoreCase(c.role().trim()))));
                if (hit) {
                    existing = asset;
                    break;
                }
            }
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
            existingCharacters.add(asset);
        }
    }

    private void advanceProjectIfPreparing(Long projectId) {
        Project project = ctx.project(projectId);
        if (project.getStatus() != null && project.getStatus() == Project.STATUS_PREPARING) {
            Project patch = new Project();
            patch.setId(projectId);
            patch.setStatus(Project.STATUS_READY);
            patch.setUpdateTime(LocalDateTime.now());
            ctx.projectMapper.updateById(patch);
        }
    }

    private String writeStructured(CharacterItem c) {
        try {
            return objectMapper.writeValueAsString(new com.aimanga.v2.pipeline.AssetExtractResult.Structured(
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

    private List<String> parseAliases(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(
                    json == null || json.isBlank() ? "[]" : json);
            List<String> list = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(n -> {
                    if (n.isTextual() && !n.asText().isBlank()) list.add(n.asText());
                });
            }
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean notBlank(String s) {
        return !blank(s);
    }
}
