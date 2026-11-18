package com.soumikseth.incidentinvestigator.engine;

import com.soumikseth.incidentinvestigator.contracts.MetricNames;
import com.soumikseth.incidentinvestigator.engine.domain.Evidence;
import com.soumikseth.incidentinvestigator.engine.domain.HypothesisAssessment;
import com.soumikseth.incidentinvestigator.engine.domain.HypothesisCategory;
import com.soumikseth.incidentinvestigator.engine.domain.InvestigationResult;
import com.soumikseth.incidentinvestigator.engine.domain.InvestigationStatus;
import com.soumikseth.incidentinvestigator.engine.investigation.IncidentNotFoundException;
import com.soumikseth.incidentinvestigator.engine.investigation.InvestigationOrchestrator;
import com.soumikseth.incidentinvestigator.engine.persistence.InvestigationRepository;
import com.soumikseth.incidentinvestigator.engine.support.FakeGatewayConfiguration;
import com.soumikseth.incidentinvestigator.engine.support.FakeTelemetryGateway;
import com.soumikseth.incidentinvestigator.engine.support.ScenarioFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end engine tests for the three supported incident shapes.
 *
 * Each fixture presents the engine with symptom-level telemetry only. The test
 * asserts that the top-ranked category matches the injected failure, which proves
 * the diagnosis came from evidence rather than from a label.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeGatewayConfiguration.class)
class InvestigationScenariosTest {

    @Autowired
    private InvestigationOrchestrator orchestrator;

    @Autowired
    private FakeTelemetryGateway gateway;

    @Autowired
    private InvestigationRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clearStoredInvestigations() {
        gateway.reset();
        jdbc.sql("DELETE FROM investigation").update();
    }

    @Test
    void diagnosesPoolExhaustionAndPersistsAnExplainableReport() {
        ScenarioFixtures.poolExhaustion(gateway);

        InvestigationResult result = orchestrator.investigate("incident-pool");

        assertThat(result.investigation().status()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(result.steps()).hasSize(10);
        assertThat(result.steps()).allMatch(step -> "SUCCESS".equals(step.status()));

        HypothesisAssessment top = result.hypotheses().getFirst();
        assertThat(top.category()).isEqualTo(HypothesisCategory.CONNECTION_POOL_EXHAUSTION);
        assertThat(top.rank()).isEqualTo(1);
        assertThat(top.confidence()).isGreaterThan(0.7);
        assertThat(result.hypotheses()).hasSizeGreaterThanOrEqualTo(2);

        assertReportIsExplainedByCollectedEvidence(result);

        InvestigationResult stored = repository.findById(result.investigation().id()).orElseThrow();
        assertThat(stored.investigation().status()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(stored.steps()).hasSize(result.steps().size());
        assertThat(stored.hypotheses()).hasSize(result.hypotheses().size());
        assertThat(stored.report()).isNotNull();
    }

    @Test
    void diagnosesSaturationFromHealthWhenMetricSamplesHaveNotCaughtUp() {
        ScenarioFixtures.poolExhaustionWithLaggingMetrics(gateway);

        InvestigationResult result = orchestrator.investigate("incident-pool");

        HypothesisAssessment top = result.hypotheses().getFirst();
        assertThat(top.category()).isEqualTo(HypothesisCategory.CONNECTION_POOL_EXHAUSTION);
        assertThat(top.confidence()).isGreaterThan(0.4);

        // The baseline metric point must not be cited as support for saturation.
        String citedEvidence = result.evidence().stream()
                .filter(evidence -> top.supportingEvidenceIds().contains(evidence.id()))
                .map(Evidence::content)
                .collect(Collectors.joining(" "));
        assertThat(citedEvidence).contains("pool=0.98");
        assertThat(citedEvidence).doesNotContain(MetricNames.DB_POOL_UTILIZATION);
    }

    @Test
    void diagnosesMemoryPressureAndCitesGcAndHeapEvidence() {
        ScenarioFixtures.memoryLeak(gateway);

        InvestigationResult result = orchestrator.investigate("incident-memory");

        HypothesisAssessment top = result.hypotheses().getFirst();
        assertThat(top.category()).isEqualTo(HypothesisCategory.MEMORY_PRESSURE);
        assertThat(top.confidence()).isGreaterThan(0.7);

        String citedEvidence = result.evidence().stream()
                .filter(evidence -> top.supportingEvidenceIds().contains(evidence.id()))
                .map(Evidence::content)
                .collect(Collectors.joining(" "));
        assertThat(citedEvidence).contains("jvm.heap.utilization", "GC pause");

        assertReportIsExplainedByCollectedEvidence(result);
        assertThat(result.report().recommendations()).isNotEmpty();
        assertThat(result.report().recommendations().getFirst().action()).contains("heap dump");
    }

    @Test
    void diagnosesASlowDownstreamDependencyRatherThanTheCaller() {
        ScenarioFixtures.slowInventoryDependency(gateway);

        InvestigationResult result = orchestrator.investigate("incident-slow");

        HypothesisAssessment top = result.hypotheses().getFirst();
        assertThat(top.category()).isEqualTo(HypothesisCategory.DOWNSTREAM_DEPENDENCY_LATENCY);
        assertThat(top.confidence()).isGreaterThan(0.5);
        assertThat(top.impactedServices()).contains("order-service", "inventory-service");

        // The planner added a health query for the dependency it discovered in traces.
        assertThat(result.steps())
                .anyMatch(step -> step.summary().contains("inventory-service"));
        assertThat(result.steps()).hasSize(11);
        assertThat(result.steps()).allMatch(step -> "SUCCESS".equals(step.status()));

        assertReportIsExplainedByCollectedEvidence(result);
    }

    @Test
    void unknownIncidentIsRejectedBeforeAnInvestigationRecordIsCreated() {
        ScenarioFixtures.poolExhaustion(gateway);

        assertThatThrownBy(() -> orchestrator.investigate("incident-does-not-exist"))
                .isInstanceOf(IncidentNotFoundException.class);

        assertThat(repository.findRecent(10)).isEmpty();
    }

    private void assertReportIsExplainedByCollectedEvidence(InvestigationResult result) {
        assertThat(result.report()).isNotNull();
        assertThat(result.report().rootCauses()).isEqualTo(result.hypotheses());
        assertThat(result.report().summary()).isNotBlank();

        Set<String> evidenceIds = result.evidence().stream()
                .map(Evidence::id)
                .collect(Collectors.toSet());
        assertThat(evidenceIds).isNotEmpty();

        for (HypothesisAssessment hypothesis : result.hypotheses()) {
            assertThat(evidenceIds).containsAll(hypothesis.supportingEvidenceIds());
            assertThat(hypothesis.confidence()).isGreaterThanOrEqualTo(0.0);
            assertThat(hypothesis.confidence()).isLessThanOrEqualTo(1.0);
            assertThat(hypothesis.confidenceBasis()).contains("distinct supporting categories");
        }

        // Scenario labels and injected failure names must never reach the agent's data.
        assertThat(result.evidence())
                .allMatch(evidence -> !evidence.content().toLowerCase().contains("scenario"))
                .allMatch(evidence -> !evidence.content().toLowerCase().contains("failure mode"));
    }
}
