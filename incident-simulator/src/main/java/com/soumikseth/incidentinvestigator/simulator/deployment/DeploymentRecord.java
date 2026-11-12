package com.soumikseth.incidentinvestigator.simulator.deployment;

import com.soumikseth.incidentinvestigator.contracts.DeploymentView;

import java.time.Instant;

public record DeploymentRecord(
        String id,
        String service,
        String version,
        Instant deployedAt,
        String status,
        String summary) {

    public DeploymentView toView() {
        return new DeploymentView(id, service, version, deployedAt, status, summary);
    }
}
