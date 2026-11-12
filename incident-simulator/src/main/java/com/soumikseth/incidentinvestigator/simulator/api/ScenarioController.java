package com.soumikseth.incidentinvestigator.simulator.api;

import com.soumikseth.incidentinvestigator.simulator.domain.Scenario;
import com.soumikseth.incidentinvestigator.simulator.runtime.FailureInjector;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Operator control plane. The investigation engine never calls these endpoints;
 * they exist to drive demos and automated end-to-end tests.
 */
@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final FailureInjector injector;

    public ScenarioController(FailureInjector injector) {
        this.injector = injector;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> activate(@RequestBody ScenarioRequest request) {
        if (request.scenario() == null || request.scenario().isBlank()) {
            throw new IllegalArgumentException("scenario is required");
        }
        Scenario scenario = Scenario.fromSlug(request.scenario());
        int durationSeconds = request.durationSeconds() == null ? 120 : request.durationSeconds();
        injector.activate(scenario, durationSeconds);
        return ResponseEntity.accepted().body(Map.of(
                "scenario", scenario.slug(),
                "targetService", scenario.targetService().slug(),
                "activeForSeconds", durationSeconds));
    }

    @DeleteMapping
    public ResponseEntity<Void> clear() {
        injector.clear();
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public Map<String, Object> active() {
        return Map.of("activeScenarios", injector.activeScenarioSlugs());
    }
}
