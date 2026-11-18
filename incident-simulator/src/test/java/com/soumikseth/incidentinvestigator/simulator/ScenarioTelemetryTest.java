package com.soumikseth.incidentinvestigator.simulator;

import com.soumikseth.incidentinvestigator.contracts.LogEntryView;
import com.soumikseth.incidentinvestigator.contracts.MetricSeries;
import com.soumikseth.incidentinvestigator.contracts.ServiceHealth;
import com.soumikseth.incidentinvestigator.contracts.TraceView;
import com.soumikseth.incidentinvestigator.simulator.domain.Scenario;
import com.soumikseth.incidentinvestigator.simulator.domain.ServiceName;
import com.soumikseth.incidentinvestigator.simulator.runtime.FailureInjector;
import com.soumikseth.incidentinvestigator.simulator.runtime.SimulatedEnvironment;
import com.soumikseth.incidentinvestigator.contracts.MetricNames;
import com.soumikseth.incidentinvestigator.simulator.telemetry.TelemetrySampler;
import com.soumikseth.incidentinvestigator.simulator.telemetry.TelemetryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ScenarioTelemetryTest {

    @Autowired
    private SimulatedEnvironment environment;

    @Autowired
    private FailureInjector injector;

    @Autowired
    private TelemetryStore store;

    @Autowired
    private TelemetrySampler sampler;

    @BeforeEach
    void reset() {
        injector.clear();
        environment.reset();
        store.clear();
    }

    @AfterEach
    void clearFailures() {
        injector.clear();
    }

    @Test
    void connectionPoolExhaustionProducesSaturationErrorsAndDatabaseSpans() {
        injector.activate(Scenario.CONNECTION_POOL_EXHAUSTION, 120);
        generateOrders(80);
        sampler.sample();

        ServiceHealth payment = environment.health(ServiceName.PAYMENT);
        assertThat(payment.connectionPoolUtilization()).isGreaterThan(0.9);
        assertThat(payment.errorRate()).isGreaterThan(0.1);
        assertThat(payment.p99LatencyMs()).isGreaterThan(1_000);

        Instant now = Instant.now();
        List<MetricSeries> poolSeries = store.queryMetrics(
                "payment-service", MetricNames.DB_POOL_UTILIZATION, now.minusSeconds(60), now);
        assertThat(poolSeries).isNotEmpty();

        List<LogEntryView> logs = store.queryLogs(
                "payment-service", now.minusSeconds(60), now, "Connection acquisition", 10);
        assertThat(logs).isNotEmpty();

        List<TraceView> traces = store.findTraces(
                "payment-service", now.minusSeconds(60), now, 1_000, 5);
        assertThat(traces).isNotEmpty();
        assertThat(traces.getFirst().spans())
                .anyMatch(span -> span.operation().equals("db.query"));
    }

    @Test
    void memoryLeakIncreasesHeapAndEmitsGcEvidence() {
        injector.activate(Scenario.MEMORY_LEAK, 120);
        generateOrders(80);
        sampler.sample();

        ServiceHealth order = environment.health(ServiceName.ORDER);
        assertThat(order.heapUtilization()).isGreaterThan(0.9);
        assertThat(order.cpuUtilization()).isGreaterThan(0.7);

        Instant now = Instant.now();
        List<LogEntryView> logs = store.queryLogs(
                "order-service", now.minusSeconds(60), now, "GC pause", 20);
        assertThat(logs).isNotEmpty();
    }

    @Test
    void slowInventoryDependencyCascadesIntoOrderLatencyAndTraceSpans() {
        injector.activate(Scenario.SLOW_INVENTORY_DEPENDENCY, 120);

        SimulatedEnvironment.OrderOutcome last = null;
        for (int i = 0; i < 50; i++) {
            last = environment.generateOrder();
        }
        sampler.sample();

        ServiceHealth order = environment.health(ServiceName.ORDER);
        assertThat(order.p99LatencyMs()).isGreaterThan(1_200);

        assertThat(last).isNotNull();
        TraceView trace = store.findTrace(last.traceId()).orElseThrow();
        assertThat(trace.spans())
                .anyMatch(span -> span.service().equals("inventory-service") && span.durationMs() > 1_500);
    }

    private void generateOrders(int count) {
        for (int i = 0; i < count; i++) {
            environment.generateOrder();
        }
    }
}
