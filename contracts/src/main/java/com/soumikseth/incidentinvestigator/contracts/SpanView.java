package com.soumikseth.incidentinvestigator.contracts;

import java.time.Instant;
import java.util.Map;

public record SpanView(
        String traceId,
        String spanId,
        String parentSpanId,
        String service,
        String operation,
        Instant startTime,
        long durationMs,
        String status,
        Map<String, String> attributes) {
}
