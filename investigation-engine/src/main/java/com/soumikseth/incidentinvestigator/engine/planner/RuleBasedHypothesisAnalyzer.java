package com.soumikseth.incidentinvestigator.engine.planner;

import com.soumikseth.incidentinvestigator.contracts.DeploymentView;
import com.soumikseth.incidentinvestigator.contracts.IncidentView;
import com.soumikseth.incidentinvestigator.contracts.LogEntryView;
import com.soumikseth.incidentinvestigator.contracts.MetricNames;
import com.soumikseth.incidentinvestigator.contracts.MetricSample;
import com.soumikseth.incidentinvestigator.contracts.MetricSeries;
import com.soumikseth.incidentinvestigator.contracts.ServiceHealth;
import com.soumikseth.incidentinvestigator.contracts.ServiceTopology;
import com.soumikseth.incidentinvestigator.contracts.SpanView;
import com.soumikseth.incidentinvestigator.contracts.TraceView;
import com.soumikseth.incidentinvestigator.engine.domain.Evidence;
import com.soumikseth.incidentinvestigator.engine.domain.HypothesisCategory;
import com.soumikseth.incidentinvestigator.engine.domain.HypothesisProposal;
import com.soumikseth.incidentinvestigator.engine.investigation.ToolExecution;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Produces candidate hypotheses from the evidence that was actually collected.
 *
 * This is the deterministic baseline. It is intentionally rule-driven and
 * contains no scenario knowledge: it looks only at metrics, logs, traces, health,
 * and deployments. An LLM planner can take over hypothesis generation later while
 * keeping the same evidence model and evaluator.
 */
@Component
public class RuleBasedHypothesisAnalyzer {

    private static final double SATURATION_THRESHOLD = 0.90;

    /** A p99 at or above this is elevated; a lower sample cannot explain user impact. */
    private static final double ELEVATED_LATENCY_MS = 1_000;
    private static final double ELEVATED_CPU_THRESHOLD = 0.70;

    /** Below these thresholds the caller's own resources are ruled out as the origin. */
    private static final double NORMAL_POOL_THRESHOLD = 0.50;
    private static final double NORMAL_HEAP_THRESHOLD = 0.70;

    private static final long SLOW_SPAN_THRESHOLD_MS = (long) ELEVATED_LATENCY_MS;

    public List<HypothesisProposal> analyze(InvestigationContext context) {
        IncidentView incident = context.incident();
        String primary = incident.primaryService();
        List<ToolExecution> executions = context.executions();

        Map<String, List<MetricSeries>> metrics = metricsByMetric(executions);
        List<LogEntryView> logs = listData(executions, "search_logs");
        List<TraceView> traces = listData(executions, "find_slow_traces");
        List<DeploymentView> deployments = listData(executions, "get_recent_deployments");
        ResourceState resources = resourceState(executions, primary, metrics);

        List<HypothesisProposal> proposals = new ArrayList<>();

        if (resources.poolSaturated()) {
            proposals.add(connectionPoolHypothesis(executions, primary, resources, logs));
        }
        if (resources.heapSaturated()) {
            proposals.add(memoryPressureHypothesis(executions, primary, resources, logs));
        }

        Optional<SpanView> slowDownstream = slowestDownstreamSpan(executions, traces, primary);
        if (slowDownstream.isPresent()) {
            proposals.add(downstreamHypothesis(
                    executions, primary, slowDownstream.get(), resources, traces));
        }

        Optional<DeploymentView> recentDeployment = deploymentNearIncident(deployments, incident);
        if (recentDeployment.isPresent()) {
            proposals.add(deploymentHypothesis(executions, primary, recentDeployment.get(), proposals));
        }

        if (proposals.isEmpty()) {
            proposals.add(new HypothesisProposal(
                    "No single root cause is supported by the collected evidence for " + primary + ".",
                    HypothesisCategory.UNKNOWN,
                    List.of(),
                    List.of(),
                    List.of("Additional service-specific metrics or logs are required"),
                    List.of(primary)));
        }

        return proposals;
    }

