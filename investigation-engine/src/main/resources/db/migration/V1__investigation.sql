CREATE TABLE investigation (
    id VARCHAR(36) PRIMARY KEY,
    incident_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    document TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_investigation_incident
    ON investigation (incident_id, created_at);
