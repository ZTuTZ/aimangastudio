package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.model.TaskPlanUnit;
import com.aimanga.v2.repository.TaskMapper;
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
    private final TaskMapper taskMapper;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;
    private final OperationalMetrics metrics;
    private final StageItemCommitService commitService;
    private final ExportArtifactService artifactService;

    @Override public String type() { return TYPE; }

    @Override
    public void run(TaskEntity task, TaskRuntime runtime) {
        var artifact = artifactService.reserve(task);
        List<Long> projectIds = planningService.targetIds(task, PipelineStageService.STAGE_EXPORT, "PROJECT");
        runtime.begin(projectIds.size());
        stageService.markRunning(task.getProjectId(), PipelineStageService.STAGE_EXPORT);
        stageRunner.run(task.getProjectId(), PipelineStageService.STAGE_EXPORT,
                new StageRunScope("PROJECT", new java.util.LinkedHashSet<>(projectIds)), runtime,
                execution -> {
                    String result = exportProject(task, execution.planUnitId(), execution.item().getBusinessId());
                    var commit = commitService.commitFenced(execution, () -> result);
                    if (!commit.committed()) {
                        storageService.deleteStoredFile(objectMapper.readTree(result).path("url").asText(null));
                        throw new StaleCommitRejectedException(execution.item().getId());
                    }
                    return commit.resultRef();
                }, stageRunner.scriptEngine());

        List<TaskPlanUnit> units = planningService.plan(task).stream()
                .filter(unit -> PipelineStageService.STAGE_EXPORT.equals(unit.getStageType())).toList();
        publishBatchArtifact(task, runtime, units, artifact);
        stageService.refreshStageTerminalState(task.getProjectId(), PipelineStageService.STAGE_EXPORT);
    }

    private String exportProject(TaskEntity task, Long planUnitId, Long projectId) throws Exception {
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
        Path file = Files.createTempFile("aimanga-project-" + projectId + "-", ".zip");
        try {
            try (var out = Files.newOutputStream(file)) {
                publicationService.writeZip(snapshot, out);
            }
            long bytes = Files.size(file);
            metrics.addExportBytes(bytes);
            enforceQuota(bytes);
            String checksum = sha256(file);
            String url = storageService.saveFile("exports/checkpoints", task.getUserId(), file, "zip");
            return objectMapper.writeValueAsString(Map.of(
                    "projectId", projectId, "url", url, "bytes", bytes, "sha256", checksum));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private void publishBatchArtifact(TaskEntity task, TaskRuntime runtime, List<TaskPlanUnit> units,
                                      com.aimanga.v2.model.ExportArtifact artifact) {
        Path outer = null;
        try {
            outer = Files.createTempFile("aimanga-batch-" + task.getId() + "-", ".zip.tmp");
            List<Map<String, Object>> items = new ArrayList<>();
            long totalBytes = 0;
            int success = 0;
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outer))) {
                for (TaskPlanUnit unit : units) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("projectId", unit.getBusinessId());
                    if (unit.getStatus() != null && unit.getStatus() == TaskPlanUnit.STATUS_SUCCESS
                            && unit.getResultRef() != null && !unit.getResultRef().isBlank()) {
                        var result = objectMapper.readTree(unit.getResultRef());
                        Path checkpoint = Files.createTempFile("aimanga-checkpoint-", ".zip");
                        try {
                            storageService.copyStoredFile(result.path("url").asText(), checkpoint);
                            long bytes = Files.size(checkpoint);
                            totalBytes += bytes;
                            enforceQuota(totalBytes);
                            zip.putNextEntry(new ZipEntry("comic-" + unit.getBusinessId() + ".zip"));
                            Files.copy(checkpoint, zip);
                            zip.closeEntry();
                            item.put("exported", true);
                            item.put("bytes", bytes);
                            item.put("sha256", result.path("sha256").asText());
                            success++;
                        } finally {
                            Files.deleteIfExists(checkpoint);
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
            Path published = outer.resolveSibling(outer.getFileName().toString().replace(".tmp", ""));
            Files.move(outer, published, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            outer = published;
            String artifactUrl = storageService.saveFile("exports/final", task.getUserId(), outer, "zip");
            long artifactBytes = Files.size(outer);
            String artifactSha = sha256(outer);
            try {
                artifact = artifactService.activate(artifact, artifactUrl, artifactBytes, artifactSha);
            } catch (RuntimeException e) {
                storageService.deleteStoredFile(artifactUrl);
                throw e;
            }
            runtime.checkStop();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("artifactId", artifact.getId());
            result.put("downloadUrl", "/api/admin/export/artifacts/" + artifact.getId() + "/download");
            result.put("expiresAt", artifact.getExpiresAt().toString());
            result.put("bytes", artifactBytes);
            result.put("sha256", artifactSha);
            result.put("total", units.size());
            result.put("success", success);
            result.put("failed", units.size() - success);
            result.put("items", items);
            if (taskMapper.updateResultOwned(task.getId(), runtime.owner().claimToken(),
                    objectMapper.writeValueAsString(result)) != 1) {
                throw new StaleCommitRejectedException(task.getId());
            }
        } catch (RuntimeException e) {
            metrics.exportFailure();
            throw e;
        } catch (Exception e) {
            metrics.exportFailure();
            throw new BusinessException(500, "批量导出发布失败: " + e.getMessage());
        } finally {
            if (outer != null) try { Files.deleteIfExists(outer); } catch (Exception ignored) { }
        }
    }

    private void enforceQuota(long bytes) {
        long max = Math.max(10L * 1024 * 1024,
                configService.getLong("export_max_bytes", 1_073_741_824L));
        if (bytes > max) throw new BusinessException(400, "导出大小超过限制: " + max + " bytes");
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream raw = Files.newInputStream(file); DigestInputStream in = new DigestInputStream(raw, digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
