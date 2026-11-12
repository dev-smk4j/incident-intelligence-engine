package com.soumikseth.incidentinvestigator.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "simulator.detection")
public record DetectionProperties(
        boolean enabled,
        long intervalMs,
        double errorRateThreshold,
        double latencyThresholdMs,
        double heapThreshold,
        double poolThreshold) {

    public DetectionProperties {
        if (intervalMs <= 0) {
            intervalMs = 5_000;
        }
        if (errorRateThreshold <= 0) {
            errorRateThreshold = 0.08;
        }
        if (latencyThresholdMs <= 0) {
            latencyThresholdMs = 1_200;
        }
        if (heapThreshold <= 0) {
            heapThreshold = 0.90;
        }
        if (poolThreshold <= 0) {
            poolThreshold = 0.95;
        }
    }
}
