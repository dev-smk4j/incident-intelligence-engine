package com.soumikseth.incidentinvestigator.engine.domain;

import java.time.Instant;

public record Investigation(
        String id,
        String incidentId,
        InvestigationStatus status,
        String errorMessage,
        Instant createdAt,
        Instant completedAt) {
}
