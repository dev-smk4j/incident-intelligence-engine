package com.soumikseth.incidentinvestigator.contracts;

import java.time.Instant;

public record MetricSample(Instant timestamp, double value) {
}
