package com.soumikseth.incidentinvestigator.simulator.domain;

/**
 * Operator-facing incident scenarios.
 *
 * The scenario name is accepted only by the operator control endpoint. It is
 * deliberately absent from incident records, telemetry, and everything the
 * investigation engine can query.
 */
public enum Scenario {

    CONNECTION_POOL_EXHAUSTION(
            "connection-pool-exhaustion",
            ServiceName.PAYMENT,
            FailureMode.CONNECTION_POOL_EXHAUSTION),

    MEMORY_LEAK(
            "memory-leak",
            ServiceName.ORDER,
            FailureMode.MEMORY_LEAK),

    SLOW_INVENTORY_DEPENDENCY(
            "slow-inventory-dependency",
            ServiceName.INVENTORY,
            FailureMode.SLOW_DEPENDENCY);

    private final String slug;
    private final ServiceName targetService;
    private final FailureMode failureMode;

    Scenario(String slug, ServiceName targetService, FailureMode failureMode) {
        this.slug = slug;
        this.targetService = targetService;
        this.failureMode = failureMode;
    }

    public String slug() {
        return slug;
    }

    public ServiceName targetService() {
        return targetService;
    }

    public FailureMode failureMode() {
        return failureMode;
    }

    public static Scenario fromSlug(String slug) {
        for (Scenario scenario : values()) {
            if (scenario.slug.equals(slug)) {
                return scenario;
            }
        }
        throw new IllegalArgumentException("Unknown scenario: " + slug);
    }
}
