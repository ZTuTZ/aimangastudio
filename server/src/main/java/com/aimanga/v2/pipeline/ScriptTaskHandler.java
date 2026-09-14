package com.aimanga.v2.pipeline;

import com.aimanga.v2.ai.AiService;
import com.aimanga.v2.ai.PromptService;
import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.Asset;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.PipelineStageItem;
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

/**
 * SCRIPT 六段式脚本任务(Phase 5.8 Stage Item + Phase 5.9 并发引擎):
 * 一个 SCRIPT Task 处理项目内全部 PENDING Stage Items(每话一个 Item)。
 * - ImageStageRunner 有控并发 + 原子领取 + 单元失败重试(max_retry),幂等跳过 SUCCESS Items;
 * - 注入全书标准资产上下文({assets}),禁止重新设计已有角色;
 * - 页级 Source Spine 校验 + 程序侧修复;
 * - speaker 合法集 = 全局角色 name+aliases + 本话新角色,未知说话人并入旁白;
 * - 全部 Item 完成 → markSuccess(SCRIPT) → 链式 SHEET 或推进「待出图」。
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
    private final PipelineStageService stageService;
    private final ImageStageRunner imageStageRunner;
    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(TaskEntity task, TaskRuntime runtime) {
        Project project = ctx.project(task.getProjectId());

        // 重跑支持:把上次终态失败的章节 Item 重新排队(成功的 Item 不受影响);回收崩溃残留的 RUNNING
        stageService.resetFailedItems(project.getId(), PipelineStageService.STAGE_SCRIPT);
        stageService.resetRunningItems(project.getId(), PipelineStageService.STAGE_SCRIPT);

        // 获取全部排队中的 SCRIPT Items(幂等:SUCCESS 的自动跳过)
        List<PipelineStageItem> items = stageService.getPendingItems(project.getId(), PipelineStageService.STAGE_SCRIPT);
        if (items.isEmpty()) {
            // 无排队 Item → 检查是否有 Item 存在(可能是恢复后全部已完成)
            long total = stageService.getItemStats(project.getId(), PipelineStageService.STAGE_SCRIPT).total();
            if (total > 0) {
                log.info("[script] 作品 {} SCRIPT 全部 Item 已完成,跳过", project.getId());
                stageService.markSuccess(project.getId(), PipelineStageService.STAGE_SCRIPT);
                chainAfterScript(project);
                return;
            }
            throw new BusinessException(400, "SCRIPT 阶段没有待处理的 Items(请先完成拆话)");
        }

        log.info("[script] 作品 {} SCRIPT 开始: {} 个 Items", project.getId(), items.size());
        runtime.begin(items.size());
        stageService.markRunning(project.getId(), PipelineStageService.STAGE_SCRIPT);

        // Phase 5.9 并发引擎:原子领取 + 单元失败重试 + 暂停/停止感知
        imageStageRunner.run(project.getId(), PipelineStageService.STAGE_SCRIPT, runtime, item -> {
            processOneChapter(project, item);
            return "{\"chapterId\":" + item.getBusinessId() + "}";
        });

        // 暂停:阶段保持 PAUSED(pauseProject 已置),由恢复/继续重新入队
        if (stageService.isStagePaused(project.getId(), PipelineStageService.STAGE_SCRIPT)) {
            log.info("[script] 作品 {} SCRIPT 暂停中,等待继续", project.getId());
            return;
        }

        // 终态判定(以全量 Item 统计为准:单个失败已在 Runner 内自动重试)
        PipelineStageService.StageItemStats stats = stageService.getItemStats(project.getId(), PipelineStageService.STAGE_SCRIPT);
        if (stats.failed() == 0) {
            stageService.markSuccess(project.getId(), PipelineStageService.STAGE_SCRIPT);
            chainAfterScript(project);
            log.info("[script] 作品 {} SCRIPT 完成: {}/{} 成功", project.getId(), stats.success(), stats.total());
        } else {
            stageService.markFailed(project.getId(), PipelineStageService.STAGE_SCRIPT,
                    stats.failed() + "/" + stats.total() + " 个章节脚本生成失败,重跑 SCRIPT 任务可续作");
        }
    }

    /** 处理单个章节 Item(生成脚本 + 写页 + upsert 角色) */
    private void processOneChapter(Project project, PipelineStageItem item) {
        Long chapterId = item.getBusinessId();
        Chapter chapter = ctx.chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw new BusinessException(404, "话不存在: " + chapterId);
        }

        // 幂等:已就绪则跳过
        if (chapter.getStatus() != null && chapter.getStatus() >= Chapter.STATUS_SCRIPT_READY) {
            log.info("[script] 话 {} 已就绪,跳过", chapterId);
            return;
        }

        Project p = ctx.project(chapter.getProjectId());
        String text = ctx.chapterText(p, chapter);
        if (text.isBlank()) {
            throw new BusinessException(400, "本话没有故事原文,无法生成脚本");
        }

        // SourceUnit 索引 + 页数自适应
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

        String assetsContext = assetContextService.buildScriptAssetContext(p, chapter);
        String style = ctx.stylePromptOf(p);

        String prompt = promptService.render("prompt_script", PipelinePrompts.DEFAULT_STORYBOARD, Map.of(
                "assets", assetsContext,
                "page_count", String.valueOf(actualPages),
                "style", style,
                "text", numbered));
        StoryScript script = executeWithRetry(prompt, unitCount, p.getId());

        // 写页(重建该话全部页)
        writePages(p.getId(), chapter.getId(), script);

        // 角色 upsert
        upsertCharacters(p.getId(), script);

        // 话状态 → 脚本就绪
        Chapter chapterPatch = new Chapter();
        chapterPatch.setId(chapter.getId());
        chapterPatch.setStatus(Chapter.STATUS_SCRIPT_READY);
        chapterPatch.setPageCount(script.pages().size());
        chapterPatch.setUpdateTime(LocalDateTime.now());
        ctx.chapterMapper.updateById(chapterPatch);
    }

    /** 链式:SCRIPT 完成 → SHEET 或推进「待出图」 */
    private void chainAfterScript(Project project) {
        if (ctx.feature("feature_auto_sheet")) {
            ctx.enqueueUnique(project.getId(), null, SheetTaskHandler.TYPE, "{}");
        } else {
            advanceProjectIfPreparing(project.getId());
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

    /** AI 生成 + 契约校验 + 程序修复,失败自动重试 1 次 */
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
            List<String> problems = validate(script, unitCount);
            if (problems.isEmpty()) {
                return normalize(repairSpine(script, unitCount), globalSpeakers(projectId));
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

    /** 页级 Source Spine 硬校验 */
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

    /** 页区间程序修复:AI 定切点,Java 强制对齐连续区间 */
    static StoryScript repairSpine(StoryScript script, int unitCount) {
        if (script.pages() == null || script.pages().isEmpty()) return script;
        for (StoryScript.PageItem item : script.pages()) {
            if (item.sourceStartUnit() == null || item.sourceEndUnit() == null) return script;
        }
        List<StoryScript.PageItem> sorted = new ArrayList<>(script.pages());
        sorted.sort(java.util.Comparator.comparingInt(StoryScript.PageItem::sourceStartUnit));
        List<StoryScript.PageItem> repaired = new ArrayList<>();
        int expected = 1;
        for (int i = 0; i < sorted.size(); i++) {
            StoryScript.PageItem item = sorted.get(i);
            int start = i == 0 ? 1 : expected;
            int end = Math.max(item.sourceEndUnit(), start);
            if (i == sorted.size() - 1) end = Math.max(end, unitCount);
            if (end > unitCount) end = unitCount;
            if (start > end) return script;
            repaired.add(new StoryScript.PageItem(item.page(), start, end, item.narration(), item.dialogue(), item.visual()));
            expected = end + 1;
        }
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

    /** 写页(重建该话全部页) */
    private void writePages(Long projectId, Long chapterId, StoryScript script) {
        List<PageEntity> existing = ctx.pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                .eq(PageEntity::getChapterId, chapterId));
        for (PageEntity page : existing) {
            ctx.pageMapper.deleteById(page.getId());
        }
        List<StoryScript.PageItem> pages = script.pages();
        for (int i = 0; i < pages.size(); i++) {
            StoryScript.PageItem item = pages.get(i);
            PageEntity page = new PageEntity();
            page.setProjectId(projectId);
            page.setChapterId(chapterId);
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
    }

    /** 角色 upsert(canonical name/aliases 命中不建第二卡;只补缺失字段) */
    private void upsertCharacters(Long projectId, StoryScript script) {
        if (script.characters() == null) return;
        List<Asset> existingCharacters = ctx.assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, projectId)
                .eq(Asset::getAssetType, Asset.TYPE_CHARACTER));
        for (CharacterItem c : script.characters()) {
            String name = notBlank(c.name()) ? c.name().trim() : (notBlank(c.role()) ? c.role().trim() : "");
            if (name.isBlank()) continue;
            String structured = writeStructured(c);
            String description = PipelineUtils.buildCharacterDescription(c);
            Asset existing = null;
            for (Asset asset : existingCharacters) {
                List<String> aliases = parseAliases(asset.getAliases());
                boolean hit = asset.getName().equalsIgnoreCase(name)
                        || aliases.stream().anyMatch(a -> a.equalsIgnoreCase(name))
                        || (notBlank(c.role()) && (asset.getName().equalsIgnoreCase(c.role().trim())
                            || aliases.stream().anyMatch(a -> a.equalsIgnoreCase(c.role().trim()))));
                if (hit) { existing = asset; break; }
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

    private String writeStructured(CharacterItem c) {
        try {
            return objectMapper.writeValueAsString(new com.aimanga.v2.pipeline.AssetExtractResult.Structured(
                    PipelineUtils.safe(c.role()), PipelineUtils.safe(c.age()), PipelineUtils.safe(c.hair()),
                    PipelineUtils.safe(c.accessories()), PipelineUtils.safe(c.top()), PipelineUtils.safe(c.bottom())));
        } catch (Exception e) { return "{}"; }
    }

    private String writeAliases(String role) {
        try {
            List<String> aliases = notBlank(role) ? List.of(role.trim()) : List.of();
            return objectMapper.writeValueAsString(aliases);
        } catch (Exception e) { return "[]"; }
    }

    private List<String> parseAliases(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(
                    json == null || json.isBlank() ? "[]" : json);
            List<String> list = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(n -> { if (n.isTextual() && !n.asText().isBlank()) list.add(n.asText()); });
            }
            return list;
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static boolean notBlank(String s) { return !blank(s); }
}
