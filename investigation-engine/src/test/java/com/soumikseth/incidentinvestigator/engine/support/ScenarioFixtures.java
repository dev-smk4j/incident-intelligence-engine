package com.soumikseth.incidentinvestigator.engine.support;

import com.soumikseth.incidentinvestigator.contracts.DeploymentView;
import com.soumikseth.incidentinvestigator.contracts.IncidentView;
import com.soumikseth.incidentinvestigator.contracts.LogEntryView;
import com.soumikseth.incidentinvestigator.contracts.MetricNames;
import com.soumikseth.incidentinvestigator.contracts.MetricSample;
import com.soumikseth.incidentinvestigator.contracts.MetricSeries;
import com.soumikseth.incidentinvestigator.contracts.ServiceHealth;
import com.soumikseth.incidentinvestigator.contracts.ServiceNode;
import com.soumikseth.incidentinvestigator.contracts.ServiceTopology;
import com.soumikseth.incidentinvestigator.contracts.SpanView;
import com.soumikseth.incidentinvestigator.contracts.TraceView;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Telemetry fixtures shaped like the three supported incident classes.
 *
 * Only the fake gateway knows which fixture is loaded. The engine sees the same
 * symptom-level incident, metrics, logs, traces, and deployments it would see from
 * a real telemetry backend, which is what makes these tests meaningful.
 */
public final class ScenarioFixtures {

    private ScenarioFixtures() {
    }

    public static void poolExhaustion(FakeTelemetryGateway gateway) {
        gateway.reset();
        Instant now = Instant.now();

        gateway.setIncident(new IncidentView(
                "incident-pool",
                "Sustained degradation detected on payment-service",
                "Automated detection based on recent telemetry: error rate 28.7%, p99 latency 3403 ms, "
                        + "CPU utilization 42%. Severity: CRITICAL.",
                "CRITICAL",
                "OPEN",
                "payment-service",
                now.minusSeconds(120),
                now.minusSeconds(120)));
        gateway.setTopology(topology(now, "payment-service"));
        gateway.setHealth(new ServiceHealth(
                "payment-service", "DEGRADED", 0.287, 3_403, 0.42, 0.36, 0.98, 0, now));
        gateway.setHealth(new ServiceHealth(
                "order-service", "DEGRADED", 0.29, 3_500, 0.16, 0.36, 0.31, 0, now));
        gateway.setHealth(new ServiceHealth(
                "inventory-service", "HEALTHY", 0.0, 24, 0.16, 0.36, 0.31, 0, now));
        gateway.setHealth(new ServiceHealth(
                "shipping-service", "HEALTHY", 0.0, 35, 0.16, 0.36, 0.31, 0, now));

        gateway.setMetrics("payment-service",
                series("payment-service", MetricNames.HTTP_ERROR_RATE, "ratio", now,
                        0.17, 0.21, 0.25, 0.27, 0.287),
                series("payment-service", MetricNames.HTTP_P99_LATENCY_MS, "ms", now,
                        2_950, 3_100, 3_250, 3_350, 3_403),
                series("payment-service", MetricNames.DB_POOL_UTILIZATION, "ratio", now,
                        0.92, 0.95, 0.97, 0.98, 0.98),
                series("payment-service", MetricNames.JVM_HEAP_UTILIZATION, "ratio", now,
                        0.35, 0.36, 0.36, 0.36, 0.36),
                series("payment-service", MetricNames.JVM_CPU_UTILIZATION, "ratio", now,
                        0.40, 0.41, 0.42, 0.42, 0.42));

        gateway.setLogs("payment-service", List.of(
                log("payment-service", "ERROR",
                        "Connection acquisition timed out after 3000 ms while handling authorize-payment", now),
                log("payment-service", "ERROR",
                        "Connection acquisition timed out after 3000 ms while handling authorize-payment", now),
                log("payment-service", "ERROR",
                        "Connection acquisition timed out after 3000 ms while handling authorize-payment", now)));

        gateway.setTraces(List.of(poolTrace(now)));
        gateway.setDeployments("payment-service", List.of(new DeploymentView(
                "deployment-payment", "payment-service", "v2.4.1",
                now.minusSeconds(47 * 60), "SUCCESS", "Routine dependency upgrade")));
    }

