package com.soumikseth.incidentinvestigator.simulator.api;

import com.soumikseth.incidentinvestigator.contracts.DeploymentView;
import com.soumikseth.incidentinvestigator.contracts.LogEntryView;
import com.soumikseth.incidentinvestigator.contracts.MetricSeries;
import com.soumikseth.incidentinvestigator.contracts.TraceView;
import com.soumikseth.incidentinvestigator.simulator.deployment.JdbcDeploymentRepository;
import com.soumikseth.incidentinvestigator.simulator.telemetry.TelemetryStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Read-only telemetry query surface used by the investigation engine.
 *
 * Every endpoint is a query. There is no endpoint that reveals the active
 * scenario, the injected failure mode, or the internal target service.
 */
@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TelemetryStore store;
    private final JdbcDeploymentRepository deployments;

    public TelemetryController(TelemetryStore store, JdbcDeploymentRepository deployments) {
        this.store = store;
        this.deployments = deployments;
    }

    @GetMapping("/metrics")
    public List<MetricSeries> metrics(
            @RequestParam String service,
            @RequestParam(required = false) String metric,
            @RequestParam(defaultValue = "15") int minutes) {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(clampMinutes(minutes)));
        return store.queryMetrics(service, metric, from, to);
    }

    @GetMapping("/logs")
    public List<LogEntryView> logs(
            @RequestParam String service,
            @RequestParam(required = false) String contains,
            @RequestParam(defaultValue = "15") int minutes,
            @RequestParam(defaultValue = "100") int limit) {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(clampMinutes(minutes)));
        return store.queryLogs(service, from, to, contains, Math.clamp(limit, 1, 500));
    }

    @GetMapping("/traces")
    public List<TraceView> traces(
            @RequestParam String service,
            @RequestParam(defaultValue = "15") int minutes,
            @RequestParam(defaultValue = "0") long minDurationMs,
            @RequestParam(defaultValue = "20") int limit) {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(clampMinutes(minutes)));
        return store.findTraces(service, from, to, Math.max(0, minDurationMs), Math.clamp(limit, 1, 100));
    }

    @GetMapping("/traces/{traceId}")
    public TraceView trace(@PathVariable String traceId) {
        return store.findTrace(traceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trace not found"));
    }

    @GetMapping("/deployments")
    public List<DeploymentView> deployments(
            @RequestParam(required = false) String service,
            @RequestParam(defaultValue = "1440") int minutes) {
        Instant from = Instant.now().minus(Duration.ofMinutes(clampMinutes(minutes)));
        List<DeploymentView> result = (service == null || service.isBlank())
                ? deployments.findRecent(from, 100).stream().map(deployment -> deployment.toView()).toList()
                : deployments.findByService(service, from).stream().map(deployment -> deployment.toView()).toList();
        return result;
    }

    private int clampMinutes(int minutes) {
        return Math.clamp(minutes, 1, 10_080);
    }
}
