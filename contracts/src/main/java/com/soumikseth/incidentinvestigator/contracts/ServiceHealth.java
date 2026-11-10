package com.soumikseth.incidentinvestigator.contracts;

import java.time.Instant;

/**
 * Current health snapshot for one simulated service.
 *
 * {@code connectionPoolUtilization} and {@code consumerLag} are reported even
 * for services where they are not meaningful so that the shape of the data is
 * stable and tool responses remain easy to consume.
 */
public record ServiceHealth(
        String service,
        String status,
        double errorRate,
        double p99LatencyMs,
        double cpuUtilization,
        double heapUtilization,
        double connectionPoolUtilization,
        long consumerLag,
        Instant observedAt) {
}
