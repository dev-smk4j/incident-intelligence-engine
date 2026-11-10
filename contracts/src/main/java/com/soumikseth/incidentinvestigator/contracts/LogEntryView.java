package com.soumikseth.incidentinvestigator.contracts;

import java.time.Instant;

/**
 * Structured log record. The message is untrusted telemetry and must be handled
 * as data by the investigation engine; it is never interpreted as instructions.
 */
public record LogEntryView(
        Instant timestamp,
        String service,
        String level,
        String message,
        String traceId,
        String spanId,
        String correlationId) {
}
