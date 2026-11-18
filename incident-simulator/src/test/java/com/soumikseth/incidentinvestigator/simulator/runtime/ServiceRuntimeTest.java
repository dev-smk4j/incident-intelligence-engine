package com.soumikseth.incidentinvestigator.simulator.runtime;

import com.soumikseth.incidentinvestigator.simulator.domain.FailureMode;
import com.soumikseth.incidentinvestigator.simulator.domain.ServiceName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceRuntimeTest {

    @Test
    void computesRollingErrorRateAndP99() {
        ServiceRuntime runtime = new ServiceRuntime(ServiceName.PAYMENT);
        for (int i = 0; i < 98; i++) {
            runtime.recordRequest(100, false);
        }
        runtime.recordRequest(2_000, true);
        runtime.recordRequest(2_100, true);

        assertThat(runtime.errorRate()).isEqualTo(0.02);
        assertThat(runtime.p99LatencyMs()).isEqualTo(2_000);
    }

    @Test
    void memoryLeakIncreasesHeapAndMarksServiceDegraded() {
        ServiceRuntime runtime = new ServiceRuntime(ServiceName.ORDER);
        runtime.beginFailure(FailureMode.MEMORY_LEAK);
        for (int i = 0; i < 40; i++) {
            runtime.advanceLeak();
        }

        assertThat(runtime.heapUtilization()).isGreaterThan(0.85);
        assertThat(runtime.status()).isEqualTo("DEGRADED");
    }

    @Test
    void clearingFailureRestoresHealthyResourceSignals() {
        ServiceRuntime runtime = new ServiceRuntime(ServiceName.PAYMENT);
        runtime.beginFailure(FailureMode.CONNECTION_POOL_EXHAUSTION);
        assertThat(runtime.connectionPoolUtilization()).isGreaterThan(0.9);

        runtime.clearFailure();

        assertThat(runtime.failureMode()).isEqualTo(FailureMode.NONE);
        assertThat(runtime.connectionPoolUtilization()).isLessThan(0.5);
    }
}
