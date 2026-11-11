package com.soumikseth.incidentinvestigator.simulator.domain;

/**
 * Internal failure modes. This enum is never exposed through the incident or
 * telemetry APIs: the investigator has to infer the mode from observed signals.
 */
public enum FailureMode {
    NONE,
    CONNECTION_POOL_EXHAUSTION,
    MEMORY_LEAK,
    SLOW_DEPENDENCY
}
