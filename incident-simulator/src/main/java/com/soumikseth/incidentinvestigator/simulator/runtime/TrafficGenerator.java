package com.soumikseth.incidentinvestigator.simulator.runtime;

import com.soumikseth.incidentinvestigator.simulator.config.TrafficProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Generates baseline order traffic so the environment continuously produces
 * telemetry. Without this, an injected failure would have no symptoms to observe.
 *
 * Tests disable the scheduler and call {@link SimulatedEnvironment#generateOrder()}
 * directly, which keeps telemetry assertions deterministic.
 */
@Component
public class TrafficGenerator {

    private static final Logger log = LoggerFactory.getLogger(TrafficGenerator.class);

    private final SimulatedEnvironment environment;
    private final TrafficProperties properties;

    public TrafficGenerator(SimulatedEnvironment environment, TrafficProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${simulator.traffic.interval-ms:2000}")
    public void generateTraffic() {
        if (!properties.enabled()) {
            return;
        }
        for (int i = 0; i < properties.requestsPerTick(); i++) {
            try {
                environment.generateOrder();
            } catch (RuntimeException ex) {
                // A single failed simulation must not stop the generator; otherwise
                // the demo would silently go quiet after one unexpected error.
                log.warn("Failed to generate simulated order", ex);
            }
        }
    }
}
