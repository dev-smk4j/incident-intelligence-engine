package com.soumikseth.incidentinvestigator.simulator.telemetry;

import com.soumikseth.incidentinvestigator.contracts.LogEntryView;
import com.soumikseth.incidentinvestigator.contracts.MetricSample;
import com.soumikseth.incidentinvestigator.contracts.MetricSeries;
import com.soumikseth.incidentinvestigator.contracts.TraceView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded in-memory telemetry store used by the simulator's query API.
 *
 * This exists so the investigation engine can query signals without also
 * needing Prometheus/Loki/Tempo running for the core flow and its tests. The same
 * signals are exported to Micrometer, so the Grafana stack remains the human-facing
 * observability surface, and a real query backend can later sit behind the engine's
 * telemetry ports without changing the tools.
 *
 * All operations are synchronized. The write rate in the simulator is a handful
 * of events per second, so a lock is cheaper and simpler than a concurrent data
 * structure with a complex eviction policy.
 */
@Component
public class TelemetryStore {

    private static final int MAX_SAMPLES_PER_SERIES = 2_000;
    private static final int MAX_LOGS = 5_000;
    private static final int MAX_TRACES = 2_000;

    private final Map<SeriesKey, Deque<MetricSample>> metricSeries = new HashMap<>();
    private final Deque<LogEntryView> logs = new ArrayDeque<>();
    private final Map<String, TraceView> traces = new LinkedHashMap<>();

    public synchronized void addMetric(String service, String metric, String unit, Instant timestamp, double value) {
        SeriesKey key = new SeriesKey(service, metric, unit);
        Deque<MetricSample> samples = metricSeries.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        samples.addLast(new MetricSample(timestamp, value));
        while (samples.size() > MAX_SAMPLES_PER_SERIES) {
            samples.removeFirst();
        }
    }

    public synchronized List<MetricSeries> queryMetrics(String service, String metric, Instant from, Instant to) {
        List<MetricSeries> result = new ArrayList<>();
        for (Map.Entry<SeriesKey, Deque<MetricSample>> entry : metricSeries.entrySet()) {
            SeriesKey key = entry.getKey();
            if (service != null && !service.isBlank() && !key.service().equals(service)) {
                continue;
            }
            if (metric != null && !metric.isBlank() && !key.metric().equals(metric)) {
                continue;
            }
            List<MetricSample> samples = entry.getValue().stream()
                    .filter(sample -> !sample.timestamp().isBefore(from) && !sample.timestamp().isAfter(to))
                    .toList();
            if (!samples.isEmpty()) {
                result.add(new MetricSeries(key.service(), key.metric(), key.unit(), samples));
            }
        }
        result.sort(Comparator.comparing(MetricSeries::service).thenComparing(MetricSeries::metric));
        return result;
    }

    public synchronized void addLog(LogEntryView logEntry) {
        logs.addLast(logEntry);
        while (logs.size() > MAX_LOGS) {
            logs.removeFirst();
        }
    }

    public synchronized List<LogEntryView> queryLogs(
            String service, Instant from, Instant to, String contains, int limit) {
        String needle = contains == null ? "" : contains.toLowerCase();
        List<LogEntryView> matches = logs.stream()
                .filter(entry -> service == null || service.isBlank() || entry.service().equals(service))
                .filter(entry -> !entry.timestamp().isBefore(from) && !entry.timestamp().isAfter(to))
                .filter(entry -> needle.isBlank() || entry.message().toLowerCase().contains(needle))
                .sorted(Comparator.comparing(LogEntryView::timestamp))
                .toList();

        int start = Math.max(0, matches.size() - limit);
        return matches.subList(start, matches.size());
    }

    public synchronized void addTrace(TraceView trace) {
        traces.put(trace.traceId(), trace);
        while (traces.size() > MAX_TRACES) {
            Iterator<String> oldest = traces.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    public synchronized Optional<TraceView> findTrace(String traceId) {
        return Optional.ofNullable(traces.get(traceId));
    }

    public synchronized List<TraceView> findTraces(
            String service, Instant from, Instant to, long minDurationMs, int limit) {
        return traces.values().stream()
                .filter(trace -> trace.spans().stream()
                        .anyMatch(span -> service == null || service.isBlank() || span.service().equals(service)))
                .filter(trace -> {
                    Instant startedAt = trace.spans().stream()
                            .map(span -> span.startTime())
                            .min(Comparator.naturalOrder())
                            .orElse(Instant.EPOCH);
                    return !startedAt.isBefore(from) && !startedAt.isAfter(to);
                })
                .filter(trace -> trace.totalDurationMs() >= minDurationMs)
                .sorted(Comparator.comparingLong(TraceView::totalDurationMs).reversed())
                .limit(limit)
                .toList();
    }

    public synchronized void clear() {
        metricSeries.clear();
        logs.clear();
        traces.clear();
    }

    private record SeriesKey(String service, String metric, String unit) {
    }
}
