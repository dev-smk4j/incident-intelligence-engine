package com.soumikseth.incidentinvestigator.engine.domain;

/**
 * Internal hypothesis categories. They drive recommendation selection and make
 * evaluation results comparable; they are not produced by the telemetry itself.
 */
public enum HypothesisCategory {
    CONNECTION_POOL_EXHAUSTION,
    MEMORY_PRESSURE,
    DOWNSTREAM_DEPENDENCY_LATENCY,
    DEPLOYMENT_REGRESSION,
    UNKNOWN
}
