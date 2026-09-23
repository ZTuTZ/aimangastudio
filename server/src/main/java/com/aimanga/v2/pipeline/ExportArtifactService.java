package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.ExportArtifact;
import com.aimanga.v2.model.ExportStoredObject;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.ExportArtifactMapper;
import com.aimanga.v2.repository.ExportStoredObjectMapper;
import com.aimanga.v2.repository.TaskMapper;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.task.TaskExecutionOwner;
import com.aimanga.v2.task.TaskStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/** Generation-fenced publication and authorization for final export artifacts. */
@Service
@RequiredArgsConstructor
public class ExportArtifactService {
    private final ExportArtifactMapper artifactMapper;
    private final ExportStoredObjectMapper objectMapper;
    private final TaskMapper taskMapper;
    private final ExportObjectService objectService;
    private final ConfigService configService;
    private final PlatformTransactionManager transactionManager;

    public record ExportAttempt(Long artifactId, Long taskId, long generation, String claimToken) { }
    public record Download(ExportArtifact artifact, ExportStoredObject object) { }

    @Transactional
    public ExportAttempt beginAttempt(TaskExecutionOwner owner) {
        TaskEntity task = taskMapper.lockActiveClaim(owner.taskId(), owner.claimToken());
        if (task == null || !"EXPORT".equals(task.getTaskType())) {
            throw new BusinessException(409, "导出任务所有权已失效");
        }
        ExportArtifact artifact = artifactMapper.lockByTaskId(task.getId());
        if (artifact == null) {
            artifact = new ExportArtifact();
            artifact.setTaskId(task.getId());
            artifact.setUserId(task.getUserId());
            artifact.setFileName("aimanga-export-" + task.getId() + ".zip");
            artifact.setStatus(ExportArtifact.STATUS_BUILDING);
            artifact.setGeneration(1L);
            artifact.setPublisherToken(owner.claimToken());
            artifact.setCreateTime(LocalDateTime.now());
            artifactMapper.insert(artifact);
        } else if (!owner.claimToken().equals(artifact.getPublisherToken())) {
            if (artifactMapper.beginGeneration(artifact.getId(), owner.claimToken()) != 1) {
                throw new BusinessException(409, "导出发布代次已变化");
            }
            artifact = artifactMapper.lockById(artifact.getId());
        }
        return new ExportAttempt(artifact.getId(), task.getId(), artifact.getGeneration(), owner.claimToken());
    }

    @Transactional
    public ExportArtifact publish(ExportAttempt attempt, Long finalObjectId, ObjectNode summary) {
        TaskEntity task = taskMapper.lockActiveClaim(attempt.taskId(), attempt.claimToken());
        if (task == null) throw new BusinessException(409, "导出任务所有权已失效");
        ExportArtifact artifact = artifactMapper.lockById(attempt.artifactId());
        if (artifact == null || !attempt.taskId().equals(artifact.getTaskId())
                || artifact.getGeneration() == null || artifact.getGeneration() != attempt.generation()
                || !attempt.claimToken().equals(artifact.getPublisherToken())) {
            throw new BusinessException(409, "导出发布代次已失效");
        }
        ExportStoredObject object = objectMapper.lockById(finalObjectId);
        if (object == null || !ExportStoredObject.STATE_RETAINED.equals(object.getState())
                || !ExportStoredObject.KIND_FINAL.equals(object.getKind())
                || !attempt.taskId().equals(object.getTaskId())
                || object.getGeneration() == null || object.getGeneration() != attempt.generation()
                || !attempt.claimToken().equals(object.getOwnerToken())
                || object.getByteSize() == null || object.getSha256() == null) {
            throw new BusinessException(409, "最终导出对象所有权已失效");
        }
        if (finalObjectId.equals(artifact.getCurrentObjectId())) return artifact;

        Long previousObjectId = artifact.getCurrentObjectId();
        int ttlHours = Math.max(1, configService.getInt("export_artifact_ttl_hours", 168));
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(ttlHours);
        if (artifactMapper.publishOwned(artifact.getId(), attempt.generation(), attempt.claimToken(),
                object.getId(), object.getStorageUrl(), object.getByteSize(), object.getSha256(), expiresAt) != 1) {
            throw new BusinessException(409, "导出发布状态已变化");
        }
        summary.put("artifactId", artifact.getId());
        summary.put("downloadUrl", "/api/admin/export/artifacts/" + artifact.getId() + "/download");
        summary.put("expiresAt", expiresAt.toString());
        summary.put("bytes", object.getByteSize());
        summary.put("sha256", object.getSha256());
        if (taskMapper.updateResultOwned(task.getId(), attempt.claimToken(), summary.toString()) != 1) {
            throw new BusinessException(409, "导出任务结果写入失败");
        }
        if (previousObjectId != null && !previousObjectId.equals(object.getId())) {
            objectMapper.requestDeletion(previousObjectId, LocalDateTime.now().plusMinutes(5), "最终产物已被新代次替换");
        }
        artifact.setCurrentObjectId(object.getId());
        artifact.setStorageUrl(object.getStorageUrl());
        artifact.setByteSize(object.getByteSize());
        artifact.setSha256(object.getSha256());
        artifact.setStatus(ExportArtifact.STATUS_ACTIVE);
        artifact.setExpiresAt(expiresAt);
        return artifact;
    }

    public Download requireDownloadable(Long artifactId) {
        ExportArtifact artifact = artifactMapper.selectById(artifactId);
        if (artifact == null || artifact.getStatus() == null || artifact.getStatus() != ExportArtifact.STATUS_ACTIVE
                || artifact.getByteSize() == null) {
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
        ExportStoredObject object;
        if (artifact.getCurrentObjectId() == null) {
            object = objectService.registerLegacyFinal(artifact);
            artifactMapper.attachLegacyObject(artifact.getId(), object.getId(), artifact.getStorageUrl());
            artifact.setCurrentObjectId(object.getId());
        } else {
            object = objectService.requireRetained(artifact.getCurrentObjectId());
        }
        return new Download(artifact, object);
    }

    public void stream(Download download, java.io.OutputStream output) {
        Download current = requireDownloadable(download.artifact().getId());
        objectService.streamWithLease(current.object().getId(), output);
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void expireArtifacts() {
        for (ExportArtifact artifact : artifactMapper.selectCleanupCandidates()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                if (artifactMapper.markExpired(artifact.getId(), artifact.getStorageUrl()) == 1
                        && artifact.getCurrentObjectId() != null) {
                    objectService.requestDeletion(artifact.getCurrentObjectId(), "最终产物已过期", 0);
                }
            });
        }
    }
}