    private HypothesisProposal connectionPoolHypothesis(
            List<ToolExecution> executions,
            String primary,
            ResourceState resources,
            List<LogEntryView> logs) {
        Set<String> supporting = new LinkedHashSet<>();

        if (resources.poolSaturatedInHealth()) {
            healthEvidenceId(executions, primary).ifPresent(supporting::add);
        }
        if (resources.poolSaturatedInMetrics()) {
            evidenceId(executions, evidence -> evidence.source().startsWith("metrics:")
                            && evidence.content().contains(MetricNames.DB_POOL_UTILIZATION))
                    .ifPresent(supporting::add);
        }
        evidenceId(executions, evidence -> evidence.source().startsWith("logs:")
                        && containsIgnoreCase(evidence.content(), "connection acquisition"))
                .ifPresent(supporting::add);
        if (resources.latencyElevatedInMetrics()) {
            evidenceId(executions, evidence -> evidence.content().contains(MetricNames.HTTP_P99_LATENCY_MS))
                    .ifPresent(supporting::add);
        }
        evidenceId(executions, evidence -> evidence.source().startsWith("traces:"))
                .ifPresent(supporting::add);

        List<String> missing = new ArrayList<>();
        if (logs.stream().noneMatch(log -> containsIgnoreCase(log.message(), "connection"))) {
            missing.add("Connection acquisition logs were not available");
        }
        if (!resources.latencyElevatedInMetrics()) {
            missing.add("Request latency metric samples did not show elevated latency");
        }

        return new HypothesisProposal(
                "Database connection pool exhaustion on " + primary,
                HypothesisCategory.CONNECTION_POOL_EXHAUSTION,
                List.copyOf(supporting),
                List.of(),
                missing,
                List.of(primary));
    }

    private HypothesisProposal memoryPressureHypothesis(
            List<ToolExecution> executions,
            String primary,
            ResourceState resources,
            List<LogEntryView> logs) {
        Set<String> supporting = new LinkedHashSet<>();

        if (resources.heapSaturatedInHealth()) {
            healthEvidenceId(executions, primary).ifPresent(supporting::add);
        }
        if (resources.heapSaturatedInMetrics()) {
            evidenceId(executions, evidence -> evidence.source().startsWith("metrics:")
                            && evidence.content().contains(MetricNames.JVM_HEAP_UTILIZATION))
                    .ifPresent(supporting::add);
        }
        evidenceId(executions, evidence -> evidence.source().startsWith("logs:")
                        && containsIgnoreCase(evidence.content(), "gc pause"))
                .ifPresent(supporting::add);
        if (resources.cpuElevated()) {
            evidenceId(executions, evidence -> evidence.content().contains(MetricNames.JVM_CPU_UTILIZATION))
                    .ifPresent(supporting::add);
        }
        if (resources.latencyElevatedInMetrics()) {
            evidenceId(executions, evidence -> evidence.content().contains(MetricNames.HTTP_P99_LATENCY_MS))
                    .ifPresent(supporting::add);
        }
        evidenceId(executions, evidence -> evidence.source().startsWith("traces:"))
                .ifPresent(supporting::add);

        List<String> missing = new ArrayList<>();
        if (logs.stream().noneMatch(log -> containsIgnoreCase(log.message(), "gc pause"))) {
            missing.add("GC pause logs were not available");
        }
        if (!resources.cpuObserved()) {
            missing.add("CPU utilization metrics were unavailable");
        }
        if (!resources.latencyElevatedInMetrics()) {
            missing.add("Request latency metric samples did not show elevated latency");
        }

        return new HypothesisProposal(
                "Memory pressure or a memory leak on " + primary,
                HypothesisCategory.MEMORY_PRESSURE,
                List.copyOf(supporting),
                List.of(),
                missing,
                List.of(primary));
    }

