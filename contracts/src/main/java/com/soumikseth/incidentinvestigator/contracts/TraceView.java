package com.soumikseth.incidentinvestigator.contracts;

import java.util.List;

public record TraceView(String traceId, List<SpanView> spans) {

    public long totalDurationMs() {
        return spans.stream()
                .mapToLong(SpanView::durationMs)
                .max()
                .orElse(0L);
    }
}
