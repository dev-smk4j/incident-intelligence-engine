package com.soumikseth.incidentinvestigator.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "simulator.traffic")
public record TrafficProperties(boolean enabled, long intervalMs, int requestsPerTick) {

    public TrafficProperties {
        if (intervalMs <= 0) {
            intervalMs = 2_000;
        }
        if (requestsPerTick <= 0) {
            requestsPerTick = 2;
        }
    }
}
