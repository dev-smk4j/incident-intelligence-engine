package com.soumikseth.incidentinvestigator.engine.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "simulator.client")
public record SimulatorClientProperties(String baseUrl) {

    public SimulatorClientProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8081";
        }
    }
}
