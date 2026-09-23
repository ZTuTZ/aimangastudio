package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.ExportObjectReadLease;
import com.aimanga.v2.model.ExportStoredObject;
import com.aimanga.v2.repository.ExportObjectReadLeaseMapper;
import com.aimanga.v2.repository.ExportStoredObjectMapper;
import com.aimanga.v2.repository.ExportArtifactMapper;
import com.aimanga.v2.repository.TaskPlanUnitMapper;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persistent lifecycle for every checkpoint and final export object. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportObjectService {
    private static final int READ_LEASE_SECONDS = 120;

    private final ExportStoredObjectMapper objectMapper;
    private final ExportObjectReadLeaseMapper leaseMapper;
    private final StorageService storageService;
    private final ConfigService configService;
    private final PlatformTransactionManager transactionManager;
    private final ExportArtifactMapper artifactMapper;
    private final TaskPlanUnitMapper planUnitMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public ExportStoredObject registerUpload(Long taskId, Long planUnitId, Long artifactId,
                                             long generation, String kind, String ownerToken, Long userId) {
        if (!ExportStoredObject.KIND_CHECKPOINT.equals(kind) && !ExportStoredObject.KIND_FINAL.equals(kind)) {
            throw new BusinessException(400, "非法导出对象类型");
        }
        String folder = ExportStoredObject.KIND_FINAL.equals(kind) ? "final" : "checkpoints";
        String key = "exports/" + folder + "/" + (userId == null ? 0 : userId) + "/"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "/" + UUID.randomUUID() + ".zip";
        ExportStoredObject object = new ExportStoredObject();
        object.setTaskId(taskId);
        object.setPlanUnitId(planUnitId);
        object.setArtifactId(artifactId);
        object.setGeneration(generation);
        object.setKind(kind);
        object.setStorageKey(key);
        object.setState(ExportStoredObject.STATE_UPLOADING);
        object.setOwnerToken(ownerToken);
        int timeout = Math.max(60, configService.getInt("export_upload_timeout_seconds", 3600));
        object.setUploadDeadline(LocalDateTime.now().plusSeconds(timeout));
        object.setCreateTime(LocalDateTime.now());
        objectMapper.insert(object);
        return object;
    }

    public ExportStoredObject upload(ExportStoredObject object, Path file, long bytes, String sha256) {
        storageService.saveExportFile(object.getStorageKey(), file);
        String url = storageService.exportUrl(object.getStorageKey());
        if (objectMapper.markUploaded(object.getId(), url, bytes, sha256) != 1) {
            objectMapper.queueLateUploadDeletion(object.getId(), url, bytes, sha256);
            try {
                storageService.deleteExportFile(object.getStorageKey());
                // Cleanup remains idempotently queued in case the remote delete response was ambiguous.
            } catch (RuntimeException e) {
                log.warn("[export] 失效上传立即删除失败 objectId={}", object.getId(), e);
            }
            throw new BusinessException(409, "导出对象上传已失效");
        }
        object.setStorageUrl(url);
        object.setByteSize(bytes);
        object.setSha256(sha256);
        object.setState(ExportStoredObject.STATE_RETAINED);
        return object;
    }

    public ExportStoredObject requireRetained(Long objectId) {
        ExportStoredObject object = objectMapper.selectById(objectId);
        if (object == null || !ExportStoredObject.STATE_RETAINED.equals(object.getState())) {
            throw new BusinessException(409, "导出对象不存在或已进入清理流程");
        }
        return object;
    }

    public ExportStoredObject registerLegacyFinal(com.aimanga.v2.model.ExportArtifact artifact) {
        if (artifact.getStorageUrl() == null || artifact.getStorageUrl().isBlank()) {
            throw new BusinessException(409, "历史导出产物缺少存储地址，需人工处理");
        }
        final String key;
        try {
            key = storageService.exportKey(artifact.getStorageUrl());
        } catch (RuntimeException e) {
            throw new BusinessException(409, "历史导出产物不属于当前存储配置，需人工处理");
        }
        ExportStoredObject existing = objectMapper.selectByStorageKey(key);
        if (existing != null) return existing;
        ExportStoredObject object = new ExportStoredObject();
        object.setTaskId(artifact.getTaskId());
        object.setArtifactId(artifact.getId());
        object.setGeneration(artifact.getGeneration() == null ? 0L : artifact.getGeneration());
        object.setKind(ExportStoredObject.KIND_FINAL);
        object.setStorageKey(key);
        object.setStorageUrl(artifact.getStorageUrl());
        object.setState(ExportStoredObject.STATE_RETAINED);
        object.setByteSize(artifact.getByteSize());
        object.setSha256(artifact.getSha256());
        object.setCreateTime(artifact.getCreateTime() == null ? LocalDateTime.now() : artifact.getCreateTime());
        try {
            objectMapper.insert(object);
            return object;
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            return objectMapper.selectByStorageKey(key);
        }
    }

    private ExportStoredObject registerLegacyCheckpoint(com.aimanga.v2.model.TaskPlanUnit unit,
                                                        com.fasterxml.jackson.databind.JsonNode result) {
        String url = result.path("url").asText(null);
        if (url == null) throw new BusinessException(409, "历史检查点缺少存储地址");
        String key = storageService.exportKey(url);
        ExportStoredObject existing = objectMapper.selectByStorageKey(key);
        if (existing != null) return existing;
        ExportStoredObject object = new ExportStoredObject();
        object.setTaskId(unit.getTaskId());
        object.setPlanUnitId(unit.getId());
        object.setGeneration(0L);
        object.setKind(ExportStoredObject.KIND_CHECKPOINT);
        object.setStorageKey(key);
        object.setStorageUrl(url);
        object.setState(ExportStoredObject.STATE_RETAINED);
        object.setByteSize(result.path("bytes").canConvertToLong() ? result.path("bytes").asLong() : null);
        object.setSha256(result.path("sha256").asText(null));
        object.setCreateTime(unit.getFinishTime() == null ? LocalDateTime.now() : unit.getFinishTime());
        try {
            objectMapper.insert(object);
            return object;
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            return objectMapper.selectByStorageKey(key);
        }
    }

    public void copyTo(Long objectId, Path target) {
        ExportStoredObject object = requireRetained(objectId);
        storageService.copyExportFile(object.getStorageKey(), target);
    }

    public void writeTo(Long objectId, OutputStream output) {
        ExportStoredObject object = requireRetained(objectId);
        storageService.writeExportFile(object.getStorageKey(), output);
    }

    public void requestDeletion(Long objectId, String reason, int delaySeconds) {
        objectMapper.requestDeletion(objectId, LocalDateTime.now().plusSeconds(Math.max(0, delaySeconds)), reason);
    }

    public int requestTaskDeletion(Long taskId, String reason) {
        return objectMapper.requestTaskDeletion(taskId, reason);
    }

    public String beginReadLease(Long objectId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ExportStoredObject object = objectMapper.lockById(objectId);
            if (object == null || !ExportStoredObject.STATE_RETAINED.equals(object.getState())) {
                throw new BusinessException(410, "导出产物已进入清理流程");
            }
            String token = UUID.randomUUID().toString();
            ExportObjectReadLease lease = new ExportObjectReadLease();
            lease.setToken(token);
            lease.setObjectId(objectId);
            lease.setLeaseUntil(LocalDateTime.now().plusSeconds(READ_LEASE_SECONDS));
            lease.setCreateTime(LocalDateTime.now());
            leaseMapper.insert(lease);
            return token;
        });
    }

    public void streamWithLease(Long objectId, OutputStream output) {
        ExportStoredObject object = requireRetained(objectId);
        String token = beginReadLease(objectId);
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        var renewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "export-read-lease");
            thread.setDaemon(true);
            return thread;
        });
        renewer.scheduleAtFixedRate(() -> {
            try {
                if (leaseMapper.renew(token, READ_LEASE_SECONDS) != 1) leaseLost.set(true);
            } catch (RuntimeException e) {
                leaseLost.set(true);
            }
        }, 30, 30, TimeUnit.SECONDS);
        long maxSeconds = Math.max(60, configService.getInt("export_download_max_seconds", 3600));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(maxSeconds);
        try {
            storageService.writeExportFile(object.getStorageKey(),
                    new LeaseCheckedOutputStream(output, leaseLost, deadline));
        } finally {
            renewer.shutdownNow();
            leaseMapper.release(token);
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void cleanup() {
        leaseMapper.deleteExpired();
        int checkpointTtl = Math.max(1, configService.getInt("export_checkpoint_ttl_hours", 168));
        objectMapper.expireCheckpoints(checkpointTtl);
        for (ExportStoredObject candidate : objectMapper.selectCleanupCandidates()) {
            String token = UUID.randomUUID().toString();
            if (objectMapper.claimCleanup(candidate.getId(), token, 300) != 1) continue;
            try {
                storageService.deleteExportFile(candidate.getStorageKey());
                objectMapper.markDeleted(candidate.getId(), token);
            } catch (RuntimeException e) {
                objectMapper.cleanupFailed(candidate.getId(), token, abbreviate(e.getMessage()));
                log.warn("[export] 对象清理失败 objectId={}", candidate.getId(), e);
            }
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void backfillLegacyObjects() {
        for (var artifact : artifactMapper.selectLegacyBackfillCandidates()) {
            try {
                ExportStoredObject object = registerLegacyFinal(artifact);
                artifactMapper.attachLegacyObject(artifact.getId(), object.getId(), artifact.getStorageUrl());
            } catch (RuntimeException e) {
                log.warn("[export] 历史最终产物待人工处理 artifactId={}: {}", artifact.getId(), e.getMessage());
            }
        }
        for (var unit : planUnitMapper.selectLegacyExportCheckpoints()) {
            try {
                var result = json.readTree(unit.getResultRef());
                ExportStoredObject object = registerLegacyCheckpoint(unit, result);
                ((com.fasterxml.jackson.databind.node.ObjectNode) result).put("objectId", object.getId());
                planUnitMapper.replaceSuccessfulResult(unit.getId(), result.toString());
            } catch (Exception e) {
                log.warn("[export] 历史检查点待人工处理 planUnitId={}: {}", unit.getId(), e.getMessage());
            }
        }
    }

    private static String abbreviate(String value) {
        if (value == null) return "存储删除失败";
        return value.length() <= 480 ? value : value.substring(0, 480);
    }

    private static final class LeaseCheckedOutputStream extends FilterOutputStream {
        private final AtomicBoolean leaseLost;
        private final long deadline;

        private LeaseCheckedOutputStream(OutputStream out, AtomicBoolean leaseLost, long deadline) {
            super(out);
            this.leaseLost = leaseLost;
            this.deadline = deadline;
        }

        @Override public void write(int b) throws IOException {
            check();
            out.write(b);
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            check();
            out.write(b, off, len);
        }

        private void check() throws IOException {
            if (leaseLost.get()) throw new IOException("导出下载租约续期失败");
            if (System.nanoTime() > deadline) throw new IOException("导出下载超过最大时长");
        }
    }
}
