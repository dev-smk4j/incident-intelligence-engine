package com.soumikseth.incidentinvestigator.simulator.api;

import com.soumikseth.incidentinvestigator.simulator.runtime.FailureInjector;
import com.soumikseth.incidentinvestigator.simulator.runtime.SimulatedEnvironment;
import com.soumikseth.incidentinvestigator.simulator.telemetry.TelemetrySampler;
import com.soumikseth.incidentinvestigator.simulator.telemetry.TelemetryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SimulatorApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FailureInjector injector;

    @Autowired
    private TelemetrySampler sampler;

    @Autowired
    private TelemetryStore store;

    @Autowired
    private SimulatedEnvironment environment;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        injector.clear();
        environment.reset();
        store.clear();
        jdbc.sql("DELETE FROM incident").update();
    }

    @AfterEach
    void clearFailures() {
        injector.clear();
    }

    @Test
    void scenarioTrafficDetectionAndTelemetryQueryWorkEndToEnd() throws Exception {
        mockMvc.perform(post("/api/scenarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenario":"connection-pool-exhaustion","durationSeconds":120}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.targetService").value("payment-service"));

        mockMvc.perform(post("/api/traffic").param("count", "60"))
                .andExpect(status().isOk());

        sampler.sample();

        MvcResult detection = mockMvc.perform(post("/api/detection/run"))
                .andExpect(status().isOk())
                .andReturn();

        String incidentJson = detection.getResponse().getContentAsString();
        assertThat(incidentJson).doesNotContain("scenario");
        assertThat(incidentJson).contains("payment-service");

        mockMvc.perform(get("/api/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].primaryService").value("payment-service"));

        mockMvc.perform(get("/api/telemetry/metrics")
                        .param("service", "payment-service")
                        .param("metric", "db.connection.pool.utilization"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].service").value("payment-service"));

        mockMvc.perform(get("/api/telemetry/logs")
                        .param("service", "payment-service")
                        .param("contains", "Connection acquisition"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isNotEmpty());

        mockMvc.perform(get("/api/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services.length()").value(4));
    }

    @Test
    void unknownScenarioIsRejectedWithoutLeakingInternals() throws Exception {
        mockMvc.perform(post("/api/scenarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenario":"not-a-real-scenario"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown scenario: not-a-real-scenario"));
    }

    @Test
    void clearingScenariosRestoresBaselineTelemetry() throws Exception {
        mockMvc.perform(post("/api/scenarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenario":"memory-leak","durationSeconds":120}
                                """))
                .andExpect(status().isAccepted());

        mockMvc.perform(delete("/api/scenarios"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeScenarios").isEmpty());
    }

    @Test
    void actuatorHealthEndpointIsExposed() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
