package com.soumikseth.incidentinvestigator.simulator.api;

import com.soumikseth.incidentinvestigator.simulator.runtime.SimulatedEnvironment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/traffic")
public class TrafficController {

    private final SimulatedEnvironment environment;

    public TrafficController(SimulatedEnvironment environment) {
        this.environment = environment;
    }

    @PostMapping
    public Map<String, Object> generate(@RequestParam(defaultValue = "5") int count) {
        int boundedCount = Math.clamp(count, 1, 500);
        List<SimulatedEnvironment.OrderOutcome> outcomes = new ArrayList<>(boundedCount);
        for (int i = 0; i < boundedCount; i++) {
            outcomes.add(environment.generateOrder());
        }
        long failed = outcomes.stream().filter(outcome -> !outcome.success()).count();
        return Map.of(
                "generated", boundedCount,
                "failed", failed,
                "sampleTraceId", outcomes.getFirst().traceId());
    }
}