    private HypothesisProposal downstreamHypothesis(
            List<ToolExecution> executions,
            String primary,
            SpanView slowSpan,
            ResourceState resources,
            List<TraceView> traces) {
        Set<String> supporting = new LinkedHashSet<>();
        evidenceId(executions, evidence -> evidence.source().startsWith("traces:")
                        && evidence.content().contains(slowSpan.service() + ":" + slowSpan.operation()))
                .or(() -> evidenceId(executions, evidence -> evidence.source().startsWith("traces:")))
                .ifPresent(supporting::add);
        if (resources.latencyElevatedInMetrics()) {
            evidenceId(executions, evidence -> evidence.content().contains(MetricNames.HTTP_P99_LATENCY_MS))
                    .ifPresent(supporting::add);
        }
        healthEvidenceId(executions, primary).ifPresent(supporting::add);
        healthEvidenceId(executions, slowSpan.service()).ifPresent(supporting::add);
        // The topology edge is what makes the downstream service a plausible cause
        // rather than an unrelated service that happened to appear in a trace.
        evidenceId(executions, evidence -> evidence.source().equals("topology")
                        && evidence.content().contains(primary + " -> "))
                .ifPresent(supporting::add);

        // Normal caller resources are the observation that separates a dependency
        // problem from a local one. They support this hypothesis rather than
        // contradicting it: the caller is slow despite having spare capacity.
        if (resources.poolNormal()) {
            evidenceId(executions, evidence -> evidence.source().startsWith("metrics:")
                            && evidence.content().contains(MetricNames.DB_POOL_UTILIZATION))
                    .ifPresent(supporting::add);
        }
        if (resources.heapNormal()) {
            evidenceId(executions, evidence -> evidence.source().startsWith("metrics:")
                            && evidence.content().contains(MetricNames.JVM_HEAP_UTILIZATION))
                    .ifPresent(supporting::add);
        }

        List<String> missing = new ArrayList<>();
        if (traces.isEmpty()) {
            missing.add("No slow traces were available");
        }
        if (!resources.latencyElevatedInMetrics()) {
            missing.add("Request latency metric samples had not caught up with the incident");
        }

        return new HypothesisProposal(
                "Slow downstream dependency " + slowSpan.service()
                        + " is driving latency in " + primary,
                HypothesisCategory.DOWNSTREAM_DEPENDENCY_LATENCY,
                List.copyOf(supporting),
                List.of(),
                missing,
                List.of(primary, slowSpan.service()));
    }

    private HypothesisProposal deploymentHypothesis(
            List<ToolExecution> executions,
            String primary,
            DeploymentView deployment,
            List<HypothesisProposal> runtimeHypotheses) {
        Set<String> supporting = new LinkedHashSet<>();
        evidenceId(executions, evidence -> evidence.source().startsWith("deployments:")
                        && evidence.content().contains(deployment.version()))
                .ifPresent(supporting::add);

        Set<String> contradicting = new LinkedHashSet<>();
        for (HypothesisProposal runtime : runtimeHypotheses) {
            contradicting.addAll(runtime.supportingEvidenceIds());
        }

        return new HypothesisProposal(
                "Recent deployment " + deployment.version() + " on " + primary
                        + " may have introduced the regression",
                HypothesisCategory.DEPLOYMENT_REGRESSION,
                List.copyOf(supporting),
                List.copyOf(contradicting),
                List.of("Deployment diff and canary comparison were not available"),
                List.of(primary));
    }

    /**
     * Finds the slowest span that belongs to a declared dependency of the primary
     * service. Restricting candidates to the topology matters: a trace also contains
     * the caller's own span, and treating that as a "downstream dependency" would
     * produce a hypothesis pointing in the wrong direction.
     */
    private Optional<SpanView> slowestDownstreamSpan(
            List<ToolExecution> executions, List<TraceView> traces, String primary) {
        Set<String> dependencies = dependenciesOf(executions, primary);
        if (dependencies.isEmpty()) {
            return Optional.empty();
        }
        return traces.stream()
                .flatMap(trace -> trace.spans().stream())
                .filter(span -> dependencies.contains(span.service()))
                .filter(span -> span.durationMs() >= SLOW_SPAN_THRESHOLD_MS)
                .max(Comparator.comparingLong(SpanView::durationMs));
    }

    private Set<String> dependenciesOf(List<ToolExecution> executions, String primary) {
        return executions.stream()
                .filter(ToolExecution::successful)
                .filter(execution -> "get_service_topology".equals(execution.toolName()))
                .map(ToolExecution::data)
                .filter(ServiceTopology.class::isInstance)
                .map(ServiceTopology.class::cast)
                .flatMap(topology -> topology.services().stream())
                .filter(node -> node.name().equals(primary))
                .findFirst()
                .map(node -> Set.copyOf(node.dependencies()))
                .orElse(Set.of());
    }

    private Optional<DeploymentView> deploymentNearIncident(
            List<DeploymentView> deployments, IncidentView incident) {
        return deployments.stream()
                .filter(deployment -> !deployment.deployedAt().isAfter(incident.detectedAt()))
                .filter(deployment -> deployment.deployedAt()
                        .isAfter(incident.detectedAt().minus(Duration.ofMinutes(60))))
                .max(Comparator.comparing(DeploymentView::deployedAt));
    }

    private Optional<String> healthEvidenceId(List<ToolExecution> executions, String service) {
        return evidenceId(executions, evidence -> evidence.source().equals("health:" + service));
    }

