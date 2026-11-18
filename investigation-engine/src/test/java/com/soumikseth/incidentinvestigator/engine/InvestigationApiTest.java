package com.soumikseth.incidentinvestigator.engine;

import com.jayway.jsonpath.JsonPath;
import com.soumikseth.incidentinvestigator.engine.persistence.InvestigationRepository;
import com.soumikseth.incidentinvestigator.engine.support.FakeGatewayConfiguration;
import com.soumikseth.incidentinvestigator.engine.support.FakeTelemetryGateway;
import com.soumikseth.incidentinvestigator.engine.support.ScenarioFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeGatewayConfiguration.class)
class InvestigationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeTelemetryGateway gateway;

    @Autowired
    private InvestigationRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clearStoredInvestigations() {
        gateway.reset();
        jdbc.sql("DELETE FROM investigation").update();
    }

    @Test
    void createsRetrievesAndReportsAnInvestigation() throws Exception {
        ScenarioFixtures.poolExhaustion(gateway);

        MvcResult created = mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"incidentId":"incident-pool"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.investigation.status").value("COMPLETED"))
                .andExpect(jsonPath("$.hypotheses[0].category").value("CONNECTION_POOL_EXHAUSTION"))
                .andExpect(jsonPath("$.report.rootCauses[0].rank").value(1))
                .andReturn();

        String investigationId = JsonPath.read(
                created.getResponse().getContentAsString(), "$.investigation.id");

        mockMvc.perform(get("/api/investigations/{id}", investigationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigation.id").value(investigationId));

        mockMvc.perform(get("/api/investigations/{id}/report", investigationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigationId").value(investigationId))
                .andExpect(jsonPath("$.recommendations").isNotEmpty());

        mockMvc.perform(get("/api/investigations")
                        .param("incidentId", "incident-pool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(investigationId))
                .andExpect(jsonPath("$[0].topConfidence").isNumber());

        assertStored(investigationId);
    }

    @Test
    void unknownIncidentReturnsNotFound() throws Exception {
        ScenarioFixtures.poolExhaustion(gateway);

        mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"incidentId":"incident-unknown"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Incident not found: incident-unknown"));
    }

    @Test
    void missingInvestigationReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/investigations/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Investigation not found: does-not-exist"));
    }

    @Test
    void malformedRequestReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed request body"));
    }

    private void assertStored(String investigationId) {
        assertThat(repository.findById(investigationId)).isPresent();
    }
}
