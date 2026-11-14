package com.soumikseth.incidentinvestigator.engine.investigation;

public class IncidentNotFoundException extends RuntimeException {

    private final String incidentId;

    public IncidentNotFoundException(String incidentId) {
        super("Incident not found: " + incidentId);
        this.incidentId = incidentId;
    }

    public String incidentId() {
        return incidentId;
    }
}
