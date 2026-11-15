package com.soumikseth.incidentinvestigator.engine.planner;

import com.soumikseth.incidentinvestigator.contracts.MetricNames;
import com.soumikseth.incidentinvestigator.contracts.ServiceTopology;
import com.soumikseth.incidentinvestigator.contracts.SpanView;
import com.soumikseth.incidentinvestigator.contracts.TraceView;
import com.soumikseth.incidentinvestigator.engine.investigation.ToolExecution;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic planner used as the baseline investigation strategy.
 *
 * The plan is a fixed breadth-first sweep of the signals an on-call engineer
 * would inspect: dependency topology, the primary service's health, its five
 * core metrics, logs, slow traces, and recent deployments. One step is adaptive:
 * once traces are available, the slowest downstream service is identified and its
 * health is queried as well.
 *
 * This class never reads scenario or failure-mode data. It only knows tool
 * names and the incident's primary service, both of which are operator-visible
 * facts. A Spring AI planner can replace it behind {@link InvestigationPlanner}
 * without changing the tool layer or the evaluator.
 */
@Component
public class ScriptedInvestigationPlanner implements InvestigationPlanner {

    private static final long SLOW_SPAN_THRESHOLD_MS = 1_000;

    private static final List<String> PRIMARY_METRICS = List.of(
            MetricNames.HTTP_ERROR_RATE,
            MetricNames.HTTP_P99_LATENCY_MS,
            MetricNames.DB_POOL_UTILIZATION,
            MetricNames.JVM_HEAP_UTILIZATION,
            MetricNames.JVM_CPU_UTILIZATION);

    private final RuleBasedHypothesisAnalyzer analyzer;

    public ScriptedInvestigationPlanner(RuleBasedHypothesisAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public PlanDecision next(InvestigationContext context) {
        for (Query query : buildPlan(context)) {
            if (!alreadyExecuted(context.executions(), query)) {
                return new PlanDecision.CallTool(query.toolName(), query.arguments());
            }
        }
        return new PlanDecision.Conclude(analyzer.analyze(context));
    }

    private List<Query> buildPlan(InvestigationContext context) {
        String primary = context.incident().primaryService();
        List<Query> plan = new ArrayList<>();

        plan.add(new Query("get_service_topology", Map.of()));
        plan.add(new Query("get_service_health", Map.of("service", primary)));

        for (String metric : PRIMARY_METRICS) {
            plan.add(new Query("query_metrics", Map.of("service", primary, "metric", metric)));
        }

        plan.add(new Query("search_logs", Map.of("service", primary)));
        plan.add(new Query("find_slow_traces", Map.of("service", primary, "minDurationMs", 500)));
        plan.add(new Query("get_recent_deployments", Map.of("service", primary)));

        // Only add this step once traces exist. Otherwise the planner would have
        // to guess which dependency to inspect, which would bias the investigation.
        slowestDependencyService(context)
                .ifPresent(service -> plan.add(new Query(
                        "get_service_health", Map.of("service", service))));

        return plan;
    }

    private boolean alreadyExecuted(List<ToolExecution> executions, Query query) {
        // Failed attempts still count. Retrying a permanently broken tool on every
        // planner turn would consume the step budget without adding information.
        return executions.stream()
                .anyMatch(execution -> execution.toolName().equals(query.toolName())
                        && execution.arguments().equals(query.arguments()));
    }

    private Optional<String> slowestDependencyService(InvestigationContext context) {
        Set<String> dependencies = dependenciesOf(context);
        if (dependencies.isEmpty()) {
            return Optional.empty();
        }
        return collectedTraces(context.executions()).stream()
                .flatMap(trace -> trace.spans().stream())
                .filter(span -> dependencies.contains(span.service()))
                .filter(span -> span.durationMs() >= SLOW_SPAN_THRESHOLD_MS)
                .max(Comparator.comparingLong(SpanView::durationMs))
                .map(SpanView::service);
    }

    private Set<String> dependenciesOf(InvestigationContext context) {
        String primary = context.incident().primaryService();
        return context.executions().stream()
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

    private List<TraceView> collectedTraces(List<ToolExecution> executions) {
        for (ToolExecution execution : executions) {
            if (!execution.successful()
                    || !"find_slow_traces".equals(execution.toolName())
                    || !(execution.data() instanceof List<?> raw)) {
                continue;
            }
            List<TraceView> traces = new ArrayList<>(raw.size());
            for (Object item : raw) {
                if (item instanceof TraceView trace) {
                    traces.add(trace);
                }
            }
            return traces;
        }
        return List.of();
    }

    private record Query(String toolName, Map<String, Object> arguments) {
    }
}
