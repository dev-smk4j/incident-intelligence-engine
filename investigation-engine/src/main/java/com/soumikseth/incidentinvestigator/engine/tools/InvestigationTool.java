package com.soumikseth.incidentinvestigator.engine.tools;

import java.util.Map;

public interface InvestigationTool {

    String name();

    String description();

    ToolResult execute(Map<String, Object> arguments);
}
