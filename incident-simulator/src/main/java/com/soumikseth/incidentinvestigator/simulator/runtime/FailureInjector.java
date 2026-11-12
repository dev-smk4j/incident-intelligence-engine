package com.soumikseth.incidentinvestigator.simulator.runtime;

import com.soumikseth.incidentinvestigator.simulator.domain.Scenario;
import com.soumikseth.incidentinvestigator.simulator.domain.ServiceName;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Activates and expires simulated failures.
 *
 * Only one scenario may be active at a time. Overlapping failures would make
 * the demo ambiguous and would make it impossible to say whether the investigator
 * correctly separated causes from cascading symptoms.
 */
@Component
public class FailureInjector {

    private final SimulatedEnvironment environment;
    private final Map<ServiceName, ActiveFailure> activeFailures = new ConcurrentHashMap<>();

    public FailureInjector(SimulatedEnvironment environment) {
        this.environment = environment;
    }

    public void activate(Scenario scenario, int durationSeconds) {
        int boundedDuration = Math.clamp(durationSeconds, 10, 3_600);
        clear();

        ServiceName target = scenario.targetService();
        environment.runtime(target).beginFailure(scenario.failureMode());
        activeFailures.put(target, new ActiveFailure(
                scenario.slug(), Instant.now().plusSeconds(boundedDuration)));
    }

    public void clear() {
        for (ServiceName service : activeFailures.keySet()) {
            environment.runtime(service).clearFailure();
        }
        activeFailures.clear();
    }

    @Scheduled(fixedDelay = 1_000)
    public void expireFinishedFailures() {
        Instant now = Instant.now();
        activeFailures.entrySet().removeIf(entry -> {
            if (now.isAfter(entry.getValue().expiresAt())) {
                environment.runtime(entry.getKey()).clearFailure();
                return true;
            }
            return false;
        });
    }

    public List<String> activeScenarioSlugs() {
        return activeFailures.values().stream()
                .map(ActiveFailure::scenarioSlug)
                .sorted()
                .toList();
    }

    private record ActiveFailure(String scenarioSlug, Instant expiresAt) {
    }
}
