package com.soumikseth.incidentinvestigator.contracts;

import java.time.Instant;

/**
 * A symptom-level incident as exposed to the investigation engine.
 *
 * Deliberately contains no scenario identifier or injected failure mode. The
 * investigator must derive the cause from telemetry, not from a label supplied by
 * the simulator.
 */
public record IncidentView(
        String id,
        String title,
        String description,
        String severity,
        String status,
        String primaryService,
        Instant detectedAt,
        Instant createdAt) {
}
