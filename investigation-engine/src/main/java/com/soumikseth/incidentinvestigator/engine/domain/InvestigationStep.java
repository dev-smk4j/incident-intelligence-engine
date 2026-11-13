package com.soumikseth.incidentinvestigator.engine.domain;

import java.time.Instant;

public record InvestigationStep(
        String id,
        String investigationId,
        int sequence,
        String toolName,
        String status,
        String summary,
        Instant startedAt,
        Instant completedAt) {
}
