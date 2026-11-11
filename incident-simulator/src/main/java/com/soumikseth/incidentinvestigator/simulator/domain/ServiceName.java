package com.soumikseth.incidentinvestigator.simulator.domain;

import java.util.List;

/**
 * The four logical services in the simulated environment.
 *
 * Dependencies are declared as slugs rather than enum constants to keep enum
 * initialisation order irrelevant. A real deployment would source this from
 * service discovery or an architecture catalogue; here it is intentionally
 * explicit and small.
 */
public enum ServiceName {

    ORDER("order-service", List.of("payment-service", "inventory-service", "shipping-service")),
    PAYMENT("payment-service", List.of()),
    INVENTORY("inventory-service", List.of()),
    SHIPPING("shipping-service", List.of());

    private final String slug;
    private final List<String> dependencies;

    ServiceName(String slug, List<String> dependencies) {
        this.slug = slug;
        this.dependencies = List.copyOf(dependencies);
    }

    public String slug() {
        return slug;
    }

    public List<String> dependencies() {
        return dependencies;
    }

    public static ServiceName fromSlug(String slug) {
        for (ServiceName service : values()) {
            if (service.slug.equals(slug)) {
                return service;
            }
        }
        throw new IllegalArgumentException("Unknown service: " + slug);
    }
}
