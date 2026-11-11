package com.soumikseth.incidentinvestigator.simulator.runtime;

import com.soumikseth.incidentinvestigator.contracts.ServiceHealth;
import com.soumikseth.incidentinvestigator.contracts.ServiceNode;
import com.soumikseth.incidentinvestigator.contracts.ServiceTopology;
import com.soumikseth.incidentinvestigator.contracts.SpanView;
import com.soumikseth.incidentinvestigator.contracts.TraceView;
import com.soumikseth.incidentinvestigator.simulator.domain.FailureMode;
import com.soumikseth.incidentinvestigator.simulator.domain.ServiceName;
import com.soumikseth.incidentinvestigator.simulator.telemetry.TelemetryRecorder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Executes simulated order traffic across the logical services.
 *
 * The flow is sequential and realistic enough to demonstrate distributed
 * tracing: order reserves inventory, authorizes payment, then creates a shipment.
 * Payment failure short-circuits shipment creation. Every call produces a span,
 * a structured log line, and latency/error statistics for the owning service.
 *
 * Latency is simulated rather than actually slept. Sleeping would make traffic
 * generation slow and tests flaky; the reported durations are what matter for the
 * investigation, not wall-clock execution time.
 */
@Component
public class SimulatedEnvironment {

    private final Map<ServiceName, ServiceRuntime> services = new EnumMap<>(ServiceName.class);
    private final TelemetryRecorder recorder;

    public SimulatedEnvironment(TelemetryRecorder recorder) {
        this.recorder = recorder;
        for (ServiceName service : ServiceName.values()) {
            services.put(service, new ServiceRuntime(service));
        }
    }

    public ServiceRuntime runtime(ServiceName service) {
        return services.get(service);
    }

    public ServiceHealth health(ServiceName service) {
        ServiceRuntime runtime = runtime(service);
        return new ServiceHealth(
                service.slug(),
                runtime.status(),
                runtime.errorRate(),
                runtime.p99LatencyMs(),
                runtime.cpuUtilization(),
                runtime.heapUtilization(),
                runtime.connectionPoolUtilization(),
                runtime.consumerLag(),
                Instant.now());
    }

    public ServiceTopology topology() {
        List<ServiceNode> nodes = new ArrayList<>();
        for (ServiceName service : ServiceName.values()) {
            nodes.add(new ServiceNode(service.slug(), health(service).status(), service.dependencies()));
        }
        return new ServiceTopology(nodes, Instant.now());
    }

    public OrderOutcome generateOrder() {
        Instant requestStart = Instant.now();
        String traceId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        String orderSpanId = UUID.randomUUID().toString();
        List<SpanView> spans = new ArrayList<>();

        ServiceRuntime orderRuntime = runtime(ServiceName.ORDER);
        orderRuntime.advanceLeak();
        long orderOverheadMs = orderOverheadMs(orderRuntime);

        Instant cursor = requestStart;
        CallResult inventory = invoke(
                ServiceName.INVENTORY, "reserve-stock", "/inventory/reservations",
                traceId, orderSpanId, cursor, correlationId, spans);
        cursor = cursor.plusMillis(inventory.durationMs());

        CallResult payment = invoke(
                ServiceName.PAYMENT, "authorize-payment", "/payments/authorizations",
                traceId, orderSpanId, cursor, correlationId, spans);
        cursor = cursor.plusMillis(payment.durationMs());

        CallResult shipping = null;
        if (!payment.failed()) {
            shipping = invoke(
                    ServiceName.SHIPPING, "create-shipment", "/shipments",
                    traceId, orderSpanId, cursor, correlationId, spans);
        }

        long orderDurationMs = orderOverheadMs
                + inventory.durationMs()
                + payment.durationMs()
                + (shipping == null ? 0 : shipping.durationMs());
        boolean orderFailed = payment.failed()
                || (orderRuntime.failureMode() == FailureMode.MEMORY_LEAK
                    && ThreadLocalRandom.current().nextDouble() < 0.04);
        orderRuntime.recordRequest(orderDurationMs, orderFailed);

        // The order service does not call itself, so its GC pressure has to be
        // recorded here rather than in invoke(). Emitting every fourth overloaded
        // request keeps the signal visible without flooding the log store.
        if (orderRuntime.failureMode() == FailureMode.MEMORY_LEAK
                && orderRuntime.heapUtilization() > 0.85
                && orderRuntime.leakTicks() % 4 == 0) {
            recorder.recordLog(
                    ServiceName.ORDER,
                    "WARN",
                    "GC pause 380 ms (heap "
                            + Math.round(orderRuntime.heapUtilization() * 100) + "% used)",
                    traceId, orderSpanId, correlationId);
        }

        Map<String, String> orderAttributes = new LinkedHashMap<>();
        orderAttributes.put("http.method", "POST");
        orderAttributes.put("http.route", "/api/orders");
        orderAttributes.put("peer.service", ServiceName.ORDER.slug());
        if (orderFailed) {
            orderAttributes.put("error", "true");
        }
        spans.add(new SpanView(
                traceId, orderSpanId, null, ServiceName.ORDER.slug(), "POST /api/orders",
                requestStart, orderDurationMs, orderFailed ? "ERROR" : "OK", orderAttributes));

        recorder.recordTrace(new TraceView(traceId, List.copyOf(spans)));
        recorder.recordLog(
                ServiceName.ORDER,
                orderFailed ? "ERROR" : "INFO",
                orderFailed
                        ? "Order request failed after " + orderDurationMs + " ms"
                        : "Order request completed in " + orderDurationMs + " ms",
                traceId, orderSpanId, correlationId);

        return new OrderOutcome(traceId, !orderFailed, orderDurationMs);
    }

