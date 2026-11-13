package com.soumikseth.incidentinvestigator.engine.domain;

import java.util.List;

/**
 * A scored hypothesis. {@code confidence} is a documented evidence score, not a
 * calibrated probability; {@code confidenceBasis} explains how it was calculated.
 */
public record HypothesisAssessment(
        String id,
        String investigationId,
        String statement,
        HypothesisCategory category,
        double confidence,
        String confidenceBasis,
        List<String> supportingEvidenceIds,
        List<String> contradictingEvidenceIds,
        List<String> missingEvidence,
        List<String> impactedServices,
        int rank) {

    public HypothesisAssessment {
        supportingEvidenceIds = List.copyOf(supportingEvidenceIds);
        contradictingEvidenceIds = List.copyOf(contradictingEvidenceIds);
        missingEvidence = List.copyOf(missingEvidence);
        impactedServices = List.copyOf(impactedServices);
    }

    public HypothesisAssessment withRank(int newRank) {
        return new HypothesisAssessment(
                id, investigationId, statement, category, confidence, confidenceBasis,
                supportingEvidenceIds, contradictingEvidenceIds, missingEvidence,
                impactedServices, newRank);
    }
}
