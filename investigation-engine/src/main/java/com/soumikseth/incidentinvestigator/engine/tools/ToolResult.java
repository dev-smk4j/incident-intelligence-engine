package com.soumikseth.incidentinvestigator.engine.tools;

import java.util.List;

/**
 * Result of one tool call. {@code data} is the structured value the planner can
 * inspect; {@code evidence} is what gets persisted and cited by hypotheses.
 */
public record ToolResult(String summary, Object data, List<EvidenceDraft> evidence) {

    public ToolResult {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
