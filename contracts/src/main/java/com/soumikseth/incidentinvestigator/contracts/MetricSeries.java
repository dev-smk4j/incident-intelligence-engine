package com.soumikseth.incidentinvestigator.contracts;

import java.util.List;

public record MetricSeries(String service, String metric, String unit, List<MetricSample> samples) {
}
