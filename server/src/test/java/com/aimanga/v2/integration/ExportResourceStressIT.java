package com.aimanga.v2.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in streaming ZIP stress scaffold; use -Dexport.stress.entryMiB=300 for the 20×300MiB gate. */
class ExportResourceStressIT {
    @Test
    void streamsTwentyEntriesAndProducesAReadableArchiveUnderBoundedHeap() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("export.stress.enabled"));
        int entryMiB = Integer.getInteger("export.stress.entryMiB", 2);
        int entries = 20;
        byte[] block = new byte[64 * 1024];
        new SecureRandom().nextBytes(block);
        var archive = Files.createTempFile("aimanga-export-stress-", ".zip");
        try {
            try (OutputStream raw = Files.newOutputStream(archive); ZipOutputStream zip = new ZipOutputStream(raw)) {
                for (int i = 0; i < entries; i++) {
                    zip.putNextEntry(new ZipEntry("comic-" + i + ".zip"));
                    long remaining = entryMiB * 1024L * 1024L;
                    while (remaining > 0) {
                        int length = (int) Math.min(block.length, remaining);
                        zip.write(block, 0, length);
                        remaining -= length;
                    }
                    zip.closeEntry();
                }
            }
            Set<String> names = new HashSet<>();
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                zip.stream().forEach(entry -> names.add(entry.getName()));
                assertThat(names).hasSize(entries);
                for (var entry : java.util.Collections.list(zip.entries())) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        assertThat(in.transferTo(OutputStream.nullOutputStream()))
                                .isEqualTo(entryMiB * 1024L * 1024L);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }
}
