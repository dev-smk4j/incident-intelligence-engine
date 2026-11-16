package com.soumikseth.incidentinvestigator.engine.api;

import java.time.Instant;

/**
 * Compact view for list endpoints. The full investigation document includes every
 * tool result and can be large, so listing keeps only what an operator scans first.
 */
public record InvestigationSummary(
        String id,
        String incidentId,
        String status,
        String topHypothesis,
        Double topConfidence,
        Instant createdAt,
        Instant completedAt) {
}
