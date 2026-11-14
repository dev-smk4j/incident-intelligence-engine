package com.soumikseth.incidentinvestigator.engine.tools;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The only tools the orchestrator will execute. Unknown names are rejected
 * before any code runs, which is the main guard against a model inventing a
 * privileged operation.
 */
@Component
public class ToolRegistry {

    private final Map<String, InvestigationTool> tools;

    public ToolRegistry(TelemetryToolCatalog catalog) {
        Map<String, InvestigationTool> registered = new LinkedHashMap<>();
        for (InvestigationTool tool : catalog.tools()) {
            InvestigationTool previous = registered.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("Duplicate investigation tool: " + tool.name());
            }
        }
        this.tools = Map.copyOf(registered);
    }

    public Optional<InvestigationTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Set<String> allowedNames() {
        return tools.keySet();
    }
}
