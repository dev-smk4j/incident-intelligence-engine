package com.soumikseth.incidentinvestigator.engine.investigation;

import com.soumikseth.incidentinvestigator.contracts.IncidentView;
import com.soumikseth.incidentinvestigator.engine.config.InvestigationProperties;
import com.soumikseth.incidentinvestigator.engine.domain.Evidence;
import com.soumikseth.incidentinvestigator.engine.domain.HypothesisAssessment;
import com.soumikseth.incidentinvestigator.engine.domain.HypothesisProposal;
import com.soumikseth.incidentinvestigator.engine.domain.IncidentReport;
import com.soumikseth.incidentinvestigator.engine.domain.Investigation;
import com.soumikseth.incidentinvestigator.engine.domain.InvestigationResult;
import com.soumikseth.incidentinvestigator.engine.domain.InvestigationStatus;
import com.soumikseth.incidentinvestigator.engine.domain.InvestigationStep;
import com.soumikseth.incidentinvestigator.engine.persistence.InvestigationRepository;
import com.soumikseth.incidentinvestigator.engine.planner.InvestigationContext;
import com.soumikseth.incidentinvestigator.engine.planner.InvestigationPlanner;
import com.soumikseth.incidentinvestigator.engine.planner.PlanDecision;
import com.soumikseth.incidentinvestigator.engine.tools.InvestigationTool;
import com.soumikseth.incidentinvestigator.engine.tools.TelemetryGateway;
import com.soumikseth.incidentinvestigator.engine.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs the bounded investigation loop and assembles the final report.
 *
 * The orchestrator, not the planner, owns every limit: step count, tool-call
 * count, wall-clock budget, per-call timeout, retry count, and consecutive-failure
 * budget. A planner can only choose an allowlisted tool or conclude, so prompt
 * injection in telemetry can at worst waste the budget; it cannot execute a
 * command or escape these bounds.
 *
 * Investigations are synchronous and bounded by design. A production system
 * would move this to a queue and stream progress; for a single-incident demo the
 * synchronous contract keeps the API simple and the behaviour observable.
 */
