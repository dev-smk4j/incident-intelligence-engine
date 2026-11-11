package com.soumikseth.incidentinvestigator.simulator.telemetry;

import com.soumikseth.incidentinvestigator.contracts.LogEntryView;
import com.soumikseth.incidentinvestigator.contracts.TraceView;
import com.soumikseth.incidentinvestigator.simulator.domain.ServiceName;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Writes telemetry to the queryable in-memory store and to the standard
 * observability surface (Micrometer/Actuator Prometheus endpoint).
 *
 * The Prometheus gauges are updated in place rather than registered per sample;
 * Micrometer meters are long-lived objects and re-registering them would grow the
 * registry without bound.
 */
@Component
public class TelemetryRecorder {

    private static final Logger telemetryLog = LoggerFactory.getLogger("telemetry");

    private final TelemetryStore store;
    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicReference<Double>> gauges = new ConcurrentHashMap<>();

    public TelemetryRecorder(TelemetryStore store, MeterRegistry meterRegistry) {
        this.store = store;
        this.meterRegistry = meterRegistry;
    }

    public void recordMetric(ServiceName service, String metric, String unit, double value, Instant at) {
        store.addMetric(service.slug(), metric, unit, at, value);

        String key = service.slug() + '|' + metric;
        AtomicReference<Double> holder = gauges.computeIfAbsent(key, ignored -> registerGauge(service, metric, value));
        holder.set(value);
    }

    public void recordLog(
            ServiceName service, String level, String message, String traceId, String spanId, String correlationId) {
        LogEntryView entry = new LogEntryView(
                Instant.now(), service.slug(), level, message, traceId, spanId, correlationId);
        store.addLog(entry);

        // The message is simulator-generated, but it is still passed as an argument
        // rather than interpolated into the format string. That keeps logging safe
        // if a future scenario includes user-supplied content.
        switch (level) {
            case "ERROR" -> telemetryLog.error("service={} traceId={} spanId={} message={}",
                    service.slug(), traceId, spanId, message);
            case "WARN" -> telemetryLog.warn("service={} traceId={} spanId={} message={}",
                    service.slug(), traceId, spanId, message);
            default -> telemetryLog.info("service={} traceId={} spanId={} message={}",
                    service.slug(), traceId, spanId, message);
        }
    }

    public void recordTrace(TraceView trace) {
        store.addTrace(trace);
    }

    private AtomicReference<Double> registerGauge(ServiceName service, String metric, double initialValue) {
        AtomicReference<Double> holder = new AtomicReference<>(initialValue);
        Gauge.builder("sim." + metric, holder, ref -> {
                    Double current = ref.get();
                    return current == null ? 0.0 : current;
                })
                .description("Simulated telemetry: " + metric)
                .tag("service", service.slug())
                .register(meterRegistry);
        return holder;
    }
}
