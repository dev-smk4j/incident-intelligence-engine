package com.soumikseth.incidentinvestigator.simulator.telemetry;

import com.soumikseth.incidentinvestigator.contracts.LogEntryView;
import com.soumikseth.incidentinvestigator.contracts.MetricSeries;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryStoreTest {

    private final TelemetryStore store = new TelemetryStore();

    @Test
    void filtersMetricSeriesByServiceAndTimeRange() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        store.addMetric("payment-service", "m", "ratio", now.minusSeconds(10), 1.0);
        store.addMetric("payment-service", "m", "ratio", now, 2.0);
        store.addMetric("order-service", "m", "ratio", now, 3.0);

        List<MetricSeries> series = store.queryMetrics(
                "payment-service", "m", now.minusSeconds(5), now);

        assertThat(series).hasSize(1);
        assertThat(series.getFirst().samples()).hasSize(1);
        assertThat(series.getFirst().samples().getFirst().value()).isEqualTo(2.0);
    }

    @Test
    void returnsTheMostRecentLogsInChronologicalOrder() {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            store.addLog(new LogEntryView(
                    now.plusSeconds(i), "svc", "INFO", "msg " + i, "trace", "span", "corr"));
        }

        List<LogEntryView> logs = store.queryLogs(
                "svc", now.minusSeconds(1), now.plusSeconds(10), null, 2);

        assertThat(logs).extracting(LogEntryView::message).containsExactly("msg 3", "msg 4");
    }

    @Test
    void findTracesReturnsSlowestFirst() {
        Instant now = Instant.now();
        store.addTrace(trace("slow", "payment-service", now, 2_000));
        store.addTrace(trace("fast", "payment-service", now, 100));

        List<String> ids = store.findTraces("payment-service", now.minusSeconds(1), now.plusSeconds(1), 0, 10)
                .stream()
                .map(trace -> trace.traceId())
                .toList();

        assertThat(ids).containsExactly("slow", "fast");
    }

    private com.soumikseth.incidentinvestigator.contracts.TraceView trace(
            String id, String service, Instant start, long durationMs) {
        return new com.soumikseth.incidentinvestigator.contracts.TraceView(
                id,
                List.of(new com.soumikseth.incidentinvestigator.contracts.SpanView(
                        id, "span-" + id, null, service, "op", start, durationMs, "OK", java.util.Map.of())));
    }
}
