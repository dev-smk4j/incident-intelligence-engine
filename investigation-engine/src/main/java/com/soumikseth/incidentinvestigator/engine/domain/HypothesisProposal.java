package com.soumikseth.incidentinvestigator.engine.domain;

import java.util.List;

/**
 * A candidate root cause produced by a planner. The planner proposes; the
 * evaluator decides how much the cited evidence actually supports it.
 */
public record HypothesisProposal(
        String statement,
        HypothesisCategory category,
        List<String> supportingEvidenceIds,
        List<String> contradictingEvidenceIds,
        List<String> missingEvidence,
        List<String> impactedServices) {

    public HypothesisProposal {
        supportingEvidenceIds = supportingEvidenceIds == null ? List.of() : List.copyOf(supportingEvidenceIds);
        contradictingEvidenceIds =
                contradictingEvidenceIds == null ? List.of() : List.copyOf(contradictingEvidenceIds);
        missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
        impactedServices = impactedServices == null ? List.of() : List.copyOf(impactedServices);
    }
}
