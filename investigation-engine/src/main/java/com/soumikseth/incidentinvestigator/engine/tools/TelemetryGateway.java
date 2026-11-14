package com.soumikseth.incidentinvestigator.engine.tools;

import com.soumikseth.incidentinvestigator.contracts.DeploymentView;
import com.soumikseth.incidentinvestigator.contracts.IncidentView;
import com.soumikseth.incidentinvestigator.contracts.LogEntryView;
import com.soumikseth.incidentinvestigator.contracts.MetricSeries;
import com.soumikseth.incidentinvestigator.contracts.ServiceHealth;
import com.soumikseth.incidentinvestigator.contracts.ServiceTopology;
import com.soumikseth.incidentinvestigator.contracts.TraceView;

import java.util.List;

/**
 * Read-only port over the telemetry and incident query surface. Keeping tools
 * behind this port is what makes the orchestrator testable without a live
 * simulator, Prometheus, Loki, or Tempo.
 */
public interface TelemetryGateway {

    ServiceTopology topology();

    ServiceHealth serviceHealth(String service);

    List<MetricSeries> metrics(String service, String metric, int minutes);

    List<LogEntryView> logs(String service, String contains, int minutes, int limit);

    List<TraceView> slowTraces(String service, long minDurationMs, int minutes, int limit);

    List<DeploymentView> deployments(String service, int minutes);

    IncidentView incident(String id);
}
