package com.soumikseth.incidentinvestigator.engine.planner;

import com.soumikseth.incidentinvestigator.engine.domain.Evidence;
import com.soumikseth.incidentinvestigator.engine.investigation.ToolExecution;
import com.soumikseth.incidentinvestigator.contracts.IncidentView;

import java.util.List;

public record InvestigationContext(
        IncidentView incident,
        List<Evidence> evidence,
        List<ToolExecution> executions) {

    public InvestigationContext {
        evidence = List.copyOf(evidence);
        executions = List.copyOf(executions);
    }
}
