package com.soumikseth.incidentinvestigator.engine.tools;

import com.soumikseth.incidentinvestigator.contracts.DeploymentView;
import com.soumikseth.incidentinvestigator.contracts.LogEntryView;
import com.soumikseth.incidentinvestigator.contracts.MetricSample;
import com.soumikseth.incidentinvestigator.contracts.MetricSeries;
import com.soumikseth.incidentinvestigator.contracts.ServiceHealth;
import com.soumikseth.incidentinvestigator.contracts.ServiceTopology;
import com.soumikseth.incidentinvestigator.contracts.TraceView;
import com.soumikseth.incidentinvestigator.engine.domain.EvidenceType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The complete, read-only tool catalog exposed to a planner.
 *
 * Every tool is a query. There is no shell, no filesystem, no write endpoint,
 * and no scenario or failure-mode lookup. A planner can only request these named
 * operations, and the orchestrator still enforces the allowlist.
 */
@Component
public class TelemetryToolCatalog {

    private static final int DEFAULT_WINDOW_MINUTES = 15;
    private static final int DEFAULT_LOG_LIMIT = 50;
    private static final int MAX_EVIDENCE_ITEMS_PER_TOOL = 5;

    private final TelemetryGateway gateway;

    public TelemetryToolCatalog(TelemetryGateway gateway) {
        this.gateway = gateway;
    }

    public List<InvestigationTool> tools() {
        return List.of(
                new SimpleTool(
                        "get_service_topology",
                        "Returns the service dependency graph and current status.",
                        args -> topology()),
                new SimpleTool(
                        "get_service_health",
                        "Returns health, error rate, p99 latency, CPU, heap, connection pool, and consumer lag for a service.",
                        args -> health(requireString(args, "service"))),
                new SimpleTool(
                        "query_metrics",
                        "Returns a metric time series for a service. Metric names are stable constants such as "
                                + "http.server.error.rate, http.server.latency.p99, jvm.cpu.utilization, "
                                + "jvm.heap.utilization, db.connection.pool.utilization, kafka.consumer.lag.",
                        args -> metrics(requireString(args, "service"), requireString(args, "metric"))),
                new SimpleTool(
                        "search_logs",
                        "Returns recent structured logs for a service, optionally filtered by a text fragment.",
                        args -> logs(requireString(args, "service"), optionalString(args, "contains"))),
                new SimpleTool(
                        "find_slow_traces",
                        "Returns the slowest recent traces that include the service, including child spans.",
                        args -> slowTraces(
                                requireString(args, "service"),
                                optionalLong(args, "minDurationMs", 500L))),
                new SimpleTool(
                        "get_recent_deployments",
                        "Returns recent deployments for a service so incidents can be correlated with releases.",
                        args -> deployments(requireString(args, "service"))));
    }

    private ToolResult topology() {
        ServiceTopology topology = gateway.topology();
        String summary = topology.services().stream()
                .map(service -> service.name()
                        + "=" + service.status()
                        + (service.dependencies().isEmpty()
                                ? ""
                                : " -> " + String.join(",", service.dependencies())))
                .collect(Collectors.joining("; "));
        return new ToolResult(
                "Topology with " + topology.services().size() + " services",
                topology,
                List.of(new EvidenceDraft(EvidenceType.OBSERVED_FACT, "topology", summary)));
    }

    private ToolResult health(String service) {
        ServiceHealth health = gateway.serviceHealth(service);
        String content = String.format(
                Locale.ROOT,
                "%s status=%s errorRate=%.3f p99=%.0fms cpu=%.2f heap=%.2f pool=%.2f consumerLag=%d",
                health.service(),
                health.status(),
                health.errorRate(),
                health.p99LatencyMs(),
                health.cpuUtilization(),
                health.heapUtilization(),
                health.connectionPoolUtilization(),
                health.consumerLag());
        return new ToolResult(
                "Health for " + service + ": " + health.status(),
                health,
                List.of(new EvidenceDraft(EvidenceType.OBSERVED_FACT, "health:" + service, content)));
    }

