package com.aimanga.v2.integration;

import com.aimanga.v2.model.ExportStoredObject;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.model.TaskPlanUnit;
import com.aimanga.v2.pipeline.*;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.service.TaskPlanningService;
import com.aimanga.v2.storage.StorageService;
import com.aimanga.v2.task.OperationalMetrics;
import com.aimanga.v2.task.TaskExecutionOwner;
import com.aimanga.v2.task.TaskRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in production export-path stress: -Dexport.stress.entryBytes=300000000, -Xmx512m. */
class ExportResourceStressIT {
    private static final int ENTRIES = 20;
    private static final long LIMIT = 8L * 1024 * 1024 * 1024;

    @Test
    void productionBatchPathStreamsIncompressibleComicsWithinFixedHeap() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("export.stress.enabled"));
        long entryBytes = Long.getLong("export.stress.entryBytes", 2_000_000L);
        assertThat(entryBytes).isPositive().isLessThan(LIMIT / ENTRIES);
        String expectedSha = syntheticSha(entryBytes);

        TaskPlanningService planning = mock(TaskPlanningService.class);
        PipelineStageService stages = mock(PipelineStageService.class);
        ConcurrentStageRunner runner = mock(ConcurrentStageRunner.class);
        PublicationService publication = mock(PublicationService.class);
        StorageService storage = mock(StorageService.class);
        ConfigService config = new ConfigService(null) {
            @Override public long getLong(String key, long defaultValue) { return LIMIT; }
        };
        OperationalMetrics metrics = mock(OperationalMetrics.class);
        StageItemCommitService commit = mock(StageItemCommitService.class);
        ExportArtifactService artifacts = mock(ExportArtifactService.class);
        ExportObjectService objects = mock(ExportObjectService.class);
        TaskRuntime runtime = mock(TaskRuntime.class);
        ObjectMapper json = new ObjectMapper();
        ExportTempFiles temp = new ExportTempFiles(config);

        TaskEntity task = new TaskEntity();
        task.setId(70L);
        task.setProjectId(1L);
        task.setUserId(1L);
        List<Long> ids = new ArrayList<>();
        List<TaskPlanUnit> units = new ArrayList<>();
        for (long id = 1; id <= ENTRIES; id++) {
            ids.add(id);
            TaskPlanUnit unit = new TaskPlanUnit();
            unit.setId(id);
            unit.setBusinessId(id);
            unit.setStageType(PipelineStageService.STAGE_EXPORT);
            unit.setStatus(TaskPlanUnit.STATUS_SUCCESS);
            unit.setResultRef(json.createObjectNode().put("objectId", id + 100)
                    .put("bytes", entryBytes).put("sha256", expectedSha).toString());
            units.add(unit);
        }
        when(planning.targetIds(task, PipelineStageService.STAGE_EXPORT, "PROJECT")).thenReturn(ids);
        when(planning.plan(task)).thenReturn(units);
        when(runtime.owner()).thenReturn(new TaskExecutionOwner(70L, "owner"));
        when(artifacts.beginAttempt(any())).thenReturn(new ExportArtifactService.ExportAttempt(90L, 70L, 1, "owner"));
        doAnswer(invocation -> {
            writeSynthetic(invocation.getArgument(1), entryBytes);
            return null;
        }).when(objects).writeTo(anyLong(), any(OutputStream.class));
        ExportStoredObject finalObject = new ExportStoredObject();
        finalObject.setId(501L);
        when(objects.registerUpload(eq(70L), eq(null), eq(90L), eq(1L),
                eq(ExportStoredObject.KIND_FINAL), eq("owner"), eq(1L))).thenReturn(finalObject);
        AtomicLong archiveBytes = new AtomicLong();
        AtomicReference<String> archiveSha = new AtomicReference<>();
        doAnswer(invocation -> {
            Path archive = invocation.getArgument(1);
            long bytes = invocation.getArgument(2);
            String sha = invocation.getArgument(3);
            assertThat(Files.size(archive)).isEqualTo(bytes);
            assertThat(bytes).isGreaterThan((long) (entryBytes * ENTRIES * 0.95));
            verifyArchive(archive, entryBytes, expectedSha, json);
            assertThat(sha256(archive)).isEqualTo(sha);
            archiveBytes.set(bytes);
            archiveSha.set(sha);
            return finalObject;
        }).when(objects).upload(eq(finalObject), any(Path.class), anyLong(), any(String.class));

        Path samples = Path.of("target", "export-stress-metrics.csv");
        Files.createDirectories(samples.getParent());
        var sampler = Executors.newSingleThreadScheduledExecutor();
        AtomicLong peakHeap = new AtomicLong();
        AtomicLong peakTemp = new AtomicLong();
        Instant start = Instant.now();
        try (var report = Files.newBufferedWriter(samples)) {
            report.write("seconds,heap_bytes,temp_bytes\n");
            sampler.scheduleAtFixedRate(() -> {
                try {
                    long heap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                    long disk = temporaryBytes();
                    peakHeap.accumulateAndGet(heap, Math::max);
                    peakTemp.accumulateAndGet(disk, Math::max);
                    synchronized (report) {
                        report.write(Duration.between(start, Instant.now()).toSeconds() + "," + heap + "," + disk + "\n");
                        report.flush();
                    }
                } catch (Exception e) {
                    throw new AssertionError("resource sampling failed", e);
                }
            }, 0, 1, TimeUnit.SECONDS);
            new ExportTaskHandler(planning, stages, runner, publication, storage, config, json,
                    metrics, commit, artifacts, objects, temp).run(task, runtime);
        } finally {
            sampler.shutdownNow();
            sampler.awaitTermination(10, TimeUnit.SECONDS);
        }
        verify(artifacts).publish(any(), eq(501L), any());
        assertThat(peakHeap.get()).isLessThanOrEqualTo(512L * 1024 * 1024);
        assertThat(peakTemp.get()).isLessThanOrEqualTo(LIMIT);
        System.out.printf("export stress: entryBytes=%d, archiveBytes=%d, archiveSha256=%s, peakHeap=%d, peakTemp=%d, elapsedSeconds=%d, samples=%s%n",
                entryBytes, archiveBytes.get(), archiveSha.get(), peakHeap.get(), peakTemp.get(),
                Duration.between(start, Instant.now()).toSeconds(), samples);
    }

    private static String syntheticSha(long bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        writeSynthetic(new DigestOutputStream(OutputStream.nullOutputStream(), digest), bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void writeSynthetic(OutputStream output, long bytes) throws Exception {
        Random random = new Random(884477L);
        byte[] block = new byte[64 * 1024];
        long remaining = bytes;
        while (remaining > 0) {
            random.nextBytes(block);
            int length = (int) Math.min(block.length, remaining);
            output.write(block, 0, length);
            remaining -= length;
        }
    }

    private static void verifyArchive(Path archive, long entryBytes, String expectedSha,
                                      ObjectMapper json) throws Exception {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertThat(zip.size()).isEqualTo(ENTRIES + 1);
            for (int id = 1; id <= ENTRIES; id++) {
                var entry = zip.getEntry("comic-" + id + ".zip");
                assertThat(entry).isNotNull();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long count;
                try (InputStream input = new DigestInputStream(zip.getInputStream(entry), digest)) {
                    count = input.transferTo(OutputStream.nullOutputStream());
                }
                assertThat(count).isEqualTo(entryBytes);
                assertThat(HexFormat.of().formatHex(digest.digest())).isEqualTo(expectedSha);
            }
            try (InputStream input = zip.getInputStream(zip.getEntry("summary.json"))) {
                var summary = json.readTree(input);
                assertThat(summary.path("total").asInt()).isEqualTo(ENTRIES);
                assertThat(summary.path("success").asInt()).isEqualTo(ENTRIES);
                assertThat(summary.path("failed").asInt()).isZero();
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long temporaryBytes() throws Exception {
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "aimanga-export",
                Long.toString(ProcessHandle.current().pid()));
        if (!Files.isDirectory(directory)) return 0;
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.size(path); } catch (Exception e) { throw new RuntimeException(e); }
            }).sum();
        }
    }
}