@Service
public class InvestigationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(InvestigationOrchestrator.class);

    private final TelemetryGateway gateway;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final InvestigationPlanner planner;
    private final HypothesisEvaluator evaluator;
    private final RecommendationGenerator recommendationGenerator;
    private final InvestigationRepository repository;
    private final InvestigationProperties properties;

    public InvestigationOrchestrator(
            TelemetryGateway gateway,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            InvestigationPlanner planner,
            HypothesisEvaluator evaluator,
            RecommendationGenerator recommendationGenerator,
            InvestigationRepository repository,
            InvestigationProperties properties) {
        this.gateway = gateway;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.planner = planner;
        this.evaluator = evaluator;
        this.recommendationGenerator = recommendationGenerator;
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * @throws IncidentNotFoundException if the simulator does not know the incident
     */
    public InvestigationResult investigate(String incidentId) {
        if (incidentId == null || incidentId.isBlank()) {
            throw new IllegalArgumentException("incidentId is required");
        }

        // Resolve the incident before allocating an investigation id so that a
        // missing incident is a clean 404 rather than a persisted failed run.
        IncidentView incident = gateway.incident(incidentId.trim());
        Instant createdAt = Instant.now();
        Investigation running = new Investigation(
                UUID.randomUUID().toString(),
                incident.id(),
                InvestigationStatus.INVESTIGATING,
                null,
                createdAt,
                null);

        EvidenceLedger ledger = new EvidenceLedger(running.id());
        List<InvestigationStep> steps = new ArrayList<>();
        List<ToolExecution> executions = new ArrayList<>();

        try {
            LoopResult loop = runLoop(running.id(), incident, ledger, steps, executions, createdAt);
            if (loop.failure() != null) {
                return fail(running, incident, steps, ledger, loop.failure());
            }

            List<HypothesisAssessment> hypotheses =
                    evaluator.evaluate(running.id(), loop.proposals(), ledger.all());
            IncidentReport report = buildReport(running.id(), incident, hypotheses, ledger.all(), executions.size());

            Investigation completed = new Investigation(
                    running.id(),
                    running.incidentId(),
                    InvestigationStatus.COMPLETED,
                    null,
                    createdAt,
                    Instant.now());
            InvestigationResult result =
                    new InvestigationResult(completed, incident, steps, ledger.all(), hypotheses, report);
            repository.save(result);

            log.info(
                    "Investigation {} completed for incident {}: {} tool calls, {} evidence items, top hypothesis '{}' at {}",
                    completed.id(),
                    incident.id(),
                    executions.size(),
                    ledger.all().size(),
                    hypotheses.isEmpty() ? "none" : hypotheses.getFirst().category(),
                    hypotheses.isEmpty() ? "n/a" : hypotheses.getFirst().confidence());
            return result;
        } catch (RuntimeException unexpected) {
            log.error("Investigation {} for incident {} failed unexpectedly", running.id(), incident.id(), unexpected);
            return fail(running, incident, steps, ledger, "Investigation failed due to an internal error");
        }
    }

    private LoopResult runLoop(
            String investigationId,
            IncidentView incident,
            EvidenceLedger ledger,
            List<InvestigationStep> steps,
            List<ToolExecution> executions,
            Instant createdAt) {

        Instant deadline = createdAt.plusSeconds(properties.maxDurationSeconds());
        int stepNumber = 0;
        int toolCalls = 0;
        int consecutiveFailures = 0;

        while (true) {
            if (stepNumber >= properties.maxSteps()) {
                return LoopResult.failed("Reached the maximum of " + properties.maxSteps()
                        + " investigation steps without a conclusion");
            }
            if (Instant.now().isAfter(deadline)) {
                return LoopResult.failed("Investigation exceeded its "
                        + properties.maxDurationSeconds() + " second time budget");
            }

            InvestigationContext context = new InvestigationContext(incident, ledger.all(), executions);
            PlanDecision decision = planner.next(context);

            if (decision instanceof PlanDecision.Conclude conclude) {
                return LoopResult.concluded(conclude.hypotheses());
            }

            PlanDecision.CallTool call = (PlanDecision.CallTool) decision;
            if (toolCalls >= properties.maxToolCalls()) {
                return LoopResult.failed("Reached the maximum of " + properties.maxToolCalls()
                        + " tool calls without a conclusion");
            }
            toolCalls++;
            stepNumber++;

            Instant stepStartedAt = Instant.now();
            Optional<InvestigationTool> tool = toolRegistry.find(call.toolName());
            ToolExecution execution = tool
                    .map(allowed -> toolExecutor.execute(allowed, call.arguments(), ledger))
                    .orElseGet(() -> ToolExecution.failed(
                            call.toolName(),
                            call.arguments(),
                            "Tool is not allowlisted: " + TelemetrySanitizer.content(call.toolName()),
                            stepStartedAt,
                            Instant.now()));

            executions.add(execution);
            steps.add(new InvestigationStep(
                    UUID.randomUUID().toString(),
                    investigationId,
                    stepNumber,
                    execution.toolName(),
                    execution.status(),
                    TelemetrySanitizer.content(execution.summary()),
                    execution.startedAt(),
                    execution.completedAt()));

            consecutiveFailures = execution.successful() ? 0 : consecutiveFailures + 1;
            if (consecutiveFailures >= properties.maxConsecutiveFailures()) {
                return LoopResult.failed("Aborted after " + consecutiveFailures
                        + " consecutive tool failures; last error: "
                        + TelemetrySanitizer.content(execution.summary()));
            }
        }
    }

    private IncidentReport buildReport(
            String investigationId,
            IncidentView incident,
            List<HypothesisAssessment> hypotheses,
            List<Evidence> evidence,
            int toolCalls) {

        String summary;
        if (hypotheses.isEmpty()) {
            summary = String.format(
                    Locale.ROOT,
                    "Investigation of %s on %s completed without a supported root-cause hypothesis "
                            + "after %d tool calls and %d evidence items.",
                    incident.id(),
                    incident.primaryService(),
                    toolCalls,
                    evidence.size());
        } else {
            HypothesisAssessment top = hypotheses.getFirst();
            summary = String.format(
                    Locale.ROOT,
                    "Most likely cause for the incident on %s: %s (evidence score %.2f, rank %d of %d). "
                            + "Derived from %d tool calls and %d evidence items.",
                    incident.primaryService(),
                    top.statement(),
                    top.confidence(),
                    top.rank(),
                    hypotheses.size(),
                    toolCalls,
                    evidence.size());
        }

        return new IncidentReport(
                investigationId,
                incident.id(),
                summary,
                hypotheses,
                recommendationGenerator.generate(hypotheses),
                Instant.now());
    }

    private InvestigationResult fail(
            Investigation running,
            IncidentView incident,
            List<InvestigationStep> steps,
            EvidenceLedger ledger,
            String failureMessage) {

        Investigation failed = new Investigation(
                running.id(),
                running.incidentId(),
                InvestigationStatus.FAILED,
                TelemetrySanitizer.content(failureMessage),
                running.createdAt(),
                Instant.now());
        InvestigationResult result =
                new InvestigationResult(failed, incident, steps, ledger.all(), List.of(), null);

        // A failure must still be visible to an operator, but a persistence problem
        // must not replace the original failure with a second exception.
        try {
            repository.save(result);
        } catch (RuntimeException persistenceFailure) {
            log.error("Could not persist failed investigation {}", failed.id(), persistenceFailure);
        }
        log.warn("Investigation {} for incident {} failed: {}", failed.id(), incident.id(), failureMessage);
        return result;
    }

    private record LoopResult(List<HypothesisProposal> proposals, String failure) {

        static LoopResult concluded(List<HypothesisProposal> proposals) {
            return new LoopResult(List.copyOf(proposals), null);
        }

        static LoopResult failed(String failure) {
            return new LoopResult(List.of(), failure);
        }
    }
}
