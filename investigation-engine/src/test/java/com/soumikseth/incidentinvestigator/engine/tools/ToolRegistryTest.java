package com.soumikseth.incidentinvestigator.engine.tools;

import com.soumikseth.incidentinvestigator.engine.support.FakeTelemetryGateway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    private final ToolRegistry registry =
            new ToolRegistry(new TelemetryToolCatalog(new FakeTelemetryGateway()));

    @Test
    void exposesExactlyTheReadOnlyQuerySurface() {
        assertThat(registry.allowedNames()).containsExactlyInAnyOrder(
                "get_service_topology",
                "get_service_health",
                "query_metrics",
                "search_logs",
                "find_slow_traces",
                "get_recent_deployments");
    }

    @Test
    void unknownToolsAreNotResolvable() {
        assertThat(registry.find("run_shell")).isEmpty();
        assertThat(registry.find("apply_remediation")).isEmpty();
        assertThat(registry.find("delete_pod")).isEmpty();
    }
}
