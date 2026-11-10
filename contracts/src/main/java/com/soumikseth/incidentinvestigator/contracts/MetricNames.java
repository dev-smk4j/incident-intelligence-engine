package com.soumikseth.incidentinvestigator.contracts;

/**
 * Stable metric names shared by the simulator and the investigation engine.
 */
public final class MetricNames {

    public static final String HTTP_ERROR_RATE = "http.server.error.rate";
    public static final String HTTP_P99_LATENCY_MS = "http.server.latency.p99";
    public static final String JVM_CPU_UTILIZATION = "jvm.cpu.utilization";
    public static final String JVM_HEAP_UTILIZATION = "jvm.heap.utilization";
    public static final String DB_POOL_UTILIZATION = "db.connection.pool.utilization";
    public static final String KAFKA_CONSUMER_LAG = "kafka.consumer.lag";

    private MetricNames() {
    }
}
