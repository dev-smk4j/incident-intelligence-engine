package com.soumikseth.incidentinvestigator.contracts;

import java.time.Instant;

public record DeploymentView(
        String id,
        String service,
        String version,
        Instant deployedAt,
        String status,
        String summary) {
}
