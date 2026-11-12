package com.soumikseth.incidentinvestigator.simulator.api;

import com.soumikseth.incidentinvestigator.contracts.ServiceHealth;
import com.soumikseth.incidentinvestigator.contracts.ServiceTopology;
import com.soumikseth.incidentinvestigator.simulator.domain.ServiceName;
import com.soumikseth.incidentinvestigator.simulator.runtime.SimulatedEnvironment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/services")
public class TopologyController {

    private final SimulatedEnvironment environment;

    public TopologyController(SimulatedEnvironment environment) {
        this.environment = environment;
    }

    @GetMapping
    public ServiceTopology topology() {
        return environment.topology();
    }

    @GetMapping("/{service}/health")
    public ServiceHealth health(@PathVariable String service) {
        return environment.health(ServiceName.fromSlug(service));
    }
}
