package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.PipelineStage;
import com.aimanga.v2.repository.PipelineStageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 流水线阶段生命周期管理(Phase 5.7 断点恢复核心):
 * - 每个 (projectId, stageType) 只有一条记录(UNIQUE);
 * - Handler 开始时 markRunning,成功时 markSuccess,失败时 markFailed;
 * - 恢复时 isStageSuccess() 判断是否跳过已完成阶段;
 * - 暂停/继续:pauseProject / resumeProject。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineStageService {

    public static final String STAGE_SPLIT = "SPLIT";
    public static final String STAGE_ASSET = "ASSET";
    public static final String STAGE_SCRIPT = "SCRIPT";
    public static final String STAGE_SHEET = "SHEET";
    public static final String STAGE_LAYOUT = "LAYOUT";
    public static final String STAGE_IMAGE = "IMAGE";
    public static final String STAGE_EXPORT = "EXPORT";

    private final PipelineStageMapper stageMapper;

    /** 标记阶段开始(UPSERT:不存在则创建,存在则更新为进行中) */
    public void markRunning(Long projectId, String stageType) {
        PipelineStage stage = getOrCreate(projectId, stageType);
        PipelineStage patch = new PipelineStage();
        patch.setId(stage.getId());
        patch.setStatus(PipelineStage.STATUS_RUNNING);
        patch.setProgress(0);
        patch.setStartTime(LocalDateTime.now());
        patch.setFinishTime(null);
        patch.setError("");
        stageMapper.updateById(patch);
    }

    /** 标记阶段成功 */
    public void markSuccess(Long projectId, String stageType) {
        PipelineStage stage = getOrCreate(projectId, stageType);
        PipelineStage patch = new PipelineStage();
        patch.setId(stage.getId());
        patch.setStatus(PipelineStage.STATUS_SUCCESS);
        patch.setProgress(100);
        patch.setFinishTime(LocalDateTime.now());
        stageMapper.updateById(patch);
        log.info("[stage] {} {} → SUCCESS", projectId, stageType);
    }

    /** 标记阶段失败 */
    public void markFailed(Long projectId, String stageType, String error) {
        PipelineStage stage = getOrCreate(projectId, stageType);
        PipelineStage patch = new PipelineStage();
        patch.setId(stage.getId());
        patch.setStatus(PipelineStage.STATUS_FAILED);
        patch.setError(error == null ? "" : error.substring(0, Math.min(error.length(), 512)));
        patch.setFinishTime(LocalDateTime.now());
        stageMapper.updateById(patch);
    }

    /** 判断阶段是否已成功(恢复时跳过已完成阶段) */
    public boolean isStageSuccess(Long projectId, String stageType) {
        PipelineStage stage = stageMapper.selectOne(new LambdaQueryWrapper<PipelineStage>()
                .eq(PipelineStage::getProjectId, projectId)
                .eq(PipelineStage::getStageType, stageType));
        return stage != null && stage.getStatus() == PipelineStage.STATUS_SUCCESS;
    }

    /** 判断阶段是否处于暂停状态 */
    public boolean isStagePaused(Long projectId, String stageType) {
        PipelineStage stage = stageMapper.selectOne(new LambdaQueryWrapper<PipelineStage>()
                .eq(PipelineStage::getProjectId, projectId)
                .eq(PipelineStage::getStageType, stageType));
        return stage != null && stage.getStatus() == PipelineStage.STATUS_PAUSED;
    }

    /** 查询项目的全部阶段(前端进度条用) */
    public List<PipelineStage> listByProject(Long projectId) {
        return stageMapper.selectList(new LambdaQueryWrapper<PipelineStage>()
                .eq(PipelineStage::getProjectId, projectId)
                .orderByAsc(PipelineStage::getId));
    }

    /** 暂停项目的所有进行中阶段 */
    public void pauseProject(Long projectId) {
        stageMapper.update(null, new LambdaUpdateWrapper<PipelineStage>()
                .eq(PipelineStage::getProjectId, projectId)
                .eq(PipelineStage::getStatus, PipelineStage.STATUS_RUNNING)
                .set(PipelineStage::getStatus, PipelineStage.STATUS_PAUSED)
                .set(PipelineStage::getUpdateTime, LocalDateTime.now()));
    }

    /** 继续项目的暂停阶段(重置为排队,由恢复/链式入队重新启动) */
    public void resumeProject(Long projectId) {
        stageMapper.update(null, new LambdaUpdateWrapper<PipelineStage>()
                .eq(PipelineStage::getProjectId, projectId)
                .eq(PipelineStage::getStatus, PipelineStage.STATUS_PAUSED)
                .set(PipelineStage::getStatus, PipelineStage.STATUS_PENDING)
                .set(PipelineStage::getUpdateTime, LocalDateTime.now()));
    }

    /** 查找第一个未成功的阶段类型(恢复入口),全部成功返回 null */
    public String firstIncompleteStage(Long projectId) {
        String[] order = {STAGE_SPLIT, STAGE_ASSET, STAGE_SCRIPT, STAGE_SHEET};
        for (String stageType : order) {
            if (!isStageSuccess(projectId, stageType)) {
                return stageType;
            }
        }
        return null;
    }

    private PipelineStage getOrCreate(Long projectId, String stageType) {
        PipelineStage stage = stageMapper.selectOne(new LambdaQueryWrapper<PipelineStage>()
                .eq(PipelineStage::getProjectId, projectId)
                .eq(PipelineStage::getStageType, stageType));
        if (stage == null) {
            stage = new PipelineStage();
            stage.setProjectId(projectId);
            stage.setStageType(stageType);
            stage.setStatus(PipelineStage.STATUS_PENDING);
            stage.setCreateTime(LocalDateTime.now());
            stageMapper.insert(stage);
        }
        return stage;
    }
}