    public void reset() {
        for (ServiceRuntime runtime : services.values()) {
            runtime.reset();
        }
    }

    private long orderOverheadMs(ServiceRuntime orderRuntime) {
        long base = 6 + ThreadLocalRandom.current().nextLong(0, 8);
        if (orderRuntime.failureMode() == FailureMode.MEMORY_LEAK) {
            // GC pressure shows up as extra tail latency before the service degrades
            // into outright failures.
            return base + Math.min(900, 120 + orderRuntime.leakTicks() * 8)
                    + ThreadLocalRandom.current().nextLong(0, 80);
        }
        return base;
    }

    private CallResult invoke(
            ServiceName service,
            String operation,
            String route,
            String traceId,
            String parentSpanId,
            Instant start,
            String correlationId,
            List<SpanView> spans) {
        ServiceRuntime runtime = runtime(service);
        long durationMs = latencyMs(runtime);
        boolean failed = fails(runtime);

        String spanId = UUID.randomUUID().toString();
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("http.method", "POST");
        attributes.put("http.route", route);
        attributes.put("peer.service", service.slug());
        if (failed) {
            attributes.put("error", "true");
        }

        spans.add(new SpanView(
                traceId, spanId, parentSpanId, service.slug(), operation,
                start, durationMs, failed ? "ERROR" : "OK", attributes));
        runtime.recordRequest(durationMs, failed);

        addDependencySpans(service, operation, traceId, spanId, start, durationMs, failed, runtime, spans);

        String level = failed ? "ERROR" : (durationMs > 1_000 ? "WARN" : "INFO");
        String message = failed
                ? failureMessage(runtime, operation)
                : "Handled " + operation + " in " + durationMs + " ms";
        recorder.recordLog(service, level, message, traceId, spanId, correlationId);

        return new CallResult(durationMs, failed);
    }

    private void addDependencySpans(
            ServiceName service,
            String operation,
            String traceId,
            String spanId,
            Instant start,
            long durationMs,
            boolean failed,
            ServiceRuntime runtime,
            List<SpanView> spans) {
        if (service == ServiceName.PAYMENT
                && runtime.failureMode() == FailureMode.CONNECTION_POOL_EXHAUSTION) {
            Map<String, String> dbAttributes = new LinkedHashMap<>();
            dbAttributes.put("db.system", "postgresql");
            dbAttributes.put("db.operation", "select");
            dbAttributes.put("peer.service", "postgres");
            spans.add(new SpanView(
                    traceId,
                    UUID.randomUUID().toString(),
                    spanId,
                    service.slug(),
                    "db.query",
                    start.plusMillis(20),
                    Math.max(1, durationMs - 40),
                    failed ? "ERROR" : "OK",
                    dbAttributes));
        }
        if (service == ServiceName.SHIPPING && !failed) {
            spans.add(new SpanView(
                    traceId,
                    UUID.randomUUID().toString(),
                    spanId,
                    service.slug(),
                    "carrier-api.call",
                    start.plusMillis(10),
                    Math.max(1, durationMs - 15),
                    "OK",
                    Map.of("peer.service", "carrier-api", "http.method", "POST")));
        }
    }

    private long latencyMs(ServiceRuntime runtime) {
        long base = switch (runtime.service()) {
            case ORDER -> 6;
            case PAYMENT -> 24;
            case INVENTORY -> 21;
            case SHIPPING -> 34;
        };
        long jitter = ThreadLocalRandom.current().nextLong(0, 12);
        long injected = switch (runtime.failureMode()) {
            case CONNECTION_POOL_EXHAUSTION -> ThreadLocalRandom.current().nextLong(2_600, 3_400);
            case MEMORY_LEAK -> Math.min(900, 120 + runtime.leakTicks() * 8)
                    + ThreadLocalRandom.current().nextLong(0, 80);
            case SLOW_DEPENDENCY -> runtime.service() == ServiceName.INVENTORY
                    ? ThreadLocalRandom.current().nextLong(1_600, 2_100)
                    : 0;
            case NONE -> 0;
        };
        return base + jitter + injected;
    }

    private boolean fails(ServiceRuntime runtime) {
        double probability = switch (runtime.failureMode()) {
            case CONNECTION_POOL_EXHAUSTION -> 0.35;
            case MEMORY_LEAK -> 0.03;
            case SLOW_DEPENDENCY -> 0.01;
            case NONE -> 0.005;
        };
        return ThreadLocalRandom.current().nextDouble() < probability;
    }

    private String failureMessage(ServiceRuntime runtime, String operation) {
        return switch (runtime.failureMode()) {
            case CONNECTION_POOL_EXHAUSTION ->
                    "Connection acquisition timed out after 3000 ms while handling " + operation;
            case MEMORY_LEAK ->
                    "Request failed after GC pause exceeded 500 ms";
            case SLOW_DEPENDENCY ->
                    "Downstream call exceeded the 2000 ms deadline";
            case NONE ->
                    "Unexpected error while handling " + operation;
        };
    }

    public record OrderOutcome(String traceId, boolean success, long totalDurationMs) {
    }

    private record CallResult(long durationMs, boolean failed) {
    }
}
