package com.soumikseth.incidentinvestigator.engine.api;

import com.soumikseth.incidentinvestigator.engine.domain.HypothesisAssessment;
import com.soumikseth.incidentinvestigator.engine.domain.IncidentReport;
import com.soumikseth.incidentinvestigator.engine.domain.InvestigationResult;
import com.soumikseth.incidentinvestigator.engine.investigation.InvestigationOrchestrator;
import com.soumikseth.incidentinvestigator.engine.persistence.InvestigationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/investigations")
public class InvestigationController {

    private final InvestigationOrchestrator orchestrator;
    private final InvestigationRepository repository;

    public InvestigationController(
            InvestigationOrchestrator orchestrator, InvestigationRepository repository) {
        this.orchestrator = orchestrator;
        this.repository = repository;
    }

    /**
     * Runs an investigation synchronously. The run is bounded by the configured
     * step, tool-call, and time budgets, so the request cannot hang indefinitely.
     */
    @PostMapping
    public ResponseEntity<InvestigationResult> investigate(@RequestBody CreateInvestigationRequest request) {
        InvestigationResult result = orchestrator.investigate(request.incidentId());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/{id}")
    public InvestigationResult get(@PathVariable String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Investigation not found: " + id));
    }

    @GetMapping
    public List<InvestigationSummary> list(
            @RequestParam(required = false) String incidentId,
            @RequestParam(defaultValue = "20") int limit) {
        List<InvestigationResult> results = incidentId == null || incidentId.isBlank()
                ? repository.findRecent(limit)
                : repository.findByIncidentId(incidentId, limit);
        return results.stream().map(this::toSummary).toList();
    }

    @GetMapping("/{id}/report")
    public IncidentReport report(@PathVariable String id) {
        InvestigationResult result = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Investigation not found: " + id));
        if (result.report() == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Investigation " + id + " did not produce a report");
        }
        return result.report();
    }

    private InvestigationSummary toSummary(InvestigationResult result) {
        HypothesisAssessment top = result.hypotheses().isEmpty() ? null : result.hypotheses().getFirst();
        return new InvestigationSummary(
                result.investigation().id(),
                result.investigation().incidentId(),
                result.investigation().status().name(),
                top == null ? null : top.statement(),
                top == null ? null : top.confidence(),
                result.investigation().createdAt(),
                result.investigation().completedAt());
    }
}
