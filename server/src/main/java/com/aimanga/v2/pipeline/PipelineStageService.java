package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.PipelineStage;
import com.aimanga.v2.model.PipelineStageItem;
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
 * - 暂停/继续:pauseProject / resumeProject;
 * - Phase 5.9:Item 原子领取(claim)/ 失败重试(retry)/ 孤儿回收 / 阶段进度统计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineStageService {

    public static final String STAGE_SPLIT = "SPLIT";
    public static final String STAGE_ASSET = "ASSET";
    public static final String STAGE_SCRIPT = "SCRIPT";
    public static final String STAGE_SHEET = "SHEET";
    public static final String STAGE_REFERENCE = "REFERENCE";
    public static final String STAGE_LAYOUT = "LAYOUT";
    public static final String STAGE_IMAGE = "IMAGE";
    public static final String STAGE_EXPORT = "EXPORT";

    private final PipelineStageMapper stageMapper;
    private final com.aimanga.v2.repository.PipelineStageItemMapper itemMapper;

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

    /** 查询当前处于暂停状态的所有阶段类型(T5.11.5:resume 时逐阶段恢复任务) */
    public List<String> pausedStageTypes(Long projectId) {
        return stageMapper.selectList(new LambdaQueryWrapper<PipelineStage>()
                        .eq(PipelineStage::getProjectId, projectId)
                        .eq(PipelineStage::getStatus, PipelineStage.STATUS_PAUSED))
                .stream()
                .map(PipelineStage::getStageType)
                .distinct()
                .toList();
    }

    /** 继续项目的暂停阶段(重置为排队,由恢复/链式入队重新启动) */
    public void resumeProject(Long projectId) {
        stageMapper.update(null, new LambdaUpdateWrapper<PipelineStage>()
                .eq(PipelineStage::getProjectId, projectId)
                .eq(PipelineStage::getStatus, PipelineStage.STATUS_PAUSED)
                .set(PipelineStage::getStatus, PipelineStage.STATUS_PENDING)
                .set(PipelineStage::getUpdateTime, LocalDateTime.now()));
    }

    /**
     * 查找第一个未完成的阶段类型(恢复入口),全部成功返回 null。
     * SHEET(素材生成)已改为用户按需触发:阶段从未开始过(无记录)时不视为未完成,
     * 避免「继续流水线」把用户没有发起过的素材生成自动跑起来。
     */
    public String firstIncompleteStage(Long projectId) {
        String[] order = {STAGE_SPLIT, STAGE_ASSET, STAGE_SCRIPT, STAGE_SHEET};
        for (String stageType : order) {
            if (isStageSuccess(projectId, stageType)) {
                continue;
            }
            if (STAGE_SHEET.equals(stageType) && !stageExists(projectId, stageType)) {
                continue;
            }
            return stageType;
        }
        return null;
    }

    /** 阶段记录是否存在(从未 markRunning/markSuccess 过的阶段视为未开始) */
    public boolean stageExists(Long projectId, String stageType) {
        Long count = stageMapper.selectCount(new LambdaQueryWrapper<PipelineStage>()
                .eq(PipelineStage::getProjectId, projectId)
                .eq(PipelineStage::getStageType, stageType));
        return count != null && count > 0;
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

    // ---- Stage Item 管理(Phase 5.8) ----

    /** 创建阶段 Items(幂等:已存在的 business_id 跳过) */
    public void createItems(Long projectId, String stageType, String businessType, List<Long> businessIds) {
        for (Long bizId : businessIds) {
            Long exists = itemMapper.selectCount(new LambdaQueryWrapper<PipelineStageItem>()
                    .eq(PipelineStageItem::getProjectId, projectId)
                    .eq(PipelineStageItem::getStageType, stageType)
                    .eq(PipelineStageItem::getBusinessType, businessType)
                    .eq(PipelineStageItem::getBusinessId, bizId));
            if (exists != null && exists > 0) continue;
            PipelineStageItem item = new PipelineStageItem();
            item.setProjectId(projectId);
            item.setStageType(stageType);
            item.setBusinessType(businessType);
            item.setBusinessId(bizId);
            item.setStatus(PipelineStageItem.STATUS_PENDING);
            item.setRetryCount(0);
            item.setCreateTime(LocalDateTime.now());
            itemMapper.insert(item);
        }
    }

    // ---- Stage Item 并发领取与重试(Phase 5.9) ----

    /**
     * Item 原子领取(Phase 8.1 Attempt Fencing):PENDING → RUNNING 并写入 attempt 代次与 token。
     * 多个 Worker 同时领取同一 Item 时只有一个成功,保证同一页/同一角色不被重复生成。
     */
    public boolean claimItem(Long itemId, String attemptToken) {
        return itemMapper.claim(itemId, attemptToken) == 1;
    }

    /** Fenced 释放(用户停止/暂停时把在跑 Item 归还;token 失效=0 行,由调用方忽略) */
    public void releaseItem(Long itemId, String attemptToken) {
        itemMapper.release(itemId, attemptToken);
    }

    public PipelineStageItem getItem(Long itemId) {
        return itemMapper.selectById(itemId);
    }

    /** Item 失败重试(Phase 8.1 fenced):回到 PENDING 并累计 retry_count;token 失效返回 false */
    public boolean markItemRetry(Long itemId, String attemptToken, String error) {
        return itemMapper.markRetry(itemId, attemptToken, error) == 1;
    }

    /** Fenced 成功(Phase 8.1):仅当前 token 持有者可标成功;0 行=已被其他 Attempt 提交,忽略 */
    public boolean markItemSuccess(Long itemId, String attemptToken, String resultRef) {
        return itemMapper.markSuccess(itemId, attemptToken, resultRef) == 1;
    }

    /** Fenced 失败(Phase 8.1):仅当前 token 持有者可标失败;0 行=已被其他 Attempt 接管 */
    public boolean markItemFailed(Long itemId, String attemptToken, String error) {
        return itemMapper.markFailed(itemId, attemptToken, error) == 1;
    }

    /** 回收孤儿 RUNNING Items(进程崩溃/被杀残留)。任务层 claim_token+看门狗保证同一阶段同时只有一个 Runner,启动时重置安全。 */
    public int resetRunningItems(Long projectId, String stageType) {
        return itemMapper.update(null, new LambdaUpdateWrapper<PipelineStageItem>()
                .eq(PipelineStageItem::getProjectId, projectId)
                .eq(PipelineStageItem::getStageType, stageType)
                .eq(PipelineStageItem::getStatus, PipelineStageItem.STATUS_RUNNING)
                .set(PipelineStageItem::getStatus, PipelineStageItem.STATUS_PENDING)
                .set(PipelineStageItem::getAttemptToken, null)
                .set(PipelineStageItem::getClaimedAt, null));
    }

    /** 重跑支持:把终态 FAILED 的 Items 重新排队(重跑 SHEET/SCRIPT 任务时自动重试失败单元) */
    public int resetFailedItems(Long projectId, String stageType) {
        return itemMapper.update(null, new LambdaUpdateWrapper<PipelineStageItem>()
                .eq(PipelineStageItem::getProjectId, projectId)
                .eq(PipelineStageItem::getStageType, stageType)
                .eq(PipelineStageItem::getStatus, PipelineStageItem.STATUS_FAILED)
                .set(PipelineStageItem::getStatus, PipelineStageItem.STATUS_PENDING)
                .set(PipelineStageItem::getAttemptToken, null)
                .set(PipelineStageItem::getClaimedAt, null));
    }

    /** 精准重置:手动重生成指定业务单元时,把对应 Item 重置为排队(不影响其他失败单元) */
    public int resetItemsByBusiness(Long projectId, String stageType, String businessType, List<Long> businessIds) {
        if (businessIds == null || businessIds.isEmpty()) {
            return 0;
        }
        return itemMapper.update(null, new LambdaUpdateWrapper<PipelineStageItem>()
                .eq(PipelineStageItem::getProjectId, projectId)
                .eq(PipelineStageItem::getStageType, stageType)
                .eq(PipelineStageItem::getBusinessType, businessType)
                .in(PipelineStageItem::getBusinessId, businessIds)
                .in(PipelineStageItem::getStatus, PipelineStageItem.STATUS_RUNNING, PipelineStageItem.STATUS_FAILED)
                .set(PipelineStageItem::getStatus, PipelineStageItem.STATUS_PENDING)
                .set(PipelineStageItem::getAttemptToken, null)
                .set(PipelineStageItem::getClaimedAt, null));
    }

    /**
     * 强制重置(Phase 5.11 T5.11.1):用户点击「生成/重新生成」时使用。
     * 与 resetItemsByBusiness 的区别:SUCCESS 也会被重置(否则已有素材的资产点重生成不会触发新生成),
     * 并清空 retry_count/result_ref/error_message,从零开始。
     * result_ref 写入 {"force":true} 标记,处理器据此跳过幂等检查强制重画。
     * 普通失败续跑请使用 resetFailedItems,不得混用。
     */
    public int forceResetItemsByBusiness(Long projectId, String stageType, String businessType, List<Long> businessIds) {
        if (businessIds == null || businessIds.isEmpty()) {
            return 0;
        }
        return itemMapper.update(null, new LambdaUpdateWrapper<PipelineStageItem>()
                .eq(PipelineStageItem::getProjectId, projectId)
                .eq(PipelineStageItem::getStageType, stageType)
                .eq(PipelineStageItem::getBusinessType, businessType)
                .in(PipelineStageItem::getBusinessId, businessIds)
                .set(PipelineStageItem::getStatus, PipelineStageItem.STATUS_PENDING)
                .set(PipelineStageItem::getRetryCount, 0)
                .set(PipelineStageItem::getResultRef, "{\"force\":true}")
                .set(PipelineStageItem::getErrorMessage, "")
                .set(PipelineStageItem::getAttemptToken, null)
                .set(PipelineStageItem::getClaimedAt, null));
    }

    /** 判断 Item 是否被用户强制要求重生成(forceResetItemsByBusiness 写入的标记) */
    public static boolean isForceRequested(PipelineStageItem item) {
        return item.getResultRef() != null && item.getResultRef().contains("\"force\":true");
    }

    /** 清理孤儿 Items(业务单元已删除,如角色被手动移除),避免永久失败的僵尸 Item 卡住阶段 */
    public int removeOrphanItems(Long projectId, String stageType, String businessType, List<Long> validBusinessIds) {
        List<PipelineStageItem> items = itemMapper.selectList(new LambdaQueryWrapper<PipelineStageItem>()
                .eq(PipelineStageItem::getProjectId, projectId)
                .eq(PipelineStageItem::getStageType, stageType)
                .eq(PipelineStageItem::getBusinessType, businessType));
        // T5.11.2:必须按 Stage Item 主键(id)删除;business_id 是业务主键,不能混作 item 主键
        List<Long> orphanItemIds = items.stream()
                .filter(item -> !validBusinessIds.contains(item.getBusinessId()))
                .map(PipelineStageItem::getId)
                .toList();
        if (orphanItemIds.isEmpty()) {
            return 0;
        }
        return itemMapper.deleteBatchIds(orphanItemIds);
    }

    /**
     * 刷新阶段进度统计(Phase 5.9 §10):total_count/success_count/failed_count/progress,
     * progress = success_count / total_count × 100。
     */
    public void updateStageProgress(Long projectId, String stageType) {
        StageItemStats stats = getItemStats(projectId, stageType);
        PipelineStage stage = stageMapper.selectOne(new LambdaQueryWrapper<PipelineStage>()
                .eq(PipelineStage::getProjectId, projectId)
                .eq(PipelineStage::getStageType, stageType));
        if (stage == null) {
            return;
        }
        PipelineStage patch = new PipelineStage();
        patch.setId(stage.getId());
        patch.setTotalCount((int) stats.total());
        patch.setSuccessCount((int) stats.success());
        patch.setFailedCount((int) stats.failed());
        patch.setProgress(stats.total() == 0 ? 0 : (int) Math.min(100L, stats.success() * 100L / stats.total()));
        patch.setUpdateTime(LocalDateTime.now());
        stageMapper.updateById(patch);
    }

    /** 查询指定阶段的所有排队中 Items */
    public List<PipelineStageItem> getPendingItems(Long projectId, String stageType) {
        return itemMapper.selectList(new LambdaQueryWrapper<PipelineStageItem>()
                .eq(PipelineStageItem::getProjectId, projectId)
                .eq(PipelineStageItem::getStageType, stageType)
                .eq(PipelineStageItem::getStatus, PipelineStageItem.STATUS_PENDING)
                .orderByAsc(PipelineStageItem::getBusinessId));
    }

    /** 查询一批排队中 Item id(喂给 Worker 池的候选,按 business_id 升序) */
    public List<Long> getPendingItemIds(Long projectId, String stageType, int limit) {
        return itemMapper.selectPendingIds(projectId, stageType, limit);
    }

    /** Scope 化候选查询(Phase 8.2):businessIds 为空 = 不限制 */
    public List<Long> getPendingItemIdsInScope(Long projectId, String stageType, String businessType,
                                               java.util.Collection<Long> businessIds, int limit) {
        if (businessIds == null || businessIds.isEmpty()) {
            return itemMapper.selectPendingIds(projectId, stageType, limit);
        }
        return itemMapper.selectPendingIdsInScope(projectId, stageType, businessType, businessIds, limit);
    }

    /**
     * 刷新阶段终态(Phase 8.2 §4.8):项目级 Stage 不因某个 scoped Task 成功就直接 SUCCESS。
     * 规则:存在 PENDING/RUNNING → 未完成(保持/进入 RUNNING,PAUSED 保持);
     * 全部终态 + failed=0 → SUCCESS;全部终态 + failed>0 → FAILED。返回刷新后的状态。
     */
    public int refreshStageTerminalState(Long projectId, String stageType) {
        StageItemStats stats = getItemStats(projectId, stageType);
        PipelineStage stage = stageMapper.selectOne(new LambdaQueryWrapper<PipelineStage>()
                .eq(PipelineStage::getProjectId, projectId)
                .eq(PipelineStage::getStageType, stageType));
        if (stage == null) {
            return -1;
        }
        int status;
        if (stats.pending() > 0) {
            status = stage.getStatus() != null && stage.getStatus() == PipelineStage.STATUS_PAUSED
                    ? PipelineStage.STATUS_PAUSED : PipelineStage.STATUS_RUNNING;
        } else if (stats.failed() > 0) {
            status = PipelineStage.STATUS_FAILED;
        } else {
            status = PipelineStage.STATUS_SUCCESS;
        }
        if (stage.getStatus() == null || stage.getStatus() != status) {
            PipelineStage patch = new PipelineStage();
            patch.setId(stage.getId());
            patch.setStatus(status);
            patch.setProgress(stats.total() == 0 ? 0
                    : (int) Math.min(100L, stats.success() * 100L / stats.total()));
            patch.setUpdateTime(java.time.LocalDateTime.now());
            stageMapper.updateById(patch);
        }
        return status;
    }

    /** 标记 Item 进行中 */
    public void markItemRunning(Long itemId) {
        itemMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PipelineStageItem>()
                .eq(PipelineStageItem::getId, itemId)
                .set(PipelineStageItem::getStatus, PipelineStageItem.STATUS_RUNNING));
    }

    /** 标记 Item 成功 */
    /** 判断阶段全部 Item 是否都成功 */
    public boolean allItemsSuccess(Long projectId, String stageType) {
        Long pending = itemMapper.selectCount(new LambdaQueryWrapper<PipelineStageItem>()
                .eq(PipelineStageItem::getProjectId, projectId)
                .eq(PipelineStageItem::getStageType, stageType)
                .ne(PipelineStageItem::getStatus, PipelineStageItem.STATUS_SUCCESS));
        return pending == null || pending == 0;
    }

    /** 查询阶段 Item 统计(分组计数,不加载全量行) */
    public record StageItemStats(long total, long success, long failed, long pending) {}

    public StageItemStats getItemStats(Long projectId, String stageType) {
        long success = 0;
        long failed = 0;
        long running = 0;
        long pending = 0;
        for (java.util.Map<String, Object> row : itemMapper.selectStatusCounts(projectId, stageType)) {
            int status = ((Number) row.get("status")).intValue();
            long cnt = ((Number) row.get("cnt")).longValue();
            switch (status) {
                case PipelineStageItem.STATUS_SUCCESS -> success += cnt;
                case PipelineStageItem.STATUS_FAILED -> failed += cnt;
                case PipelineStageItem.STATUS_RUNNING -> running += cnt;
                default -> pending += cnt;
            }
        }
        return new StageItemStats(success + failed + running + pending, success, failed, running + pending);
    }
}
