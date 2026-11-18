package com.soumikseth.incidentinvestigator.simulator.detection;

import com.soumikseth.incidentinvestigator.contracts.IncidentView;
import com.soumikseth.incidentinvestigator.simulator.domain.Scenario;
import com.soumikseth.incidentinvestigator.simulator.runtime.FailureInjector;
import com.soumikseth.incidentinvestigator.simulator.runtime.SimulatedEnvironment;
import com.soumikseth.incidentinvestigator.simulator.telemetry.TelemetrySampler;
import com.soumikseth.incidentinvestigator.simulator.telemetry.TelemetryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AnomalyDetectorTest {

    @Autowired
    private AnomalyDetector detector;

    @Autowired
    private FailureInjector injector;

    @Autowired
    private SimulatedEnvironment environment;

    @Autowired
    private TelemetrySampler sampler;

    @Autowired
    private TelemetryStore store;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        injector.clear();
        environment.reset();
        store.clear();
        jdbc.sql("DELETE FROM incident").update();
    }

    @AfterEach
    void clearFailures() {
        injector.clear();
    }

    @Test
    void createsSymptomIncidentForPoolExhaustionWithoutNamingTheCause() {
        injector.activate(Scenario.CONNECTION_POOL_EXHAUSTION, 120);
        generateOrders(80);
        sampler.sample();

        IncidentView incident = detector.detectAndCreateIncident().orElseThrow();

        assertThat(incident.primaryService()).isEqualTo("payment-service");
        assertThat(incident.severity()).isIn("HIGH", "CRITICAL");
        String text = (incident.title() + " " + incident.description()).toLowerCase(Locale.ROOT);
        assertThat(text).doesNotContain("pool", "connection", "scenario", "exhaustion");
        assertThat(incident.description()).contains("error rate");
    }

    @Test
    void slowDependencyIsReportedAsTheUpstreamSymptomService() {
        injector.activate(Scenario.SLOW_INVENTORY_DEPENDENCY, 120);
        generateOrders(60);
        sampler.sample();

        IncidentView incident = detector.detectAndCreateIncident().orElseThrow();

        // The alert fires on the user-facing service; the cause is downstream.
        assertThat(incident.primaryService()).isEqualTo("order-service");
        assertThat(incident.description().toLowerCase(Locale.ROOT)).doesNotContain("inventory");
    }

    @Test
    void memoryLeakIsDetectedWithoutMentioningHeapOrMemory() {
        injector.activate(Scenario.MEMORY_LEAK, 120);
        generateOrders(80);
        sampler.sample();

        IncidentView incident = detector.detectAndCreateIncident().orElseThrow();

        assertThat(incident.primaryService()).isEqualTo("order-service");
        String text = (incident.title() + " " + incident.description()).toLowerCase(Locale.ROOT);
        assertThat(text).doesNotContain("heap", "memory", "leak", "gc");
    }

    private void generateOrders(int count) {
        for (int i = 0; i < count; i++) {
            environment.generateOrder();
        }
    }
}