    /**
     * The same incident as {@link #poolExhaustion}, but the sampled metric series
     * still reflect the pre-incident baseline. A scrape interval can lag the live
     * health snapshot, and the diagnosis must not depend on winning that race.
     */
    public static void poolExhaustionWithLaggingMetrics(FakeTelemetryGateway gateway) {
        poolExhaustion(gateway);
        Instant now = Instant.now();
        gateway.setMetrics("payment-service",
                series("payment-service", MetricNames.HTTP_ERROR_RATE, "ratio", now, 0.0, 0.0, 0.0),
                series("payment-service", MetricNames.HTTP_P99_LATENCY_MS, "ms", now, 0.0, 0.0, 0.0),
                series("payment-service", MetricNames.DB_POOL_UTILIZATION, "ratio", now, 0.31, 0.31, 0.31),
                series("payment-service", MetricNames.JVM_HEAP_UTILIZATION, "ratio", now, 0.36, 0.36, 0.36),
                series("payment-service", MetricNames.JVM_CPU_UTILIZATION, "ratio", now, 0.16, 0.16, 0.16));
    }

    public static void memoryLeak(FakeTelemetryGateway gateway) {
        gateway.reset();
        Instant now = Instant.now();

        gateway.setIncident(new IncidentView(
                "incident-memory",
                "Sustained degradation detected on order-service",
                "Automated detection based on recent telemetry: error rate 5.1%, p99 latency 1700 ms, "
                        + "CPU utilization 92%. Severity: HIGH.",
                "HIGH",
                "OPEN",
                "order-service",
                now.minusSeconds(90),
                now.minusSeconds(90)));
        gateway.setTopology(topology(now, "order-service"));
        gateway.setHealth(new ServiceHealth(
                "order-service", "DEGRADED", 0.051, 1_700, 0.92, 0.95, 0.31, 0, now));
        gateway.setHealth(new ServiceHealth(
                "payment-service", "HEALTHY", 0.004, 25, 0.17, 0.36, 0.31, 0, now));
        gateway.setHealth(new ServiceHealth(
                "inventory-service", "HEALTHY", 0.002, 22, 0.16, 0.36, 0.31, 0, now));
        gateway.setHealth(new ServiceHealth(
                "shipping-service", "HEALTHY", 0.001, 34, 0.16, 0.36, 0.31, 0, now));

        gateway.setMetrics("order-service",
                series("order-service", MetricNames.HTTP_ERROR_RATE, "ratio", now,
                        0.021, 0.032, 0.041, 0.048, 0.051),
                series("order-service", MetricNames.HTTP_P99_LATENCY_MS, "ms", now,
                        1_240, 1_420, 1_550, 1_650, 1_700),
                series("order-service", MetricNames.DB_POOL_UTILIZATION, "ratio", now,
                        0.30, 0.31, 0.31, 0.31, 0.31),
                series("order-service", MetricNames.JVM_HEAP_UTILIZATION, "ratio", now,
                        0.84, 0.88, 0.91, 0.93, 0.95),
                series("order-service", MetricNames.JVM_CPU_UTILIZATION, "ratio", now,
                        0.78, 0.83, 0.87, 0.90, 0.92));

        gateway.setLogs("order-service", List.of(
                log("order-service", "WARN", "GC pause 380 ms (heap 96% used)", now),
                log("order-service", "WARN", "GC pause 410 ms (heap 96% used)", now),
                log("order-service", "WARN", "GC pause 365 ms (heap 95% used)", now)));

        gateway.setTraces(List.of(orderTrace(now, 1_700)));
        gateway.setDeployments("order-service", List.of(new DeploymentView(
                "deployment-order", "order-service", "v1.8.4",
                now.minusSeconds(30 * 60), "SUCCESS", "Refactored order validation")));
    }

    public static void slowInventoryDependency(FakeTelemetryGateway gateway) {
        gateway.reset();
        Instant now = Instant.now();

        gateway.setIncident(new IncidentView(
                "incident-slow",
                "Sustained degradation detected on order-service",
                "Automated detection based on recent telemetry: error rate 1.2%, p99 latency 1900 ms, "
                        + "CPU utilization 16%. Severity: HIGH.",
                "HIGH",
                "OPEN",
                "order-service",
                now.minusSeconds(100),
                now.minusSeconds(100)));
        gateway.setTopology(topology(now, "inventory-service"));
        gateway.setHealth(new ServiceHealth(
                "order-service", "DEGRADED", 0.012, 1_900, 0.16, 0.36, 0.31, 0, now));
        gateway.setHealth(new ServiceHealth(
                "inventory-service", "DEGRADED", 0.012, 1_780, 0.24, 0.36, 0.31, 0, now));
        gateway.setHealth(new ServiceHealth(
                "payment-service", "HEALTHY", 0.003, 24, 0.16, 0.36, 0.31, 0, now));
        gateway.setHealth(new ServiceHealth(
                "shipping-service", "HEALTHY", 0.002, 35, 0.16, 0.36, 0.31, 0, now));

        gateway.setMetrics("order-service",
                series("order-service", MetricNames.HTTP_ERROR_RATE, "ratio", now,
                        0.008, 0.009, 0.011, 0.012, 0.012),
                series("order-service", MetricNames.HTTP_P99_LATENCY_MS, "ms", now,
                        1_750, 1_800, 1_850, 1_880, 1_900),
                series("order-service", MetricNames.DB_POOL_UTILIZATION, "ratio", now,
                        0.31, 0.31, 0.31, 0.31, 0.31),
                series("order-service", MetricNames.JVM_HEAP_UTILIZATION, "ratio", now,
                        0.36, 0.36, 0.36, 0.36, 0.36),
                series("order-service", MetricNames.JVM_CPU_UTILIZATION, "ratio", now,
                        0.16, 0.16, 0.16, 0.16, 0.16));

        gateway.setLogs("order-service", List.of(
                log("order-service", "WARN", "Order request completed in 1850 ms", now),
                log("order-service", "WARN", "Order request completed in 1810 ms", now),
                log("order-service", "WARN", "Order request completed in 1890 ms", now)));

        gateway.setTraces(List.of(slowDependencyTrace(now)));
        gateway.setDeployments("order-service", List.of(new DeploymentView(
                "deployment-order", "order-service", "v1.8.4",
                now.minusSeconds(30 * 60), "SUCCESS", "Refactored order validation")));
    }

