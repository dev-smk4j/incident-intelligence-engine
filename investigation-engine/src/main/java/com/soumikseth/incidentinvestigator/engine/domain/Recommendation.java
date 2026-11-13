package com.soumikseth.incidentinvestigator.engine.domain;

import java.util.List;

/**
 * Advisory remediation only. Nothing in this project executes a recommendation.
 */
public record Recommendation(
        String action,
        String reason,
        String risk,
        String expectedEffect,
        List<String> supportingEvidenceIds) {

    public Recommendation {
        supportingEvidenceIds = List.copyOf(supportingEvidenceIds);
    }
}
