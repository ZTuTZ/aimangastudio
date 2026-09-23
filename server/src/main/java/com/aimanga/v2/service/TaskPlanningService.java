package com.aimanga.v2.service;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.Asset;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.model.TaskPlanUnit;
import com.aimanga.v2.pipeline.PipelineStageService;
import com.aimanga.v2.pipeline.ContentVersionConflictException;
import com.aimanga.v2.pipeline.PublicationService;
import com.aimanga.v2.pipeline.JsonDigest;
import com.aimanga.v2.dto.export.ComicManifest;
import com.aimanga.v2.repository.AssetMapper;
import com.aimanga.v2.repository.ChapterMapper;
import com.aimanga.v2.repository.PageMapper;
import com.aimanga.v2.repository.TaskMapper;
import com.aimanga.v2.repository.TaskPlanUnitMapper;
import com.aimanga.v2.repository.ExportStoredObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Creates the immutable target set used by retries and resume. */
@Service
@RequiredArgsConstructor
public class TaskPlanningService {

    private final TaskPlanUnitMapper unitMapper;
    private final TaskMapper taskMapper;
    private final PageMapper pageMapper;
    private final ChapterMapper chapterMapper;
    private final AssetMapper assetMapper;
    private final PipelineStageService stageService;
    private final ObjectMapper objectMapper;
    private final ConfigService configService;
    private final PublicationService publicationService;
    private final ExportStoredObjectMapper exportStoredObjectMapper;

    @Transactional
    public List<TaskPlanUnit> initializePlan(TaskEntity task) {
        TaskEntity latest = taskMapper.selectById(task.getId());
        if (latest != null && latest.getPlanInitializedAt() != null) {
            return unitMapper.selectPlan(task.getId(), effectiveVersion(latest));
        }
        int planVersion = effectiveVersion(task);
        task.setPlanVersion(planVersion);
        List<TaskPlanUnit> units = buildUnits(task);
        if ("SCRIPT".equals(task.getTaskType())) {
            for (TaskPlanUnit unit : units) {
                List<Long> pageIds = pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                                .eq(PageEntity::getChapterId, unit.getBusinessId()))
                        .stream().map(PageEntity::getId).toList();
                if (!pageIds.isEmpty() && unitMapper.countActivePageOwners(
                        task.getId(), task.getProjectId(), pageIds) > 0) {
                    throw new BusinessException(409, "本话仍有页面生成任务正在执行或暂停,请先停止后再重生成脚本");
                }
            }
        }
        for (TaskPlanUnit unit : units) {
            if ("EXPORT".equals(unit.getStageType()) && unitMapper.countActiveExportOwner(
                    task.getId(), unit.getBusinessId()) > 0) {
                throw new BusinessException(409, "作品正在由其他导出任务处理: " + unit.getBusinessId());
            }
            if (!"PROJECT".equals(unit.getBusinessType()) && unitMapper.countActiveOwner(
                    task.getId(), task.getProjectId(), unit.getStageType(), unit.getBusinessType(),
                    unit.getBusinessId()) > 0) {
                throw new BusinessException(409, "目标正在由其他任务处理: " + unit.getStageType()
                        + "/" + unit.getBusinessType() + "/" + unit.getBusinessId());
            }
        }
        for (TaskPlanUnit unit : units) {
            try {
                unitMapper.insert(unit);
            } catch (DuplicateKeyException ignored) {
                // The unique business key makes initialization idempotent after a retry/crash.
            }
        }
        initializeStageItems(units);
        unitMapper.inheritSuccessfulStageItems(task.getId(), planVersion);

