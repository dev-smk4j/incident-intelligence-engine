package com.soumikseth.incidentinvestigator.simulator.deployment;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class JdbcDeploymentRepository {

    private final JdbcClient jdbc;

    public JdbcDeploymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(DeploymentRecord deployment) {
        jdbc.sql("""
                        INSERT INTO deployment (id, service, version, deployed_at, status, summary)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        deployment.id(),
                        deployment.service(),
                        deployment.version(),
                        Timestamp.from(deployment.deployedAt()),
                        deployment.status(),
                        deployment.summary())
                .update();
    }

    public List<DeploymentRecord> findRecent(Instant from, int limit) {
        return jdbc.sql("""
                        SELECT id, service, version, deployed_at, status, summary
                        FROM deployment
                        WHERE deployed_at >= ?
                        ORDER BY deployed_at DESC
                        LIMIT ?
                        """)
                .params(Timestamp.from(from), limit)
                .query(this::mapDeployment)
                .list();
    }

    public List<DeploymentRecord> findByService(String service, Instant from) {
        return jdbc.sql("""
                        SELECT id, service, version, deployed_at, status, summary
                        FROM deployment
                        WHERE service = ?
                          AND deployed_at >= ?
                        ORDER BY deployed_at DESC
                        """)
                .params(service, Timestamp.from(from))
                .query(this::mapDeployment)
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM deployment")
                .query(Long.class)
                .single();
    }

    private DeploymentRecord mapDeployment(ResultSet rs, int rowNum) throws SQLException {
        return new DeploymentRecord(
                rs.getString("id"),
                rs.getString("service"),
                rs.getString("version"),
                rs.getTimestamp("deployed_at").toInstant(),
                rs.getString("status"),
                rs.getString("summary"));
    }
}
