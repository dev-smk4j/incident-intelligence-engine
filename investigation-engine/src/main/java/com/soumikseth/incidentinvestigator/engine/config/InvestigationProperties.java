package com.soumikseth.incidentinvestigator.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hard limits for one investigation.
 *
 * The orchestrator enforces all of these independently. A misbehaving planner
 * therefore cannot loop forever, call tools without bound, or hold a request open
 * indefinitely, even if its prompt is manipulated by telemetry content.
 */
@ConfigurationProperties(prefix = "investigation")
public record InvestigationProperties(
        int maxSteps,
        int maxToolCalls,
        int maxDurationSeconds,
        long toolTimeoutMs,
        int toolRetries,
        int maxConsecutiveFailures) {

    public InvestigationProperties {
        maxSteps = maxSteps <= 0 ? 14 : maxSteps;
        maxToolCalls = maxToolCalls <= 0 ? 12 : maxToolCalls;
        maxDurationSeconds = maxDurationSeconds <= 0 ? 120 : maxDurationSeconds;
        toolTimeoutMs = toolTimeoutMs <= 0 ? 5_000 : toolTimeoutMs;
        toolRetries = toolRetries < 0 ? 1 : toolRetries;
        maxConsecutiveFailures = maxConsecutiveFailures <= 0 ? 3 : maxConsecutiveFailures;
    }
}
