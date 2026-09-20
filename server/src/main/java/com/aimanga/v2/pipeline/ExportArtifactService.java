package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.ExportArtifact;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.ExportArtifactMapper;
import com.aimanga.v2.repository.TaskMapper;
import com.aimanga.v2.repository.TaskPlanUnitMapper;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.storage.StorageService;
import com.aimanga.v2.task.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.time.LocalDateTime;

/** Owns export artifact authorization, expiry and storage cleanup. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportArtifactService {

    private final ExportArtifactMapper artifactMapper;
    private final TaskMapper taskMapper;
    private final TaskPlanUnitMapper planUnitMapper;
    private final StorageService storageService;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    public ExportArtifact reserve(TaskEntity task) {
        ExportArtifact existing = artifactMapper.selectByTaskId(task.getId());
        if (existing != null) return existing;
        ExportArtifact artifact = new ExportArtifact();
        artifact.setTaskId(task.getId());
        artifact.setUserId(task.getUserId());
        artifact.setFileName("aimanga-export-" + task.getId() + ".zip");
        artifact.setStatus(ExportArtifact.STATUS_BUILDING);
        artifact.setCreateTime(LocalDateTime.now());
        try {
            artifactMapper.insert(artifact);
            return artifact;
        } catch (DuplicateKeyException ignored) {
            return artifactMapper.selectByTaskId(task.getId());
        }
    }

    public ExportArtifact activate(ExportArtifact artifact, String storageUrl, long bytes, String sha256) {
        if (artifact.getStatus() != null && artifact.getStatus() == ExportArtifact.STATUS_ACTIVE
                && artifact.getStorageUrl() != null) {
            storageService.deleteStoredFile(storageUrl);
            return artifact;
        }
        int ttlHours = Math.max(1, configService.getInt("export_artifact_ttl_hours", 168));
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(ttlHours);
        if (artifactMapper.activate(artifact.getId(), storageUrl, bytes, sha256, expiresAt) != 1) {
            ExportArtifact latest = artifactMapper.selectById(artifact.getId());
            if (latest != null && latest.getStatus() != null
                    && latest.getStatus() == ExportArtifact.STATUS_ACTIVE && latest.getStorageUrl() != null) {
                storageService.deleteStoredFile(storageUrl);
                return latest;
            }
            throw new BusinessException(409, "导出产物状态已变化");
        }
        artifact.setStorageUrl(storageUrl);
        artifact.setByteSize(bytes);
        artifact.setSha256(sha256);
        artifact.setStatus(ExportArtifact.STATUS_ACTIVE);
        artifact.setExpiresAt(expiresAt);
        return artifact;
    }

    public ExportArtifact requireDownloadable(Long artifactId) {
        ExportArtifact artifact = artifactMapper.selectById(artifactId);
        if (artifact == null || artifact.getStatus() == null
                || artifact.getStatus() != ExportArtifact.STATUS_ACTIVE
                || artifact.getStorageUrl() == null || artifact.getByteSize() == null) {
            throw new BusinessException(404, "导出产物不存在或已清理");
        }
        TaskEntity task = taskMapper.selectById(artifact.getTaskId());
        int status = task == null || task.getStatus() == null ? -1 : task.getStatus();
        if (task == null || !"EXPORT".equals(task.getTaskType())
                || (status != TaskStatus.SUCCESS && status != TaskStatus.PARTIAL)) {
            throw new BusinessException(409, "导出任务尚未成功完成");
        }
        if (artifact.getExpiresAt() == null || !artifact.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(410, "导出产物已过期");
        }
        return artifact;
    }

    public void stream(ExportArtifact artifact, OutputStream output) {
        storageService.writeStoredFile(artifact.getStorageUrl(), output);
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void cleanupExpiredArtifacts() {
        for (ExportArtifact artifact : artifactMapper.selectCleanupCandidates()) {
            try {
                storageService.deleteStoredFile(artifact.getStorageUrl());
                for (String resultRef : planUnitMapper.selectExportCheckpointResults(artifact.getTaskId())) {
                    String checkpointUrl = objectMapper.readTree(resultRef).path("url").asText(null);
                    storageService.deleteStoredFile(checkpointUrl);
                }
                artifactMapper.markExpired(artifact.getId());
                log.info("[export] 已清理导出产物 artifactId={} taskId={}", artifact.getId(), artifact.getTaskId());
            } catch (Exception e) {
                log.warn("[export] 清理导出产物失败，稍后重试 artifactId={} taskId={}",
                        artifact.getId(), artifact.getTaskId(), e);
            }
        }
    }
}
