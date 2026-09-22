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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    @Transactional
    public ExportArtifact activate(ExportArtifact artifact, String storageUrl, long bytes, String sha256) {
        ExportArtifact latest = artifactMapper.selectById(artifact.getId());
        String previousUrl = latest == null ? artifact.getStorageUrl() : latest.getStorageUrl();
        int ttlHours = Math.max(1, configService.getInt("export_artifact_ttl_hours", 168));
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(ttlHours);
        if (artifactMapper.activate(artifact.getId(), storageUrl, bytes, sha256, expiresAt) != 1) {
            throw new BusinessException(409, "导出产物状态已变化");
        }
        artifact.setStorageUrl(storageUrl);
        artifact.setByteSize(bytes);
        artifact.setSha256(sha256);
        artifact.setStatus(ExportArtifact.STATUS_ACTIVE);
        artifact.setExpiresAt(expiresAt);
        if (previousUrl != null && !previousUrl.isBlank() && !previousUrl.equals(storageUrl)) {
            afterCommit(() -> safeDelete(previousUrl));
        }
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
                if (artifactMapper.markExpired(artifact.getId(), artifact.getStorageUrl()) == 1) {
                    log.info("[export] 已清理导出产物 artifactId={} taskId={}",
                            artifact.getId(), artifact.getTaskId());
                } else {
                    log.info("[export] 产物已被重试替换，跳过过期标记 artifactId={} taskId={}",
                            artifact.getId(), artifact.getTaskId());
                }
            } catch (Exception e) {
                log.warn("[export] 清理导出产物失败，稍后重试 artifactId={} taskId={}",
                        artifact.getId(), artifact.getTaskId(), e);
            }
        }
    }

    private void safeDelete(String storageUrl) {
        try {
            storageService.deleteStoredFile(storageUrl);
        } catch (RuntimeException e) {
            log.warn("[export] 新产物已发布，但旧文件清理失败 url={}", storageUrl, e);
        }
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
