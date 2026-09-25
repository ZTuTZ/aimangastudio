package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.model.TaskPlanUnit;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.service.TaskPlanningService;
import com.aimanga.v2.storage.StorageService;
import com.aimanga.v2.task.TaskHandler;
import com.aimanga.v2.task.TaskRuntime;
import com.aimanga.v2.task.OperationalMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Asynchronous, resumable batch publication export. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportTaskHandler implements TaskHandler {

    public static final String TYPE = "EXPORT";

    private final TaskPlanningService planningService;
    private final PipelineStageService stageService;
    private final ConcurrentStageRunner stageRunner;
    private final PublicationService publicationService;
    private final StorageService storageService;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;
    private final OperationalMetrics metrics;
    private final StageItemCommitService commitService;
    private final ExportArtifactService artifactService;
    private final ExportObjectService objectService;
    private final ExportTempFiles tempFiles;

    @Override public String type() { return TYPE; }

    @Override
    public void run(TaskEntity task, TaskRuntime runtime) {
        ExportArtifactService.ExportAttempt attempt = artifactService.beginAttempt(runtime.owner());
        List<Long> projectIds = planningService.targetIds(task, PipelineStageService.STAGE_EXPORT, "PROJECT");
        runtime.begin(projectIds.size());
        stageService.markRunning(task.getProjectId(), PipelineStageService.STAGE_EXPORT);
        stageRunner.run(task.getProjectId(), PipelineStageService.STAGE_EXPORT,
                new StageRunScope("PROJECT", new java.util.LinkedHashSet<>(projectIds)), runtime,
                execution -> {
                    String result = exportProject(task, attempt, execution.planUnitId(), execution.item().getBusinessId());
                    var commit = commitService.commitFenced(execution, () -> result);
                    if (!commit.committed()) {
                        long objectId = objectMapper.readTree(result).path("objectId").asLong(0);
                        if (objectId > 0) objectService.requestDeletion(objectId, "阶段提交所有权已失效", 0);
                        throw new StaleCommitRejectedException(execution.item().getId());
                    }
                    return commit.resultRef();
                }, stageRunner.scriptEngine());

        List<TaskPlanUnit> units = planningService.plan(task).stream()
                .filter(unit -> PipelineStageService.STAGE_EXPORT.equals(unit.getStageType())).toList();
        publishBatchArtifact(task, runtime, units, attempt);
        stageService.refreshStageTerminalState(task.getProjectId(), PipelineStageService.STAGE_EXPORT);
    }

    private String exportProject(TaskEntity task, ExportArtifactService.ExportAttempt attempt,
                                 Long planUnitId, Long projectId) throws Exception {
        TaskPlanUnit unit = planningService.requireUnit(planUnitId);
        var input = objectMapper.readTree(unit.getInputSnapshot());
        if (input.hasNonNull("planningError")) {
            throw new BusinessException(400, input.path("planningError").asText());
        }
        var snapshotNode = input.path("publicationSnapshot");
        String expectedDigest = input.path("snapshotSha256").asText("");
        if (snapshotNode.isMissingNode() || snapshotNode.isNull()
                || !expectedDigest.equals(JsonDigest.sha256(snapshotNode))) {
            throw new BusinessException(409, "导出发布快照缺失或校验失败");
        }
        var snapshot = objectMapper.treeToValue(snapshotNode, com.aimanga.v2.dto.export.ComicManifest.class);
        try (var temp = tempFiles.create("project-" + projectId + "-", ".zip")) {
            Path file = temp.path();
            try (var out = tempFiles.limited(tempFiles.open(temp), exportMaxBytes())) {
                publicationService.writeZip(snapshot, out);
            }
            long bytes = Files.size(file);
            metrics.addExportBytes(bytes);
            enforceQuota(bytes);
            String checksum = sha256(file);
            var object = objectService.registerUpload(task.getId(), planUnitId, null, attempt.generation(),
                    com.aimanga.v2.model.ExportStoredObject.KIND_CHECKPOINT,
                    attempt.claimToken(), task.getUserId());
            objectService.upload(object, file, bytes, checksum);
            return objectMapper.writeValueAsString(Map.of(
                    "projectId", projectId, "objectId", object.getId(), "url", object.getStorageUrl(),
                    "bytes", bytes, "sha256", checksum));
        }
    }

    private void publishBatchArtifact(TaskEntity task, TaskRuntime runtime, List<TaskPlanUnit> units,
                                      ExportArtifactService.ExportAttempt attempt) {
        ExportTempFiles.Handle outerTemp = null;
        com.aimanga.v2.model.ExportStoredObject finalObject = null;
        try {
            outerTemp = tempFiles.create("batch-" + task.getId() + "-", ".zip");
            Path outer = outerTemp.path();
            List<Map<String, Object>> items = new ArrayList<>();
            long totalBytes = 0;
            int success = 0;
            long exportLimit = exportMaxBytes();
            try (ZipOutputStream zip = new ZipOutputStream(tempFiles.limited(tempFiles.open(outerTemp), exportLimit))) {
                for (TaskPlanUnit unit : units) {
                    runtime.checkStop();
                    runtime.checkPauseRequested();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("projectId", unit.getBusinessId());
                    if (unit.getStatus() != null && unit.getStatus() == TaskPlanUnit.STATUS_SUCCESS
                            && unit.getResultRef() != null && !unit.getResultRef().isBlank()) {
                        try (var checkpointTemp = tempFiles.create("checkpoint-", ".zip")) {
                            Path checkpoint = checkpointTemp.path();
                            var result = objectMapper.readTree(unit.getResultRef());
                            boolean checkpointReady = true;
                            try {
                                try {
                                    loadVerifiedCheckpoint(result, checkpointTemp);
                                } catch (Exception firstFailure) {
                                    long oldObjectId = result.path("objectId").asLong(0);
                                    String rebuilt = exportProject(task, attempt, unit.getId(), unit.getBusinessId());
                                    planningService.replaceSuccessfulResult(unit.getId(), rebuilt);
                                    result = objectMapper.readTree(rebuilt);
                                    loadVerifiedCheckpoint(result, checkpointTemp);
                                    if (oldObjectId > 0) {
                                        objectService.requestDeletion(oldObjectId, "检查点校验失败后已重建", 0);
                                    }
                                }
                            } catch (BusinessException e) {
                                checkpointReady = false;
                                item.put("exported", false);
                                item.put("error", e.getMessage());
                            }
                            if (checkpointReady) {
                                long bytes = Files.size(checkpoint);
                                enforceQuota(totalBytes + bytes);
                                zip.putNextEntry(new ZipEntry("comic-" + unit.getBusinessId() + ".zip"));
                                Files.copy(checkpoint, zip);
                                zip.closeEntry();
                                totalBytes += bytes;
                                item.put("exported", true);
                                item.put("bytes", bytes);
                                item.put("sha256", result.path("sha256").asText());
                                success++;
                            }
                        }
                    } else {
                        item.put("exported", false);
                        item.put("error", unit.getErrorMessage() == null ? "导出失败" : unit.getErrorMessage());
                    }
                    items.add(item);
                }
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("total", units.size());
                summary.put("success", success);
                summary.put("failed", units.size() - success);
                summary.put("items", items);
                summary.put("exportedAt", LocalDateTime.now().toString());
                zip.putNextEntry(new ZipEntry("summary.json"));
                zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary));
                zip.closeEntry();
            }
            // Final task counters must describe the archive that was actually assembled, including
            // checkpoint validation/rebuild failures discovered after the stage runner completed.
            runtime.restore(units.size(), success, units.size() - success);
            long artifactBytes = Files.size(outer);
            String artifactSha = sha256(outer);
            finalObject = objectService.registerUpload(task.getId(), null, attempt.artifactId(),
                    attempt.generation(), com.aimanga.v2.model.ExportStoredObject.KIND_FINAL,
                    attempt.claimToken(), task.getUserId());
            objectService.upload(finalObject, outer, artifactBytes, artifactSha);
            runtime.checkStop();
            runtime.checkPauseRequested();
            var result = objectMapper.createObjectNode();
            result.put("total", units.size());
            result.put("success", success);
            result.put("failed", units.size() - success);
            result.set("items", objectMapper.valueToTree(items));
            try {
                artifactService.publish(attempt, finalObject.getId(), result);
            } catch (RuntimeException e) {
                objectService.requestDeletion(finalObject.getId(), "最终发布失败或所有权已失效", 0);
                throw e;
            }
        } catch (RuntimeException e) {
            metrics.exportFailure();
            throw e;
        } catch (Exception e) {
            metrics.exportFailure();
            if (e.getMessage() != null && e.getMessage().contains("导出大小超过限制")) {
                throw new BusinessException(400, e.getMessage());
            }
            if (e.getMessage() != null && e.getMessage().contains("导出临时磁盘预算不足")) {
                throw new BusinessException(503, e.getMessage() + "，请稍后重试或联系管理员调整预算");
            }
            throw new BusinessException(500, "批量导出发布失败: " + e.getMessage());
        } finally {
            if (outerTemp != null) outerTemp.close();
        }
    }

    private void enforceQuota(long bytes) {
        long max = exportMaxBytes();
        if (bytes > max) throw new BusinessException(400, "导出大小超过限制: " + max + " bytes");
    }

    private long exportMaxBytes() {
        return Math.max(10L * 1024 * 1024,
                configService.getLong("export_max_bytes", 1_073_741_824L));
    }

    private void loadVerifiedCheckpoint(com.fasterxml.jackson.databind.JsonNode result,
                                        ExportTempFiles.Handle checkpointTemp) throws Exception {
        Path checkpoint = checkpointTemp.path();
        long checkpointObjectId = result.path("objectId").asLong(0);
        try (var out = tempFiles.limited(tempFiles.open(checkpointTemp), exportMaxBytes())) {
            if (checkpointObjectId > 0) objectService.writeTo(checkpointObjectId, out);
            else storageService.writeStoredFile(result.path("url").asText(), out);
        }
        long actualBytes = Files.size(checkpoint);
        String actualSha = sha256(checkpoint);
        long expectedBytes = result.path("bytes").asLong(-1);
        String expectedSha = result.path("sha256").asText("");
        if (expectedBytes != actualBytes || !expectedSha.equalsIgnoreCase(actualSha)) {
            throw new BusinessException(409, "导出检查点完整性校验失败");
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream raw = Files.newInputStream(file); DigestInputStream in = new DigestInputStream(raw, digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
