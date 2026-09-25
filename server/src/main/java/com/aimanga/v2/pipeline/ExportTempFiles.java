package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.service.ConfigService;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Per-process temporary-disk accounting for all export files. */
@Component
@RequiredArgsConstructor
public class ExportTempFiles {
    private final ConfigService configService;
    private final AtomicLong used = new AtomicLong();
    private final Set<Handle> handles = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void reclaimDeadProcessDirectories() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "aimanga-export");
        if (!Files.isDirectory(root)) return;
        try (var directories = Files.list(root)) {
            directories.filter(Files::isDirectory).forEach(directory -> {
                try {
                    long pid = Long.parseLong(directory.getFileName().toString());
                    if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) return;
                    try (var files = Files.list(directory)) {
                        files.filter(Files::isRegularFile).forEach(path -> {
                            try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                        });
                    }
                    Files.deleteIfExists(directory);
                } catch (Exception ignored) {
                    // Non-PID directories are not owned by this service and are intentionally preserved.
                }
            });
        } catch (IOException ignored) {
            // Temp cleanup is best effort; the live budget still prevents new unbounded writes.
        }
    }

    public Handle create(String prefix, String suffix) throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "aimanga-export",
                Long.toString(ProcessHandle.current().pid()));
        Files.createDirectories(dir);
        Handle handle = new Handle(Files.createTempFile(dir, prefix, suffix));
        handles.add(handle);
        return handle;
    }

    public OutputStream open(Handle handle) throws IOException {
        OutputStream raw = Files.newOutputStream(handle.path);
        long previous = handle.accounted.getAndSet(0);
        if (previous > 0) used.addAndGet(-previous);
        return budget(handle, raw);
    }

    OutputStream budget(Handle handle, OutputStream target) {
        return new BudgetOutputStream(target, handle);
    }

    public OutputStream limited(OutputStream target, long limit) {
        return new SizeLimitOutputStream(target, limit);
    }

    @PreDestroy
    void cleanupOwnedFiles() {
        for (Handle handle : Set.copyOf(handles)) handle.close();
    }

    public final class Handle implements AutoCloseable {
        private final Path path;
        private final AtomicLong accounted = new AtomicLong();
        private volatile boolean closed;

        private Handle(Path path) { this.path = path; }
        public Path path() { return path; }

        @Override public void close() {
            if (closed) return;
            try {
                if (Files.deleteIfExists(path) || !Files.exists(path)) {
                    used.addAndGet(-accounted.getAndSet(0));
                    closed = true;
                    handles.remove(this);
                }
            } catch (IOException ignored) {
                // Keep the reservation when deletion failed; later shutdown/ops cleanup can retry safely.
            }
        }
    }

    private final class BudgetOutputStream extends FilterOutputStream {
        private final Handle handle;
        private BudgetOutputStream(OutputStream out, Handle handle) { super(out); this.handle = handle; }
        // A failed write may have persisted some bytes. Keep the full reservation until deletion.
        @Override public void write(int b) throws IOException { reserve(1); out.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            reserve(len);
            out.write(b, off, len);
        }
        private void reserve(long bytes) throws IOException {
            long limit = configService.getLong("export_temp_max_bytes", 2_147_483_648L);
            while (true) {
                long current = used.get();
                if (bytes > limit - current) throw new IOException("导出临时磁盘预算不足: " + limit + " bytes");
                if (used.compareAndSet(current, current + bytes)) {
                    handle.accounted.addAndGet(bytes);
                    return;
                }
            }
        }
    }

    static final class SizeLimitOutputStream extends FilterOutputStream {
        private final long limit;
        private long written;
        SizeLimitOutputStream(OutputStream out, long limit) { super(out); this.limit = limit; }
        @Override public void write(int b) throws IOException { ensure(1); out.write(b); written++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { ensure(len); out.write(b, off, len); written += len; }
        private void ensure(int length) throws IOException {
            if (length < 0 || written > limit - length) throw new IOException("导出大小超过限制: " + limit + " bytes");
        }
    }
}
