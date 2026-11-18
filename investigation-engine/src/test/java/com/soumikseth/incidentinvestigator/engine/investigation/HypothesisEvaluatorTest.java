package com.soumikseth.incidentinvestigator.engine.investigation;

import com.soumikseth.incidentinvestigator.engine.domain.Evidence;
import com.soumikseth.incidentinvestigator.engine.domain.EvidenceType;
import com.soumikseth.incidentinvestigator.engine.domain.HypothesisAssessment;
import com.soumikseth.incidentinvestigator.engine.domain.HypothesisCategory;
import com.soumikseth.incidentinvestigator.engine.domain.HypothesisProposal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HypothesisEvaluatorTest {

    private final HypothesisEvaluator evaluator = new HypothesisEvaluator();

    @Test
    void oneCategoryContributesItsWeightOnlyOnce() {
        List<Evidence> evidence = List.of(
                evidence("ev-001", "metrics:payment-service", "pool 0.98"),
                evidence("ev-002", "metrics:payment-service", "p99 3400 ms"));

        HypothesisAssessment assessment = onlyAssessment(
                new HypothesisProposal("Pool exhaustion", HypothesisCategory.CONNECTION_POOL_EXHAUSTION,
                        List.of("ev-001", "ev-002"), List.of(), List.of(), List.of("payment-service")),
                evidence);

        assertThat(assessment.confidence()).isEqualTo(0.32);
        assertThat(assessment.confidenceBasis()).contains("metrics");
    }

    @Test
    void distinctCategoriesCombine() {
        List<Evidence> evidence = List.of(
                evidence("ev-001", "metrics:payment-service", "pool 0.98"),
                evidence("ev-002", "logs:payment-service", "connection timeout"),
                evidence("ev-003", "traces:payment-service", "db.query 3360 ms"));

        HypothesisAssessment assessment = onlyAssessment(
                new HypothesisProposal("Pool exhaustion", HypothesisCategory.CONNECTION_POOL_EXHAUSTION,
                        List.of("ev-001", "ev-002", "ev-003"), List.of(), List.of(), List.of("payment-service")),
                evidence);

        assertThat(assessment.confidence()).isEqualTo(0.80);
    }

    @Test
    void contradictionAndMissingChecksReduceTheScoreWithCaps() {
        List<Evidence> evidence = List.of(
                evidence("ev-001", "metrics:svc", "pool normal"),
                evidence("ev-002", "logs:svc", "nothing unusual"),
                evidence("ev-003", "traces:svc", "fast"),
                evidence("ev-004", "health:svc", "healthy"),
                evidence("ev-005", "deployments:svc", "no deploy"),
                evidence("ev-006", "topology", "svc -> dep"));

        HypothesisAssessment assessment = onlyAssessment(
                new HypothesisProposal("Weak candidate", HypothesisCategory.DEPLOYMENT_REGRESSION,
                        List.of("ev-001"),
                        List.of("ev-002", "ev-003", "ev-004", "ev-005", "ev-006"),
                        List.of("a", "b", "c", "d", "e"),
                        List.of("svc")),
                evidence);

        // 0.32 - 0.12*4 (capped at 0.36) - 0.04*5 (capped at 0.16) => 0.00
        assertThat(assessment.confidence()).isZero();
    }

    @Test
    void unknownEvidenceIdsAreIgnoredAndReported() {
        HypothesisAssessment assessment = onlyAssessment(
                new HypothesisProposal("Pool exhaustion", HypothesisCategory.CONNECTION_POOL_EXHAUSTION,
                        List.of("ev-999"), List.of(), List.of(), List.of("payment-service")),
                List.of());

        assertThat(assessment.confidence()).isZero();
        assertThat(assessment.confidenceBasis()).contains("unresolved evidence links: 1");
    }

    @Test
    void ranksHypothesesByScoreAndAssignsSequentialRanks() {
        List<Evidence> evidence = List.of(
                evidence("ev-001", "metrics:svc", "heap 0.95"),
                evidence("ev-002", "logs:svc", "gc pause"),
                evidence("ev-003", "metrics:svc", "pool 0.31"));

        List<HypothesisAssessment> ranked = evaluator.evaluate(
                "investigation-1",
                List.of(
                        new HypothesisProposal("Deployment regression", HypothesisCategory.DEPLOYMENT_REGRESSION,
                                List.of(), List.of(), List.of(), List.of("svc")),
                        new HypothesisProposal("Memory pressure", HypothesisCategory.MEMORY_PRESSURE,
                                List.of("ev-001", "ev-002"), List.of(), List.of(), List.of("svc"))),
                evidence);

        assertThat(ranked).hasSize(2);
        assertThat(ranked.getFirst().category()).isEqualTo(HypothesisCategory.MEMORY_PRESSURE);
        assertThat(ranked.getFirst().rank()).isEqualTo(1);
        assertThat(ranked.get(1).rank()).isEqualTo(2);
        assertThat(ranked.getFirst().confidence()).isGreaterThan(ranked.get(1).confidence());
    }

    private HypothesisAssessment onlyAssessment(
            HypothesisProposal proposal, List<Evidence> evidence) {
        List<HypothesisAssessment> assessments = evaluator.evaluate("investigation-1", List.of(proposal), evidence);
        assertThat(assessments).hasSize(1);
        return assessments.getFirst();
    }

    private Evidence evidence(String id, String source, String content) {
        return new Evidence(id, "investigation-1", EvidenceType.OBSERVED_FACT, source, content, Instant.now());
    }
}
