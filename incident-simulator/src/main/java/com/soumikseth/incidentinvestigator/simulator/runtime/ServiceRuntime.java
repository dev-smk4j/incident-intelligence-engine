package com.soumikseth.incidentinvestigator.simulator.runtime;

import com.soumikseth.incidentinvestigator.simulator.domain.FailureMode;
import com.soumikseth.incidentinvestigator.simulator.domain.ServiceName;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable runtime state for one simulated service.
 *
 * Latency and error statistics are calculated over a rolling window of recent
 * requests. That approximates what a real metrics backend would report over a
 * short query range and keeps the detector from reacting to a single slow call.
 */
public final class ServiceRuntime {

    private static final int WINDOW_SIZE = 200;

    private final ServiceName service;
    private final Deque<Long> recentLatenciesMs = new ArrayDeque<>();
    private final Deque<Boolean> recentFailures = new ArrayDeque<>();
    private final AtomicLong leakTicks = new AtomicLong();

    private volatile FailureMode failureMode = FailureMode.NONE;

    public ServiceRuntime(ServiceName service) {
        this.service = service;
    }

    public synchronized void recordRequest(long latencyMs, boolean failed) {
        recentLatenciesMs.addLast(Math.max(0L, latencyMs));
        recentFailures.addLast(failed);
        while (recentLatenciesMs.size() > WINDOW_SIZE) {
            recentLatenciesMs.removeFirst();
        }
        while (recentFailures.size() > WINDOW_SIZE) {
            recentFailures.removeFirst();
        }
    }

    public synchronized double errorRate() {
        if (recentFailures.isEmpty()) {
            return 0.0;
        }
        long failures = recentFailures.stream().filter(Boolean::booleanValue).count();
        return (double) failures / recentFailures.size();
    }

    public synchronized double p99LatencyMs() {
        if (recentLatenciesMs.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = new ArrayList<>(recentLatenciesMs);
        Collections.sort(sorted);
        int index = (int) Math.ceil(sorted.size() * 0.99) - 1;
        return sorted.get(Math.max(0, index));
    }

    public void beginFailure(FailureMode mode) {
        leakTicks.set(0);
        failureMode = mode;
    }

    public void clearFailure() {
        leakTicks.set(0);
        failureMode = FailureMode.NONE;
    }

    /**
     * Clears failure state and rolling request statistics. Used by tests and by
     * the operator reset endpoint; it is not called during normal operation.
     */
    public synchronized void reset() {
        clearFailure();
        recentLatenciesMs.clear();
        recentFailures.clear();
    }

    public void advanceLeak() {
        if (failureMode == FailureMode.MEMORY_LEAK) {
            leakTicks.incrementAndGet();
        }
    }

    public FailureMode failureMode() {
        return failureMode;
    }

    public ServiceName service() {
        return service;
    }

    public long leakTicks() {
        return leakTicks.get();
    }

    public double cpuUtilization() {
        return switch (failureMode) {
            case MEMORY_LEAK -> Math.min(0.94, 0.78 + leakTicks.get() * 0.002);
            case CONNECTION_POOL_EXHAUSTION -> 0.42;
            case SLOW_DEPENDENCY -> 0.24;
            case NONE -> 0.16;
        };
    }

    public double heapUtilization() {
        if (failureMode == FailureMode.MEMORY_LEAK) {
            return Math.min(0.96, 0.44 + leakTicks.get() * 0.012);
        }
        return 0.36;
    }

    public double connectionPoolUtilization() {
        return failureMode == FailureMode.CONNECTION_POOL_EXHAUSTION ? 0.98 : 0.31;
    }

    /**
     * Reserved for the Kafka consumer-lag scenario. Returning a stable zero now
     * keeps all service health responses the same shape.
     */
    public long consumerLag() {
        return 0L;
    }

    public String status() {
        if (errorRate() >= 0.5) {
            return "FAILED";
        }
        if (errorRate() >= 0.05
                || p99LatencyMs() >= 800
                || heapUtilization() >= 0.9
                || connectionPoolUtilization() >= 0.9) {
            return "DEGRADED";
        }
        return "HEALTHY";
    }
}
