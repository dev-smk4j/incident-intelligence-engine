CREATE TABLE incident (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    severity VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    primary_service VARCHAR(100) NOT NULL,
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_incident_service_detected
    ON incident (primary_service, detected_at);

CREATE TABLE deployment (
    id VARCHAR(36) PRIMARY KEY,
    service VARCHAR(100) NOT NULL,
    version VARCHAR(50) NOT NULL,
    deployed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(20) NOT NULL,
    summary TEXT NOT NULL
);

CREATE INDEX idx_deployment_service_detected
    ON deployment (service, deployed_at);
