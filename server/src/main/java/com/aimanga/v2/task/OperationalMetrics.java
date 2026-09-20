package com.aimanga.v2.task;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Low-cardinality process counters exposed by the existing admin monitor endpoint. */
@Component
public class OperationalMetrics {
    private final AtomicLong heartbeatFailures = new AtomicLong();
    private final AtomicLong ownershipLost = new AtomicLong();
    private final AtomicLong queueRecoveries = new AtomicLong();
    private final AtomicLong permitRenewFailures = new AtomicLong();
    private static final AtomicLong VERSION_CONFLICTS = new AtomicLong();
    private final AtomicLong exportFailures = new AtomicLong();
    private final AtomicLong exportBytes = new AtomicLong();

    public void heartbeatFailure() { heartbeatFailures.incrementAndGet(); }
    public void ownershipLost() { ownershipLost.incrementAndGet(); }
    public void queueRecovery() { queueRecoveries.incrementAndGet(); }
    public void permitRenewFailure() { permitRenewFailures.incrementAndGet(); }
    public static void recordVersionConflict() { VERSION_CONFLICTS.incrementAndGet(); }
    public void exportFailure() { exportFailures.incrementAndGet(); }
    public void addExportBytes(long bytes) { exportBytes.addAndGet(Math.max(0, bytes)); }

    public Map<String, Long> snapshot() {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("heartbeatFailures", heartbeatFailures.get());
        values.put("ownershipLost", ownershipLost.get());
        values.put("queueRecoveries", queueRecoveries.get());
        values.put("permitRenewFailures", permitRenewFailures.get());
        values.put("versionConflicts", VERSION_CONFLICTS.get());
        values.put("exportFailures", exportFailures.get());
        values.put("exportBytes", exportBytes.get());
        return values;
    }
}
