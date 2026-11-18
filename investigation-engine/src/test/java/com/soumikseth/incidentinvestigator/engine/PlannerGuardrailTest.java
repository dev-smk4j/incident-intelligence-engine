package com.soumikseth.incidentinvestigator.engine;

import com.soumikseth.incidentinvestigator.engine.domain.InvestigationResult;
import com.soumikseth.incidentinvestigator.engine.domain.InvestigationStatus;
import com.soumikseth.incidentinvestigator.engine.investigation.InvestigationOrchestrator;
import com.soumikseth.incidentinvestigator.engine.planner.InvestigationPlanner;
import com.soumikseth.incidentinvestigator.engine.planner.PlanDecision;
import com.soumikseth.incidentinvestigator.engine.support.FakeGatewayConfiguration;
import com.soumikseth.incidentinvestigator.engine.support.FakeTelemetryGateway;
import com.soumikseth.incidentinvestigator.engine.support.ScenarioFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the allowlist and failure budget hold even when the planner is hostile.
 * A planner that asks for a shell command must not be able to run anything.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({FakeGatewayConfiguration.class, PlannerGuardrailTest.RecklessPlannerConfiguration.class})
class PlannerGuardrailTest {

    @Autowired
    private InvestigationOrchestrator orchestrator;

    @Autowired
    private FakeTelemetryGateway gateway;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        gateway.reset();
        jdbc.sql("DELETE FROM investigation").update();
    }

    @Test
    void rejectsNonAllowlistedToolsAndFailsClosed() {
        ScenarioFixtures.poolExhaustion(gateway);

        InvestigationResult result = orchestrator.investigate("incident-pool");

        assertThat(result.investigation().status()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(result.investigation().errorMessage()).contains("consecutive tool failures");
        assertThat(result.steps()).hasSize(3);
        assertThat(result.steps()).allSatisfy(step -> {
            assertThat(step.toolName()).isEqualTo("run_shell");
            assertThat(step.status()).isEqualTo("FAILED");
            assertThat(step.summary()).contains("not allowlisted");
        });
        assertThat(result.evidence()).isEmpty();
        assertThat(result.hypotheses()).isEmpty();
        assertThat(result.report()).isNull();
    }

    @TestConfiguration
    static class RecklessPlannerConfiguration {

        @Bean
        @Primary
        InvestigationPlanner recklessPlanner() {
            return context -> new PlanDecision.CallTool(
                    "run_shell", Map.of("command", "rm -rf /"));
        }
    }
}