    private static ServiceTopology topology(Instant now, String degradedService) {
        return new ServiceTopology(List.of(
                new ServiceNode("order-service", "DEGRADED",
                        List.of("payment-service", "inventory-service", "shipping-service")),
                new ServiceNode("payment-service", statusFor("payment-service", degradedService), List.of()),
                new ServiceNode("inventory-service", statusFor("inventory-service", degradedService), List.of()),
                new ServiceNode("shipping-service", statusFor("shipping-service", degradedService), List.of())), now);
    }

    private static String statusFor(String service, String degradedService) {
        return service.equals(degradedService) ? "DEGRADED" : "HEALTHY";
    }

    private static TraceView poolTrace(Instant now) {
        String traceId = "trace-pool";
        Instant start = now.minusSeconds(30);
        return new TraceView(traceId, List.of(
                span(traceId, "span-order", null, "order-service", "POST /api/orders", start, 3_500, "OK"),
                span(traceId, "span-payment", "span-order", "payment-service",
                        "authorize-payment", start.plusMillis(40), 3_400, "ERROR"),
                span(traceId, "span-db", "span-payment", "payment-service",
                        "db.query", start.plusMillis(60), 3_360, "ERROR")));
    }

    private static TraceView orderTrace(Instant now, long orderDurationMs) {
        String traceId = "trace-order";
        Instant start = now.minusSeconds(40);
        return new TraceView(traceId, List.of(
                span(traceId, "span-order", null, "order-service", "POST /api/orders", start, orderDurationMs, "OK"),
                span(traceId, "span-payment", "span-order", "payment-service",
                        "authorize-payment", start.plusMillis(30), 25, "OK"),
                span(traceId, "span-inventory", "span-order", "inventory-service",
                        "reserve-stock", start.plusMillis(20), 22, "OK"),
                span(traceId, "span-shipping", "span-order", "shipping-service",
                        "create-shipment", start.plusMillis(60), 35, "OK")));
    }

    private static TraceView slowDependencyTrace(Instant now) {
        String traceId = "trace-slow";
        Instant start = now.minusSeconds(30);
        return new TraceView(traceId, List.of(
                span(traceId, "span-order", null, "order-service", "POST /api/orders", start, 1_850, "OK"),
                span(traceId, "span-inventory", "span-order", "inventory-service",
                        "reserve-stock", start.plusMillis(20), 1_778, "OK"),
                span(traceId, "span-payment", "span-order", "payment-service",
                        "authorize-payment", start.plusMillis(1_810), 24, "OK")));
    }

    private static SpanView span(
            String traceId,
            String spanId,
            String parentSpanId,
            String service,
            String operation,
            Instant start,
            long durationMs,
            String status) {
        return new SpanView(
                traceId, spanId, parentSpanId, service, operation, start, durationMs, status,
                Map.of("peer.service", service));
    }

    private static MetricSeries series(
            String service, String metric, String unit, Instant end, double... values) {
        List<MetricSample> samples = new ArrayList<>(values.length);
        for (int index = 0; index < values.length; index++) {
            long secondsAgo = (values.length - 1L - index) * 10L;
            samples.add(new MetricSample(end.minusSeconds(secondsAgo), values[index]));
        }
        return new MetricSeries(service, metric, unit, samples);
    }

    private static LogEntryView log(String service, String level, String message, Instant at) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return new LogEntryView(at, service, level, message, "trace-" + suffix, "span-" + suffix, "corr-" + suffix);
    }
}
