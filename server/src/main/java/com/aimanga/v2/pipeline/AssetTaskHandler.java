package com.aimanga.v2.pipeline;

import com.aimanga.v2.ai.AiService;
import com.aimanga.v2.ai.PromptService;
import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.Asset;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.pipeline.AssetExtractResult.AssetItem;
import com.aimanga.v2.task.TaskHandler;
import com.aimanga.v2.task.TaskRuntime;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ASSET 资产提取任务(整本一次):
 * 从故事原文提取四类资产(角色/场景/道具/服装),同名跳过、角色可被补充结构化信息;
 * 完成后按 feature_auto_sheet 链式入队 SHEET。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetTaskHandler implements TaskHandler {

    public static final String TYPE = "ASSET";
    private static final int MAX_SOURCE_CHARS = 30000;

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
        Project project = ctx.project(task.getProjectId());
        String text = safeSource(project);
        if (text.isBlank()) {
            throw new BusinessException(400, "作品没有故事原文,无法提取资产");
        }
        if (text.length() > MAX_SOURCE_CHARS) {
            text = text.substring(0, MAX_SOURCE_CHARS);
        }
        List<Asset> existingCharacters = ctx.assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, project.getId())
                .eq(Asset::getAssetType, Asset.TYPE_CHARACTER));
        String existingNames = existingCharacters.stream()
                .map(Asset::getName)
                .collect(Collectors.joining("、"));

        runtime.begin(2);
        String prompt = promptService.render("prompt_asset", PipelinePrompts.DEFAULT_ASSET,
                Map.of("text", text, "existing", existingNames.isBlank() ? "(无)" : existingNames));
        AssetExtractResult result = aiService.chatJson("text", prompt, AssetExtractResult.class);
        runtime.stepSuccess();

        List<AssetItem> items = result.assets() == null ? List.of() : result.assets();
        int inserted = 0;
        for (AssetItem item : items) {
            if (item.name() == null || item.name().isBlank()) continue;
            int type = item.assetType() == null || item.assetType() < 1 || item.assetType() > 4 ? Asset.TYPE_CHARACTER : item.assetType();
            Asset existing = ctx.assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                    .eq(Asset::getProjectId, project.getId())
                    .eq(Asset::getAssetType, type)
                    .eq(Asset::getName, item.name().trim()));
            if (existing != null) {
                // 已有:仅当缺描述/结构化时补充
                Asset patch = new Asset();
                patch.setId(existing.getId());
                if (blank(existing.getDescription()) && notBlank(item.description())) {
                    patch.setDescription(item.description());
                }
                if (type == Asset.TYPE_CHARACTER && (blank(existing.getStructured()) || "{}".equals(existing.getStructured()))
                        && item.structured() != null) {
                    patch.setStructured(writeStructured(item.structured()));
                }
                patch.setUpdateTime(LocalDateTime.now());
                ctx.assetMapper.updateById(patch);
                continue;
            }
            Asset asset = new Asset();
            asset.setProjectId(project.getId());
            asset.setAssetType(type);
            asset.setName(item.name().trim());
            asset.setAliases(writeAliases(item.aliases()));
            asset.setDescription(item.description());
            asset.setStructured(type == Asset.TYPE_CHARACTER && item.structured() != null
                    ? writeStructured(item.structured()) : "{}");
            asset.setGenStatus(Asset.GEN_IDLE);
            asset.setCreateTime(LocalDateTime.now());
            ctx.assetMapper.insert(asset);
            inserted++;
        }
        runtime.stepSuccess();
        log.info("[asset] 作品 {} 资产提取完成: AI 返回 {} 项,新增 {} 项", project.getId(), items.size(), inserted);

        if (ctx.feature("feature_auto_sheet")) {
            ctx.enqueue(project.getId(), null, SheetTaskHandler.TYPE, "{}");
        }
    }

    private String writeStructured(AssetExtractResult.Structured s) {
        try {
            return objectMapper.writeValueAsString(s);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String writeAliases(List<String> aliases) {
        try {
            List<String> cleaned = aliases == null ? List.of()
                    : aliases.stream().filter(a -> a != null && !a.isBlank()).map(String::trim).toList();
            return objectMapper.writeValueAsString(cleaned);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String safeSource(Project project) {
        return project.getSourceText() == null ? "" : project.getSourceText();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean notBlank(String s) {
        return !blank(s);
    }
}
