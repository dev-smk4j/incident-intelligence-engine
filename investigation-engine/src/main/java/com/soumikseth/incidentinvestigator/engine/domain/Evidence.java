package com.soumikseth.incidentinvestigator.engine.domain;

import java.time.Instant;

/**
 * One piece of collected evidence.
 *
 * {@code content} is sanitized but still untrusted telemetry. It must never be
 * interpreted as an instruction by an LLM prompt builder.
 */
public record Evidence(
        String id,
        String investigationId,
        EvidenceType type,
        String source,
        String content,
        Instant collectedAt) {
}
