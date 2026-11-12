package com.soumikseth.incidentinvestigator.simulator.incident;

import com.soumikseth.incidentinvestigator.contracts.IncidentView;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class IncidentService {

    // Avoid opening a new incident every detection cycle while one is already being
    // investigated. Ten minutes is arbitrary but matches a typical on-call handoff
    // window for a demo-sized environment.
    private static final Duration DEDUPLICATION_WINDOW = Duration.ofMinutes(10);

    private final JdbcIncidentRepository repository;

    public IncidentService(JdbcIncidentRepository repository) {
        this.repository = repository;
    }

    public IncidentView createIfAbsent(
            String primaryService, String title, String description, String severity) {
        Instant now = Instant.now();
        Optional<IncidentRecord> existing = repository.findOpenByService(
                primaryService, now.minus(DEDUPLICATION_WINDOW));
        if (existing.isPresent()) {
            return existing.get().toView();
        }

        IncidentRecord incident = new IncidentRecord(
                UUID.randomUUID().toString(),
                title,
                description,
                severity,
                "OPEN",
                primaryService,
                now,
                now);
        repository.save(incident);
        return incident.toView();
    }

    public List<IncidentView> listRecent(int limit) {
        return repository.findRecent(Math.clamp(limit, 1, 100)).stream()
                .map(IncidentRecord::toView)
                .toList();
    }

    public Optional<IncidentView> find(String id) {
        return repository.findById(id).map(IncidentRecord::toView);
    }
}