    private ResourceState resourceState(
            List<ToolExecution> executions,
            String primary,
            Map<String, List<MetricSeries>> metrics) {
        return new ResourceState(
                healthData(executions, primary),
                maxMetric(metrics, MetricNames.DB_POOL_UTILIZATION),
                maxMetric(metrics, MetricNames.JVM_HEAP_UTILIZATION),
                maxMetric(metrics, MetricNames.JVM_CPU_UTILIZATION),
                maxMetric(metrics, MetricNames.HTTP_P99_LATENCY_MS));
    }

    private ServiceHealth healthData(List<ToolExecution> executions, String service) {
        return executions.stream()
                .filter(execution -> execution.successful()
                        && "get_service_health".equals(execution.toolName())
                        && service.equals(execution.arguments().get("service")))
                .map(ToolExecution::data)
                .filter(ServiceHealth.class::isInstance)
                .map(ServiceHealth.class::cast)
                .findFirst()
                .orElse(null);
    }

    private Optional<String> evidenceId(
            List<ToolExecution> executions, Predicate<Evidence> predicate) {
        return executions.stream()
                .flatMap(execution -> execution.evidence().stream())
                .filter(predicate)
                .map(Evidence::id)
                .findFirst();
    }

    private double maxMetric(Map<String, List<MetricSeries>> metrics, String metric) {
        return metrics.getOrDefault(metric, List.of()).stream()
                .flatMap(series -> series.samples().stream())
                .mapToDouble(MetricSample::value)
                .max()
                .orElse(0.0);
    }

    private Map<String, List<MetricSeries>> metricsByMetric(List<ToolExecution> executions) {
        Map<String, List<MetricSeries>> result = new HashMap<>();
        for (ToolExecution execution : executions) {
            if (!execution.successful() || !"query_metrics".equals(execution.toolName())) {
                continue;
            }
            Object metric = execution.arguments().get("metric");
            if (metric != null && execution.data() instanceof List<?> data) {
                @SuppressWarnings("unchecked")
                List<MetricSeries> series = (List<MetricSeries>) data;
                result.put(metric.toString(), series);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> listData(List<ToolExecution> executions, String toolName) {
        return executions.stream()
                .filter(execution -> execution.successful() && execution.toolName().equals(toolName))
                .map(ToolExecution::data)
                .filter(List.class::isInstance)
                .map(data -> (List<T>) data)
                .findFirst()
                .orElse(List.of());
    }

    private boolean containsIgnoreCase(String value, String fragment) {
        return value != null && value.toLowerCase().contains(fragment.toLowerCase());
    }

    /**
     * Freshest view of the primary service's resources.
     *
     * Health is queried live, while metric series are sampled on an interval, so
     * health can legitimately be ahead of the series. Saturation therefore uses the
     * higher of the two sources. The {@code *InMetrics} variants answer a different
     * question — whether a sampled point can honestly be cited as evidence — and are
     * used for citation rather than diagnosis.
     */
    private record ResourceState(
            ServiceHealth health,
            double poolMax,
            double heapMax,
            double cpuMax,
            double latencyMax) {

        double effectivePool() {
            return Math.max(poolMax, health == null ? 0.0 : health.connectionPoolUtilization());
        }

        double effectiveHeap() {
            return Math.max(heapMax, health == null ? 0.0 : health.heapUtilization());
        }

        double effectiveCpu() {
            return Math.max(cpuMax, health == null ? 0.0 : health.cpuUtilization());
        }

        boolean poolSaturated() {
            return effectivePool() >= SATURATION_THRESHOLD;
        }

        boolean heapSaturated() {
            return effectiveHeap() >= SATURATION_THRESHOLD;
        }

        boolean poolSaturatedInHealth() {
            return health != null && health.connectionPoolUtilization() >= SATURATION_THRESHOLD;
        }

        boolean heapSaturatedInHealth() {
            return health != null && health.heapUtilization() >= SATURATION_THRESHOLD;
        }

        boolean poolSaturatedInMetrics() {
            return poolMax >= SATURATION_THRESHOLD;
        }

        boolean heapSaturatedInMetrics() {
            return heapMax >= SATURATION_THRESHOLD;
        }

        boolean poolNormal() {
            return effectivePool() > 0 && effectivePool() < NORMAL_POOL_THRESHOLD;
        }

        boolean heapNormal() {
            return effectiveHeap() > 0 && effectiveHeap() < NORMAL_HEAP_THRESHOLD;
        }

        boolean cpuElevated() {
            return effectiveCpu() >= ELEVATED_CPU_THRESHOLD;
        }

        boolean cpuObserved() {
            return effectiveCpu() > 0;
        }

        boolean latencyElevatedInMetrics() {
            return latencyMax >= ELEVATED_LATENCY_MS;
        }
    }
}
