package com.soumikseth.incidentinvestigator.engine.domain;

import com.soumikseth.incidentinvestigator.contracts.IncidentView;

import java.util.List;

/**
 * The complete, immutable result of one investigation run. It is stored as a
 * document because an investigation is an append-only evidence record; the demo
 * does not need relational queries across individual evidence rows.
 */
public record InvestigationResult(
        Investigation investigation,
        IncidentView incident,
        List<InvestigationStep> steps,
        List<Evidence> evidence,
        List<HypothesisAssessment> hypotheses,
        IncidentReport report) {

    public InvestigationResult {
        steps = List.copyOf(steps);
        evidence = List.copyOf(evidence);
        hypotheses = List.copyOf(hypotheses);
    }
}
