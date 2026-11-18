package com.soumikseth.incidentinvestigator.engine.support;

import com.soumikseth.incidentinvestigator.contracts.DeploymentView;
import com.soumikseth.incidentinvestigator.contracts.IncidentView;
import com.soumikseth.incidentinvestigator.contracts.LogEntryView;
import com.soumikseth.incidentinvestigator.contracts.MetricSeries;
import com.soumikseth.incidentinvestigator.contracts.ServiceHealth;
import com.soumikseth.incidentinvestigator.contracts.ServiceTopology;
import com.soumikseth.incidentinvestigator.contracts.TraceView;
import com.soumikseth.incidentinvestigator.engine.investigation.IncidentNotFoundException;
import com.soumikseth.incidentinvestigator.engine.tools.TelemetryGateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory telemetry double for engine tests.
 *
 * Using a fake gateway rather than a live simulator keeps the investigation
 * tests deterministic and fast. The fixtures still exercise the real tool
 * catalog, planner, evaluator, and persistence path.
 */
public class FakeTelemetryGateway implements TelemetryGateway {

    private IncidentView incident;
    private ServiceTopology topology;
    private final Map<String, ServiceHealth> health = new HashMap<>();
    private final Map<String, Map<String, MetricSeries>> metrics = new HashMap<>();
    private final Map<String, List<LogEntryView>> logs = new HashMap<>();
    private final List<TraceView> traces = new ArrayList<>();
    private final Map<String, List<DeploymentView>> deployments = new HashMap<>();
    private boolean failEverything;

    public void reset() {
        incident = null;
        topology = null;
        health.clear();
        metrics.clear();
        logs.clear();
        traces.clear();
        deployments.clear();
        failEverything = false;
    }

    public void failEverything() {
        this.failEverything = true;
    }

    public void setIncident(IncidentView incident) {
        this.incident = incident;
    }

    public void setTopology(ServiceTopology topology) {
        this.topology = topology;
    }

    public void setHealth(ServiceHealth serviceHealth) {
        health.put(serviceHealth.service(), serviceHealth);
    }

    public void setMetrics(String service, MetricSeries... series) {
        Map<String, MetricSeries> byMetric = metrics.computeIfAbsent(service, ignored -> new HashMap<>());
        for (MetricSeries current : series) {
            byMetric.put(current.metric(), current);
        }
    }

    public void setLogs(String service, List<LogEntryView> entries) {
        logs.put(service, List.copyOf(entries));
    }

    public void setTraces(List<TraceView> traceViews) {
        traces.clear();
        traces.addAll(traceViews);
    }

    public void setDeployments(String service, List<DeploymentView> deploymentViews) {
        deployments.put(service, List.copyOf(deploymentViews));
    }

    @Override
    public ServiceTopology topology() {
        guard();
        return topology;
    }

    @Override
    public ServiceHealth serviceHealth(String service) {
        guard();
        ServiceHealth result = health.get(service);
        if (result == null) {
            throw new IllegalArgumentException("No fixture health for " + service);
        }
        return result;
    }

    @Override
    public List<MetricSeries> metrics(String service, String metric, int minutes) {
        guard();
        MetricSeries series = metrics.getOrDefault(service, Map.of()).get(metric);
        return series == null ? List.of() : List.of(series);
    }

    @Override
    public List<LogEntryView> logs(String service, String contains, int minutes, int limit) {
        guard();
        String needle = contains == null ? "" : contains.toLowerCase();
        List<LogEntryView> matches = logs.getOrDefault(service, List.of()).stream()
                .filter(entry -> needle.isBlank() || entry.message().toLowerCase().contains(needle))
                .toList();
        int start = Math.max(0, matches.size() - limit);
        return matches.subList(start, matches.size());
    }

    @Override
    public List<TraceView> slowTraces(String service, long minDurationMs, int minutes, int limit) {
        guard();
        return traces.stream()
                .filter(trace -> trace.spans().stream().anyMatch(span -> span.service().equals(service)))
                .filter(trace -> trace.totalDurationMs() >= minDurationMs)
                .sorted(Comparator.comparingLong(TraceView::totalDurationMs).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<DeploymentView> deployments(String service, int minutes) {
        guard();
        Instant from = Instant.now().minusSeconds(minutes * 60L);
        return deployments.getOrDefault(service, List.of()).stream()
                .filter(deployment -> !deployment.deployedAt().isBefore(from))
                .toList();
    }

    @Override
    public IncidentView incident(String id) {
        guard();
        if (incident == null || !incident.id().equals(id)) {
            throw new IncidentNotFoundException(id);
        }
        return incident;
    }

    private void guard() {
        if (failEverything) {
            throw new IllegalStateException("Simulated telemetry backend is unavailable");
        }
    }
}
