package com.soumikseth.incidentinvestigator.engine.tools;

import com.soumikseth.incidentinvestigator.engine.domain.EvidenceType;

/**
 * Evidence produced by a tool, before an id is assigned. Kept separate from
 * {@link ToolResult} because the ledger records drafts independently of the
 * structured data returned to the planner.
 */
public record EvidenceDraft(EvidenceType type, String source, String content) {
}
