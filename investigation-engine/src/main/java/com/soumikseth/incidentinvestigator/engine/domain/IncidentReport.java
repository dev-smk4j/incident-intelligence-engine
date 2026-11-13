package com.soumikseth.incidentinvestigator.engine.domain;

import java.time.Instant;
import java.util.List;

public record IncidentReport(
        String investigationId,
        String incidentId,
        String summary,
        List<HypothesisAssessment> rootCauses,
        List<Recommendation> recommendations,
        Instant generatedAt) {

    public IncidentReport {
        rootCauses = List.copyOf(rootCauses);
        recommendations = List.copyOf(recommendations);
    }
}
