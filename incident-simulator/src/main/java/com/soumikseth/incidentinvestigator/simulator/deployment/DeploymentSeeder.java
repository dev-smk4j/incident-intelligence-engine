package com.soumikseth.incidentinvestigator.simulator.deployment;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Seeds deployment history once so the investigator has something to correlate
 * against. Deployment summaries are intentionally ordinary: a summary must not
 * hint at the injected scenario.
 */
@Component
public class DeploymentSeeder implements ApplicationRunner {

    private final JdbcDeploymentRepository repository;

    public DeploymentSeeder(JdbcDeploymentRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            return;
        }

        Instant now = Instant.now();
        // Every target service gets one recent deployment so that "deployment
        // regression" is always a genuine competing hypothesis. Timing correlation
        // alone must not outrank direct resource or trace evidence.
        save("payment-service", "v2.4.1", now.minus(Duration.ofMinutes(47)),
                "Routine dependency upgrade");
        save("order-service", "v1.8.4", now.minus(Duration.ofMinutes(38)),
                "Refactored order validation");
        save("inventory-service", "v1.2.1", now.minus(Duration.ofMinutes(26)),
                "Adjusted reservation cache TTL");
        save("shipping-service", "v3.0.2", now.minus(Duration.ofDays(1)),
                "Carrier client retry tuning");
    }

    private void save(String service, String version, Instant deployedAt, String summary) {
        repository.save(new DeploymentRecord(
                UUID.randomUUID().toString(), service, version, deployedAt, "SUCCESS", summary));
    }
}
