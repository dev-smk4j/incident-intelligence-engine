package com.soumikseth.incidentinvestigator.simulator.api;

import com.soumikseth.incidentinvestigator.contracts.IncidentView;
import com.soumikseth.incidentinvestigator.simulator.detection.AnomalyDetector;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/detection")
public class DetectionController {

    private final AnomalyDetector detector;

    public DetectionController(AnomalyDetector detector) {
        this.detector = detector;
    }

    /**
     * Runs one detection pass immediately. Useful for demos and tests where
     * waiting for the scheduler would make the scenario slow and less explicit.
     */
    @PostMapping("/run")
    public ResponseEntity<IncidentView> run() {
        return detector.detectAndCreateIncident()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
