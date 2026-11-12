package com.soumikseth.incidentinvestigator.simulator.incident;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcIncidentRepository {

    private final JdbcClient jdbc;

    public JdbcIncidentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(IncidentRecord incident) {
        jdbc.sql("""
                        INSERT INTO incident
                            (id, title, description, severity, status, primary_service, detected_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        incident.id(),
                        incident.title(),
                        incident.description(),
                        incident.severity(),
                        incident.status(),
                        incident.primaryService(),
                        Timestamp.from(incident.detectedAt()),
                        Timestamp.from(incident.createdAt()))
                .update();
    }

    public Optional<IncidentRecord> findById(String id) {
        return jdbc.sql("""
                        SELECT id, title, description, severity, status, primary_service, detected_at, created_at
                        FROM incident
                        WHERE id = ?
                        """)
                .param(id)
                .query(this::mapIncident)
                .optional();
    }

    public List<IncidentRecord> findRecent(int limit) {
        return jdbc.sql("""
                        SELECT id, title, description, severity, status, primary_service, detected_at, created_at
                        FROM incident
                        ORDER BY detected_at DESC
                        LIMIT ?
                        """)
                .param(limit)
                .query(this::mapIncident)
                .list();
    }

    public Optional<IncidentRecord> findOpenByService(String service, Instant since) {
        return jdbc.sql("""
                        SELECT id, title, description, severity, status, primary_service, detected_at, created_at
                        FROM incident
                        WHERE primary_service = ?
                          AND status = 'OPEN'
                          AND detected_at >= ?
                        ORDER BY detected_at DESC
                        LIMIT 1
                        """)
                .params(service, Timestamp.from(since))
                .query(this::mapIncident)
                .optional();
    }

    private IncidentRecord mapIncident(ResultSet rs, int rowNum) throws SQLException {
        return new IncidentRecord(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("severity"),
                rs.getString("status"),
                rs.getString("primary_service"),
                rs.getTimestamp("detected_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
