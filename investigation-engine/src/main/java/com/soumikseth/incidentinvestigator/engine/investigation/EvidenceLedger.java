package com.soumikseth.incidentinvestigator.engine.investigation;

import com.soumikseth.incidentinvestigator.engine.domain.Evidence;
import com.soumikseth.incidentinvestigator.engine.tools.EvidenceDraft;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects evidence for one investigation run and assigns stable, human-readable
 * identifiers so that hypotheses and reports can cite specific observations.
 *
 * Ids are scoped to a single investigation and monotonically increasing. That
 * makes a persisted report readable ("ev-004 shows pool utilisation 0.98") without
 * requiring a join back to an evidence table.
 */
public final class EvidenceLedger {

    private final String investigationId;
    private final List<Evidence> entries = new ArrayList<>();
    private int sequence;

    public EvidenceLedger(String investigationId) {
        this.investigationId = investigationId;
    }

    public List<Evidence> record(List<EvidenceDraft> drafts) {
        List<Evidence> recorded = new ArrayList<>(drafts.size());
        for (EvidenceDraft draft : drafts) {
            Evidence evidence = new Evidence(
                    nextId(),
                    investigationId,
                    draft.type(),
                    TelemetrySanitizer.source(draft.source()),
                    TelemetrySanitizer.content(draft.content()),
                    Instant.now());
            entries.add(evidence);
            recorded.add(evidence);
        }
        return recorded;
    }

    public List<Evidence> all() {
        return List.copyOf(entries);
    }

    private String nextId() {
        sequence++;
        return String.format("ev-%03d", sequence);
    }
}
