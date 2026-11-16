package com.soumikseth.incidentinvestigator.engine.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soumikseth.incidentinvestigator.engine.domain.InvestigationResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Stores each investigation as one immutable JSON document plus a small set of
 * indexed columns.
 *
 * An investigation is an append-only evidence record: reports are generated
 * once and read back for display or audit. Modelling each evidence item as its own
 * table would add joins and migrations without enabling any query the product
 * needs, so the document and its indexed metadata live side by side in one table.
 * The document column is TEXT rather than JSONB so the same migration runs on H2
 * for local development and tests and on PostgreSQL for the containerised stack.
 */
@Repository
public class InvestigationRepository {

    private static final int MAX_LIST_LIMIT = 100;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public InvestigationRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void save(InvestigationResult result) {
        jdbc.sql("""
                        INSERT INTO investigation
                            (id, incident_id, status, error_message, document, created_at, completed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        result.investigation().id(),
                        result.investigation().incidentId(),
                        result.investigation().status().name(),
                        result.investigation().errorMessage(),
                        serialize(result),
                        Timestamp.from(result.investigation().createdAt()),
                        result.investigation().completedAt() == null
                                ? null
                                : Timestamp.from(result.investigation().completedAt()))
                .update();
    }

    public Optional<InvestigationResult> findById(String id) {
        return jdbc.sql("SELECT document FROM investigation WHERE id = ?")
                .param(id)
                .query((rs, rowNum) -> deserialize(rs))
                .optional();
    }

    public List<InvestigationResult> findByIncidentId(String incidentId, int limit) {
        return jdbc.sql("""
                        SELECT document
                        FROM investigation
                        WHERE incident_id = ?
                        ORDER BY created_at DESC
                        LIMIT ?
                        """)
                .params(incidentId, bounded(limit))
                .query((rs, rowNum) -> deserialize(rs))
                .list();
    }

    public List<InvestigationResult> findRecent(int limit) {
        return jdbc.sql("""
                        SELECT document
                        FROM investigation
                        ORDER BY created_at DESC
                        LIMIT ?
                        """)
                .param(bounded(limit))
                .query((rs, rowNum) -> deserialize(rs))
                .list();
    }

    private String serialize(InvestigationResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException(
                    "Could not serialize investigation " + result.investigation().id(), serializationFailure);
        }
    }

    private InvestigationResult deserialize(ResultSet rs) throws SQLException {
        String document = rs.getString("document");
        try {
            return objectMapper.readValue(document, InvestigationResult.class);
        } catch (JsonProcessingException parseFailure) {
            throw new IllegalStateException("Stored investigation document is not readable", parseFailure);
        }
    }

    private int bounded(int limit) {
        return Math.clamp(limit, 1, MAX_LIST_LIMIT);
    }
}
