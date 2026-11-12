package com.soumikseth.incidentinvestigator.simulator.incident;

import com.soumikseth.incidentinvestigator.contracts.IncidentView;

import java.time.Instant;

/**
 * Domain record for a detected incident.
 *
 * Note the absence of any scenario or failure-mode field. The simulator knows
 * what it injected, but the investigator must not: incidents are created from
 * observed symptoms only.
 */
public record IncidentRecord(
        String id,
        String title,
        String description,
        String severity,
        String status,
        String primaryService,
        Instant detectedAt,
        Instant createdAt) {

    public IncidentView toView() {
        return new IncidentView(id, title, description, severity, status, primaryService, detectedAt, createdAt);
    }
}
