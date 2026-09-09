package com.aimanga.v2.pipeline;

import com.aimanga.v2.ai.AiService;
import com.aimanga.v2.ai.PromptService;
import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.Asset;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.task.TaskHandler;
import com.aimanga.v2.task.TaskRuntime;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * SHEET 角色设定表任务:
 * - payload {"assetId": x} → 只生成该角色(手动重生成);
 * - 否则为所有尚无设定表的角色;
 * - 每个角色一次生图调用(六姿势参考表),单个失败计入失败不阻塞(终态可为 PARTIAL);
 * - 全部完成后若 project.status 仍为"准备中",推进为"待出图"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SheetTaskHandler implements TaskHandler {

    public static final String TYPE = "SHEET";

    private final PipelineContext ctx;
    private final AiService aiService;
    private final PromptService promptService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(TaskEntity task, TaskRuntime runtime) {
        Project project = ctx.project(task.getProjectId());
        Long assetId = parseAssetId(task.getPayload());

        List<Asset> targets;
        if (assetId != null) {
            Asset asset = ctx.assetMapper.selectById(assetId);
            if (asset == null || !asset.getProjectId().equals(project.getId())) {
                throw new BusinessException(404, "资产不存在: " + assetId);
            }
            targets = List.of(asset);
        } else {
            targets = ctx.assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                    .eq(Asset::getProjectId, project.getId())
                    .eq(Asset::getAssetType, Asset.TYPE_CHARACTER)
                    .and(w -> w.isNull(Asset::getSheetImageUrl).or().eq(Asset::getSheetImageUrl, "")));
        }
        if (targets.isEmpty()) {
            advanceProject(project);
            return;
        }

        runtime.begin(targets.size());
        for (Asset asset : targets) {
            runtime.checkStop();
            // 标记生成中(前端立即可见)
            Asset mark = new Asset();
            mark.setId(asset.getId());
            mark.setGenStatus(Asset.GEN_RUNNING);
            mark.setUpdateTime(LocalDateTime.now());
            ctx.assetMapper.updateById(mark);
            try {
                String style = ctx.stylePromptOf(project);
                String prompt = "为角色「" + asset.getName() + "」创建参考表。综合设定:"
                        + (asset.getDescription() == null ? "" : asset.getDescription())
                        + "。风格:" + (style.isBlank() ? "干净漫画" : style)
                        + "。布局:两行共六姿势——上行三头像(侧/正/笑),下行三全身(正/侧/背)。只输出图像,不要文字。";
                List<String> refs = asset.getReferenceUrl() == null || asset.getReferenceUrl().isBlank()
                        ? List.of() : List.of(asset.getReferenceUrl());
                String url = aiService.generateImage("image", prompt, refs, "3:4", project.getUserId());
                Asset patch = new Asset();
                patch.setId(asset.getId());
                patch.setSheetImageUrl(url);
                patch.setGenStatus(Asset.GEN_IDLE);
                patch.setUpdateTime(LocalDateTime.now());
                ctx.assetMapper.updateById(patch);
                runtime.stepSuccess();
            } catch (Exception e) {
                log.warn("[sheet] 角色 {} 设定表生成失败: {}", asset.getName(), e.getMessage());
                Asset patch = new Asset();
                patch.setId(asset.getId());
                patch.setGenStatus(Asset.GEN_FAILED);
                patch.setUpdateTime(LocalDateTime.now());
                ctx.assetMapper.updateById(patch);
                runtime.stepFail(e.getMessage());
            }
        }
        advanceProject(project);
    }

    private void advanceProject(Project project) {
        Project current = ctx.project(project.getId());
        if (current.getStatus() != null && current.getStatus() == Project.STATUS_PREPARING) {
            Project patch = new Project();
            patch.setId(project.getId());
            patch.setStatus(Project.STATUS_READY);
            patch.setUpdateTime(LocalDateTime.now());
            ctx.projectMapper.updateById(patch);
            log.info("[sheet] 作品 {} 准备完成,状态推进为「待出图」", project.getId());
        }
    }

    private Long parseAssetId(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
            return node.has("assetId") && node.get("assetId").canConvertToLong() ? node.get("assetId").asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