        ObjectNode snapshot = parseObject(task.getPayload());
        snapshot.put("planVersion", planVersion);
        snapshot.put("targetCount", units.size());
        LocalDateTime initializedAt = LocalDateTime.now();
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<TaskEntity>()
                .eq(TaskEntity::getId, task.getId())
                .isNull(TaskEntity::getPlanInitializedAt)
                .set(TaskEntity::getPlanVersion, planVersion)
                .set(TaskEntity::getPlanSnapshot, snapshot.toString())
                .set(TaskEntity::getPlanInitializedAt, initializedAt));
        if (updated == 0) {
            return unitMapper.selectPlan(task.getId(), planVersion);
        }
        task.setPlanSnapshot(snapshot.toString());
        task.setPlanInitializedAt(initializedAt);
        return unitMapper.selectPlan(task.getId(), planVersion);
    }

    /** Package-visible so target derivation can be tested without mutating persistence. */
    List<TaskPlanUnit> buildUnits(TaskEntity task) {
        JsonNode payload = parseObject(task.getPayload());
        int version = effectiveVersion(task);
        String type = task.getTaskType();
        List<TaskPlanUnit> units = new ArrayList<>();
        switch (type) {
            case "PAGE" -> addPageUnit(units, task, version, "IMAGE", requiredPage(task, payload), true, payload);
            case "LAYOUT" -> targetPages(task, payload).stream()
                    .filter(page -> payload.has("pageId") || staleLayout(page))
                    .forEach(page -> addPageUnit(units, task, version, "LAYOUT", page,
                            payload.has("pageId") || payload.path("force").asBoolean(false), payload));
            case "COLORIZE", "CLEAN", "REPAINT" ->
                    addPageUnit(units, task, version, type, requiredPage(task, payload), true, payload);
            case "BATCH" -> {
                List<PageEntity> pages = targetPages(task, payload);
                boolean direct = configService.getInt("page_direct_output", 0) == 1;
                boolean forceLayout = payload.path("forceLayout").asBoolean(false);
                boolean forceImage = payload.path("forceImage").asBoolean(false);
                boolean skipGenerated = payload.path("skipGenerated").asBoolean(true);
                if (!direct) pages.stream().filter(page -> forceLayout || staleLayout(page)).forEach(page ->
                        addPageUnit(units, task, version, "LAYOUT", page, forceLayout, payload));
                pages.stream().filter(page -> forceImage || !skipGenerated || staleImage(page)).forEach(page ->
                        addPageUnit(units, task, version, "IMAGE", page, forceImage || !skipGenerated, payload));
            }
            case "SCRIPT" -> targetChapters(task, payload).stream()
                    .filter(chapter -> payload.path("forceScript").asBoolean(false)
                            || chapter.getStatus() == null || chapter.getStatus() < Chapter.STATUS_SCRIPT_READY)
                    .forEach(chapter ->
                    units.add(unit(task, version, "SCRIPT", "CHAPTER", chapter.getId(), payload,
                            payload.path("forceScript").asBoolean(false), null, true)));
            case "SHEET", "ASSET_REF" -> {
                String stage = "SHEET".equals(type) ? "SHEET" : "REFERENCE";
                boolean selected = !longArray(payload, "assetIds").isEmpty();
                targetAssets(task, payload).stream()
                        .filter(asset -> selected || ("SHEET".equals(type)
                                ? blank(asset.getSheetImageUrl()) : blank(asset.getReferenceUrl())))
                        .forEach(asset -> units.add(
                                unit(task, version, stage, "ASSET", asset.getId(), payload, selected, null, selected)));
            }
            case "SPLIT", "ASSET" -> units.add(
                    unit(task, version, type, "PROJECT", task.getProjectId(), payload, false, null));
            case "EXPORT" -> {
                List<Long> projectIds = longArray(payload, "projectIds");
                int max = Math.max(1, configService.getInt("export_max_projects", 50));
                if (projectIds.isEmpty()) throw new BusinessException(400, "导出任务缺少 projectIds");
                if (projectIds.size() > max) throw new BusinessException(400, "单次最多导出 " + max + " 部作品");
                projectIds.forEach(projectId -> {
                    ObjectNode exportInput = payload.deepCopy();
                    try {
                        ComicManifest snapshot = publicationService.buildManifest(projectId);
                        JsonNode snapshotNode = objectMapper.valueToTree(snapshot);
                        exportInput.set("publicationSnapshot", snapshotNode);
                        exportInput.put("snapshotSha256", JsonDigest.sha256(snapshotNode));
                    } catch (Exception e) {
                        exportInput.put("planningError", e.getMessage() == null
                                ? e.getClass().getSimpleName() : e.getMessage());
                    }
                    // A new task owns a new publication snapshot; never inherit another task's checkpoint.
                    units.add(unit(task, version, "EXPORT", "PROJECT", projectId, exportInput, true, null));
                });
            }
            case "MOCK" -> { }
            default -> throw new BusinessException(400, "不支持的任务计划类型: " + type);
        }
        return units;
    }

    public List<Long> targetIds(TaskEntity task, String stageType, String businessType) {
        return unitMapper.selectBusinessIds(task.getId(), effectiveVersion(task), stageType, businessType);
    }

    public boolean hasPlan(TaskEntity task) {
        return task != null && task.getPlanInitializedAt() != null
                && task.getPlanVersion() != null && task.getPlanVersion() > 0;
    }

    public PlanStats stats(TaskEntity task) {
        int version = effectiveVersion(task);
        int pending = unitMapper.countByStatus(task.getId(), version, TaskPlanUnit.STATUS_PENDING);
        int running = unitMapper.countByStatus(task.getId(), version, TaskPlanUnit.STATUS_RUNNING);
        int success = unitMapper.countByStatus(task.getId(), version, TaskPlanUnit.STATUS_SUCCESS);
        int failed = unitMapper.countByStatus(task.getId(), version, TaskPlanUnit.STATUS_FAILED);
        return new PlanStats(pending + running + success + failed, success, failed, pending + running);
    }

    public record PlanStats(int total, int success, int failed, int pending) { }

    public PlannedPageInput plannedPageInput(Long planUnitId, PageEntity fallback) {
        if (planUnitId == null) {
            return new PlannedPageInput(orOne(fallback.getScriptVersion()), orZero(fallback.getImageRevision()));
        }
        TaskPlanUnit unit = unitMapper.selectById(planUnitId);
        if (unit == null) throw new BusinessException(409, "任务计划单元已失效");
        return new PlannedPageInput(unit.getSourceScriptVersion() == null
                ? orOne(fallback.getScriptVersion()) : unit.getSourceScriptVersion(),
                unit.getSourceRevision() == null ? orZero(fallback.getImageRevision()) : unit.getSourceRevision());
    }

    public record PlannedPageInput(int scriptVersion, long imageRevision) { }

    public PlannedPageInput requireCurrentPageInput(Long planUnitId, PageEntity page, boolean requireImageRevision) {
        PlannedPageInput input = plannedPageInput(planUnitId, page);
        if (orOne(page.getScriptVersion()) != input.scriptVersion()
                || (requireImageRevision && orZero(page.getImageRevision()) != input.imageRevision())) {
            throw new ContentVersionConflictException("页面内容版本已变化,请重新发起任务");
        }
        return input;
    }

    public int reopenFailedForManualRetry(TaskEntity task) {
        return unitMapper.reopenFailedForManualRetry(task.getId(), effectiveVersion(task));
    }

    public int reopenMissingExportCheckpoints(TaskEntity task) {
        if (!"EXPORT".equals(task.getTaskType())) return 0;
        int reopened = 0;
        for (TaskPlanUnit unit : unitMapper.selectPlan(task.getId(), effectiveVersion(task))) {
            if (unit.getStatus() == null || unit.getStatus() != TaskPlanUnit.STATUS_SUCCESS
                    || unit.getResultRef() == null) continue;
            boolean missing;
            try {
                long objectId = objectMapper.readTree(unit.getResultRef()).path("objectId").asLong(0);
                missing = objectId > 0 && exportObjectMissing(objectId);
            } catch (Exception ignored) {
                missing = true;
            }
            if (missing) {
                int changed = unitMapper.reopenSuccessfulUnit(unit.getId());
                if (changed == 1) {
                    stageService.forceResetItemsByBusiness(task.getProjectId(), "EXPORT", "PROJECT",
                            List.of(unit.getBusinessId()));
                    reopened++;
                }
            }
        }
        return reopened;
    }

    private boolean exportObjectMissing(long objectId) {
        var object = exportStoredObjectMapper.selectById(objectId);
        return object == null || !com.aimanga.v2.model.ExportStoredObject.STATE_RETAINED.equals(object.getState());
    }

    public List<TaskPlanUnit> plan(TaskEntity task) {
        return unitMapper.selectPlan(task.getId(), effectiveVersion(task));
    }

    public void replaceSuccessfulResult(Long unitId, String resultRef) {
        if (unitMapper.replaceSuccessfulResult(unitId, resultRef) != 1) {
            throw new BusinessException(409, "导出检查点已被其他执行者修改");
        }
    }

    public TaskPlanUnit requireUnit(Long unitId) {
        TaskPlanUnit unit = unitId == null ? null : unitMapper.selectById(unitId);
        if (unit == null) throw new BusinessException(409, "任务计划单元已失效");
        return unit;
    }

    private void initializeStageItems(List<TaskPlanUnit> units) {
        Map<String, List<TaskPlanUnit>> groups = units.stream()
                .filter(unit -> !"PROJECT".equals(unit.getBusinessType()) || "EXPORT".equals(unit.getStageType()))
                .collect(Collectors.groupingBy(unit -> unit.getStageType() + "\u0000" + unit.getBusinessType()));
        for (List<TaskPlanUnit> group : groups.values()) {
            TaskPlanUnit first = group.get(0);
            List<Long> ids = group.stream().map(TaskPlanUnit::getBusinessId).toList();
            Long projectId = projectIdOf(first.getTaskId());
            stageService.createItems(projectId, first.getStageType(), first.getBusinessType(), ids);
            List<Long> forced = group.stream().filter(this::forceRequested).map(TaskPlanUnit::getBusinessId).toList();
            if (!forced.isEmpty()) {
                stageService.forceResetItemsByBusiness(projectId, first.getStageType(), first.getBusinessType(), forced);
            }
            List<Long> normal = ids.stream().filter(id -> !forced.contains(id)).toList();
            List<Long> resetSuccess = group.stream().filter(this::resetSuccessRequested)
                    .map(TaskPlanUnit::getBusinessId).filter(id -> !forced.contains(id)).toList();
            if (!resetSuccess.isEmpty()) {
                stageService.resetSuccessfulItemsByBusiness(projectId, first.getStageType(), first.getBusinessType(), resetSuccess);
            }
            if (!normal.isEmpty()) {
                stageService.resetFailedItemsByBusiness(projectId, first.getStageType(), first.getBusinessType(), normal);
            }
        }
    }

    private Long projectIdOf(Long taskId) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) throw new BusinessException(404, "任务不存在: " + taskId);
        return task.getProjectId();
    }

    private boolean forceRequested(TaskPlanUnit unit) {
        try {
            return objectMapper.readTree(unit.getInputSnapshot()).path("force").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean resetSuccessRequested(TaskPlanUnit unit) {
        try {
            return objectMapper.readTree(unit.getInputSnapshot()).path("resetSuccess").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private void addPageUnit(List<TaskPlanUnit> units, TaskEntity task, int version, String stage,
                             PageEntity page, boolean force, JsonNode payload) {
        TaskPlanUnit unit = unit(task, version, stage, "PAGE", page.getId(), payload, force,
                page.getScriptVersion() == null ? 1 : page.getScriptVersion(),
                force || ("LAYOUT".equals(stage) ? staleLayout(page) : staleImage(page)));
        unit.setSourceScriptVersion(orOne(page.getScriptVersion()));
        unit.setSourceRevision(orZero(page.getImageRevision()));
        units.add(unit);
    }

    private TaskPlanUnit unit(TaskEntity task, int version, String stage, String businessType, Long businessId,
                              JsonNode payload, boolean force, Integer scriptVersion) {
        return unit(task, version, stage, businessType, businessId, payload, force, scriptVersion, force);
    }

    private TaskPlanUnit unit(TaskEntity task, int version, String stage, String businessType, Long businessId,
                              JsonNode payload, boolean force, Integer scriptVersion, boolean resetSuccess) {
        ObjectNode snapshot = payload != null && payload.isObject()
                ? ((ObjectNode) payload).deepCopy() : objectMapper.createObjectNode();
        snapshot.put("force", force);
        snapshot.put("resetSuccess", resetSuccess);
        if (scriptVersion != null) snapshot.put("scriptVersion", scriptVersion);
        TaskPlanUnit unit = new TaskPlanUnit();
        unit.setTaskId(task.getId());
        unit.setPlanVersion(version);
        unit.setStageType(stage);
        unit.setBusinessType(businessType);
        unit.setBusinessId(businessId);
        unit.setInputSnapshot(snapshot.toString());
        unit.setStatus(TaskPlanUnit.STATUS_PENDING);
        unit.setRetryCount(0);
        unit.setErrorMessage("");
        unit.setCreateTime(LocalDateTime.now());
        return unit;
    }

    private PageEntity requiredPage(TaskEntity task, JsonNode payload) {
        Long pageId = longValue(payload, "pageId");
        if (pageId == null) throw new BusinessException(400, task.getTaskType() + " 任务缺少 pageId");
        PageEntity page = pageMapper.selectById(pageId);
        if (page == null || !task.getProjectId().equals(page.getProjectId())) {
            throw new BusinessException(400, "页面不属于当前作品");
        }
        return page;
    }

    private List<PageEntity> targetPages(TaskEntity task, JsonNode payload) {
        if (longValue(payload, "pageId") != null) return List.of(requiredPage(task, payload));
        Long chapterId = longValue(payload, "chapterId");
        List<Long> chapterIds = longArray(payload, "chapterIds");
        List<PageEntity> pages = pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                .eq(PageEntity::getProjectId, task.getProjectId())
                .eq(chapterId != null, PageEntity::getChapterId, chapterId)
                .in(!chapterIds.isEmpty(), PageEntity::getChapterId, chapterIds)
                .orderByAsc(PageEntity::getChapterId).orderByAsc(PageEntity::getPageNo));
        return pages == null ? List.of() : pages;
    }

    private List<Chapter> targetChapters(TaskEntity task, JsonNode payload) {
        Long chapterId = task.getChapterId() != null ? task.getChapterId() : longValue(payload, "chapterId");
        List<Chapter> chapters = chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getProjectId, task.getProjectId())
                .eq(chapterId != null, Chapter::getId, chapterId)
                .orderByAsc(Chapter::getChapterNo));
        return chapters == null ? List.of() : chapters;
    }

    private List<Asset> targetAssets(TaskEntity task, JsonNode payload) {
        List<Long> ids = longArray(payload, "assetIds");
        List<Asset> assets = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, task.getProjectId())
                .in(!ids.isEmpty(), Asset::getId, ids)
                .orderByAsc(Asset::getId));
        return assets == null ? List.of() : assets;
    }

    private ObjectNode parseObject(String json) {
        try {
            JsonNode node = json == null || json.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(json);
            if (!node.isObject()) throw new BusinessException(400, "任务 payload 必须是 JSON 对象");
            return (ObjectNode) node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "任务 payload 不是合法 JSON");
        }
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.canConvertToLong() ? value.asLong() : null;
    }

    private static List<Long> longArray(JsonNode node, String field) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        JsonNode values = node.path(field);
        if (values.isArray()) values.forEach(value -> { if (value.canConvertToLong()) ids.add(value.asLong()); });
        return List.copyOf(ids);
    }

    private static int effectiveVersion(TaskEntity task) {
        return task.getPlanVersion() == null || task.getPlanVersion() < 1 ? 1 : task.getPlanVersion();
    }

    private static boolean staleLayout(PageEntity page) {
        int scriptVersion = page.getScriptVersion() == null ? 1 : page.getScriptVersion();
        return blank(page.getLayoutImageUrl()) || page.getLayoutScriptVersion() == null
                || page.getLayoutScriptVersion() < scriptVersion;
    }

    private static boolean staleImage(PageEntity page) {
        int scriptVersion = page.getScriptVersion() == null ? 1 : page.getScriptVersion();
        return blank(page.getGeneratedImageUrl()) || page.getImageScriptVersion() == null
                || page.getImageScriptVersion() < scriptVersion;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static int orOne(Integer value) { return value == null ? 1 : value; }

    private static long orZero(Long value) { return value == null ? 0L : value; }
}
