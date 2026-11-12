package com.soumikseth.incidentinvestigator.simulator.detection;

import com.soumikseth.incidentinvestigator.contracts.IncidentView;
import com.soumikseth.incidentinvestigator.contracts.ServiceHealth;
import com.soumikseth.incidentinvestigator.simulator.config.DetectionProperties;
import com.soumikseth.incidentinvestigator.simulator.domain.ServiceName;
import com.soumikseth.incidentinvestigator.simulator.incident.IncidentService;
import com.soumikseth.incidentinvestigator.simulator.runtime.SimulatedEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Converts observed service health into incidents.
 *
 * This is deliberately a threshold detector, not a scenario lookup. It sees
 * only the same health snapshot an on-call engineer would see. The incident text
 * describes the symptom and the observed numbers; it never names the injected
 * failure or its target.
 *
 * The "worst" service is ranked by a weighted score rather than by error rate
 * alone. That matters for cascading failures: an upstream service often has a
 * higher total latency than the downstream service that actually caused it.
 */
@Component
public class AnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);

    private final SimulatedEnvironment environment;
    private final IncidentService incidentService;
    private final DetectionProperties properties;

    public AnomalyDetector(
            SimulatedEnvironment environment,
            IncidentService incidentService,
            DetectionProperties properties) {
        this.environment = environment;
        this.incidentService = incidentService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${simulator.detection.interval-ms:5000}")
    public void detectOnSchedule() {
        if (!properties.enabled()) {
            return;
        }
        detectAndCreateIncident().ifPresent(incident ->
                log.warn("Incident detected: id={} service={} severity={}",
                        incident.id(), incident.primaryService(), incident.severity()));
    }

    public Optional<IncidentView> detectAndCreateIncident() {
        ServiceHealth worst = null;
        double worstScore = Double.NEGATIVE_INFINITY;

        for (ServiceName service : ServiceName.values()) {
            ServiceHealth health = environment.health(service);
            if (!isAnomalous(health)) {
                continue;
            }
            double score = severityScore(health);
            if (score > worstScore) {
                worstScore = score;
                worst = health;
            }
        }

        if (worst == null) {
            return Optional.empty();
        }

        String severity = severityFor(worst);
        String title = "Sustained degradation detected on " + worst.service();
        String description = String.format(
                Locale.ROOT,
                "Automated detection based on recent telemetry: error rate %.1f%%, "
                        + "p99 latency %.0f ms, CPU utilization %.0f%%. Severity: %s.",
                worst.errorRate() * 100,
                worst.p99LatencyMs(),
                worst.cpuUtilization() * 100,
                severity);

        return Optional.of(incidentService.createIfAbsent(
                worst.service(), title, description, severity));
    }

    private boolean isAnomalous(ServiceHealth health) {
        return health.errorRate() >= properties.errorRateThreshold()
                || health.p99LatencyMs() >= properties.latencyThresholdMs()
                || health.heapUtilization() >= properties.heapThreshold()
                || health.connectionPoolUtilization() >= properties.poolThreshold();
    }

    private double severityScore(ServiceHealth health) {
        // Resource saturation is weighted heavily. A saturated downstream service
        // should outrank an upstream service whose latency merely reflects the
        // cascade; otherwise every dependency incident is blamed on the caller.
        return health.p99LatencyMs()
                + health.errorRate() * 1_000
                + health.heapUtilization() * 500
                + health.connectionPoolUtilization() * 1_000;
    }

    private String severityFor(ServiceHealth health) {
        if (health.errorRate() >= 0.40 || health.p99LatencyMs() >= 3_000) {
            return "CRITICAL";
        }
        if (health.errorRate() >= 0.15 || health.p99LatencyMs() >= 2_000) {
            return "HIGH";
        }
        return "MEDIUM";
    }
}
