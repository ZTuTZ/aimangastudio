package com.aimanga.v2.pipeline;

import com.aimanga.v2.ai.AiService;
import com.aimanga.v2.ai.PromptService;
import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.Asset;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.pipeline.asset.AssetMergeService;
import com.aimanga.v2.pipeline.asset.AssetPackBuilder;
import com.aimanga.v2.task.TaskHandler;
import com.aimanga.v2.task.TaskRuntime;
import com.aimanga.v2.task.TaskStopSignal;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * ASSET 资产提取任务(Phase 5.5 按话分包版):
 * - 按 chapter_no ASC 分包(每包最多 asset_pack_max_chapters 话且约 asset_pack_max_chars 字);
 * - 包内受 asset_pack_concurrency 并发控制,单包独立重试 2 次;
 * - 多包结果统一 AssetMergeService 全局合并(name/aliases 归一,保护人工值)后一次 upsert;
 * - 链式入队:为所有话入队 SCRIPT(SHEET 改为全部脚本就绪后执行)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetTaskHandler implements TaskHandler {

    public static final String TYPE = "ASSET";

    private final PipelineContext ctx;
    private final AiService aiService;
    private final PromptService promptService;
    private final AssetMergeService mergeService;
    private final ObjectMapper objectMapper;
    private final PipelineStageService stageService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(TaskEntity task, TaskRuntime runtime) {
        Project project = ctx.project(task.getProjectId());
        List<Chapter> chapters = ctx.chaptersOf(project.getId()).stream()
                .filter(c -> c.getScriptText() != null && !c.getScriptText().isBlank())
                .collect(Collectors.toList());
        if (chapters.isEmpty()) {
            throw new BusinessException(400, "请先完成拆话(没有可提取资产的话内容)");
        }

        int packMaxChapters = Math.max(1, ctx.configService.getInt("asset_pack_max_chapters", 5));
        int packMaxChars = Math.max(1000, ctx.configService.getInt("asset_pack_max_chars", 12000));
        int packConcurrency = Math.max(1, ctx.configService.getInt("asset_pack_concurrency", 2));
        List<List<Chapter>> packs = AssetPackBuilder.build(chapters, packMaxChapters, packMaxChars);

        String existingNames = ctx.assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getProjectId, project.getId())
                        .eq(Asset::getAssetType, Asset.TYPE_CHARACTER))
                .stream().map(Asset::getName).collect(Collectors.joining("、"));

        stageService.markRunning(project.getId(), PipelineStageService.STAGE_ASSET);
        runtime.begin(packs.size() + 1);

        // 多包受控并发执行;单包独立重试;结果不立即写库
        List<AssetExtractResult> packResults = new ArrayList<>(Arrays.asList(new AssetExtractResult[packs.size()]));
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(packConcurrency, packs.size()));
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < packs.size(); i++) {
                final int idx = i;
                final List<Chapter> pack = packs.get(i);
                futures.add(CompletableFuture.runAsync(() -> {
                    runtime.checkStop();
                    StringBuilder text = new StringBuilder();
                    for (Chapter chapter : pack) {
                        text.append("第").append(chapter.getChapterNo()).append("话 ")
                                .append(chapter.getTitle()).append('\n')
                                .append(chapter.getScriptText()).append("\n\n");
                    }
                    String prompt = promptService.render("prompt_asset", PipelinePrompts.DEFAULT_ASSET, Map.of(
                            "text", text.toString(),
                            "existing", existingNames.isBlank() ? "(无)" : existingNames));
                    AssetExtractResult result = null;
                    RuntimeException last = null;
                    for (int attempt = 0; attempt < 3; attempt++) {
                        try {
                            result = aiService.chatJson("text", prompt, AssetExtractResult.class);
                            break;
                        } catch (BusinessException e) {
                            last = e;
                            log.warn("[asset] 第 {} 包第 {} 次尝试失败: {}", idx + 1, attempt + 1, e.getMessage());
                        }
                    }
                    if (result == null) {
                        throw new BusinessException(502, "第 " + (idx + 1) + " 包资产提取失败: "
                                + (last == null ? "未知" : last.getMessage()));
                    }
                    packResults.set(idx, result);
                    runtime.stepSuccess();
                }, pool));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause() == null ? ce : ce.getCause();
                if (cause instanceof TaskStopSignal signal) {
                    throw signal;
                }
                if (cause instanceof BusinessException be) {
                    throw be;
                }
                throw new BusinessException(502, "资产提取执行失败: " + cause.getMessage());
            }
        } finally {
            pool.shutdownNow();
        }

        runtime.stepSuccess();

        // 全局合并 + 一次 upsert(保护人工值)
        List<AssetExtractResult> successResults = packResults.stream().filter(java.util.Objects::nonNull).toList();
        var canonical = mergeService.mergePacks(successResults);
        var stats = mergeService.upsertAll(project.getId(), canonical);
        runtime.stepSuccess();

        TaskEntity patch = new TaskEntity();
        patch.setId(task.getId());
        try {
            patch.setResult(objectMapper.writeValueAsString(Map.of(
                    "packs", packs.size(),
                    "rawAssets", successResults.stream().mapToInt(r -> r.assets() == null ? 0 : r.assets().size()).sum(),
                    "mergedAssets", canonical.size())));
        } catch (Exception ignored) {
            // 记录失败不影响任务
        }
        ctx.taskMapper.updateById(patch);

        // 链式入队:为所有话入队 SCRIPT(SHEET 待全部脚本就绪后执行)
        for (Chapter chapter : chapters) {
            ctx.enqueueUnique(project.getId(), chapter.getId(), ScriptTaskHandler.TYPE, "{}");
        }
        stageService.markSuccess(project.getId(), PipelineStageService.STAGE_ASSET);
        log.info("[asset] 作品 {} 资产提取完成: {} 包,canonical {} 项(新增 {}/更新 {})",
                project.getId(), packs.size(), canonical.size(), stats.inserted(), stats.updated());
    }
}