    private ToolResult metrics(String service, String metric) {
        List<MetricSeries> series = gateway.metrics(service, metric, DEFAULT_WINDOW_MINUTES);
        List<EvidenceDraft> drafts = new ArrayList<>();
        StringBuilder summary = new StringBuilder();

        for (MetricSeries current : series) {
            double latest = current.samples().isEmpty()
                    ? 0.0
                    : current.samples().getLast().value();
            double max = current.samples().stream()
                    .mapToDouble(MetricSample::value)
                    .max()
                    .orElse(0.0);
            String content = String.format(
                    Locale.ROOT,
                    "%s %s: latest=%.3f max=%.3f samples=%d",
                    service, current.metric(), latest, max, current.samples().size());
            drafts.add(new EvidenceDraft(EvidenceType.OBSERVED_FACT, "metrics:" + service, content));
            summary.append(content).append("; ");
        }

        if (drafts.isEmpty()) {
            String content = "No samples for " + service + " metric " + metric;
            drafts.add(new EvidenceDraft(EvidenceType.OBSERVED_FACT, "metrics:" + service, content));
            summary.append(content);
        }
        return new ToolResult(summary.toString(), series, drafts);
    }

    private ToolResult logs(String service, String contains) {
        List<LogEntryView> logs = gateway.logs(service, contains, DEFAULT_WINDOW_MINUTES, DEFAULT_LOG_LIMIT);
        List<EvidenceDraft> drafts = logs.stream()
                .limit(MAX_EVIDENCE_ITEMS_PER_TOOL)
                .map(entry -> new EvidenceDraft(
                        EvidenceType.OBSERVED_FACT,
                        "logs:" + service,
                        entry.level() + " " + entry.message()))
                .toList();

        if (drafts.isEmpty()) {
            drafts = List.of(new EvidenceDraft(
                    EvidenceType.OBSERVED_FACT, "logs:" + service, "No matching log entries"));
        }
        return new ToolResult("Retrieved " + logs.size() + " log entries for " + service, logs, drafts);
    }

    private ToolResult slowTraces(String service, long minDurationMs) {
        List<TraceView> traces = gateway.slowTraces(service, minDurationMs, DEFAULT_WINDOW_MINUTES, 10);
        List<EvidenceDraft> drafts = traces.stream()
                .limit(3)
                .map(trace -> new EvidenceDraft(
                        EvidenceType.OBSERVED_FACT,
                        "traces:" + service,
                        "trace " + trace.traceId() + " total=" + trace.totalDurationMs() + "ms spans=["
                                + trace.spans().stream()
                                .map(span -> span.service() + ":" + span.operation()
                                        + "=" + span.durationMs() + "ms/" + span.status())
                                .collect(Collectors.joining(", "))
                                + "]"))
                .toList();

        if (drafts.isEmpty()) {
            drafts = List.of(new EvidenceDraft(
                    EvidenceType.OBSERVED_FACT, "traces:" + service, "No slow traces found"));
        }
        return new ToolResult("Retrieved " + traces.size() + " slow traces for " + service, traces, drafts);
    }

    private ToolResult deployments(String service) {
        List<DeploymentView> deployments = gateway.deployments(service, 1_440);
        List<EvidenceDraft> drafts = deployments.stream()
                .limit(MAX_EVIDENCE_ITEMS_PER_TOOL)
                .map(deployment -> new EvidenceDraft(
                        EvidenceType.OBSERVED_FACT,
                        "deployments:" + service,
                        deployment.version() + " deployedAt=" + deployment.deployedAt()
                                + " status=" + deployment.status()
                                + " summary=" + deployment.summary()))
                .toList();

        if (drafts.isEmpty()) {
            drafts = List.of(new EvidenceDraft(
                    EvidenceType.OBSERVED_FACT, "deployments:" + service, "No recent deployments"));
        }
        return new ToolResult("Retrieved " + deployments.size() + " deployments for " + service, deployments, drafts);
    }

    private static String requireString(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return value.toString();
    }

    private static String optionalString(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? null : value.toString();
    }

    private static long optionalLong(Map<String, Object> arguments, String key, long defaultValue) {
        Object value = arguments.get(key);
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(value.toString());
    }

    private record SimpleTool(
            String name, String description, Function<Map<String, Object>, ToolResult> handler)
            implements InvestigationTool {

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            return handler.apply(arguments);
        }
    }
}
