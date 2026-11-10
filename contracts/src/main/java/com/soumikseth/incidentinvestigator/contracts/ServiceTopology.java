package com.soumikseth.incidentinvestigator.contracts;

import java.time.Instant;
import java.util.List;

public record ServiceTopology(List<ServiceNode> services, Instant observedAt) {
}
