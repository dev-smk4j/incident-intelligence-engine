package com.soumikseth.incidentinvestigator.simulator.telemetry;

import com.soumikseth.incidentinvestigator.contracts.MetricNames;
import com.soumikseth.incidentinvestigator.simulator.domain.ServiceName;
import com.soumikseth.incidentinvestigator.simulator.runtime.ServiceRuntime;
import com.soumikseth.incidentinvestigator.simulator.runtime.SimulatedEnvironment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Periodically snapshots service state into the metric store.
 *
 * Metrics are sampled independently of traffic so that a quiet service still
 * reports its baseline resource usage. This mirrors a scrape-based monitoring
 * system rather than recording a point only when a request happens.
 */
@Component
public class TelemetrySampler {

    private final SimulatedEnvironment environment;
    private final TelemetryRecorder recorder;

    public TelemetrySampler(SimulatedEnvironment environment, TelemetryRecorder recorder) {
        this.environment = environment;
        this.recorder = recorder;
    }

    @Scheduled(fixedDelayString = "${simulator.telemetry.sample-interval-ms:2000}")
    public void sample() {
        Instant now = Instant.now();
        for (ServiceName service : ServiceName.values()) {
            ServiceRuntime runtime = environment.runtime(service);
            recorder.recordMetric(service, MetricNames.HTTP_ERROR_RATE, "ratio", runtime.errorRate(), now);
            recorder.recordMetric(service, MetricNames.HTTP_P99_LATENCY_MS, "ms", runtime.p99LatencyMs(), now);
            recorder.recordMetric(service, MetricNames.JVM_CPU_UTILIZATION, "ratio", runtime.cpuUtilization(), now);
            recorder.recordMetric(service, MetricNames.JVM_HEAP_UTILIZATION, "ratio", runtime.heapUtilization(), now);
            recorder.recordMetric(service, MetricNames.DB_POOL_UTILIZATION, "ratio",
                    runtime.connectionPoolUtilization(), now);
            recorder.recordMetric(service, MetricNames.KAFKA_CONSUMER_LAG, "messages", runtime.consumerLag(), now);
        }
    }
}
