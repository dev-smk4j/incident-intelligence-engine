package com.soumikseth.incidentinvestigator.engine.investigation;

import com.soumikseth.incidentinvestigator.engine.domain.Evidence;
import com.soumikseth.incidentinvestigator.engine.tools.ToolResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ToolExecution(
        String toolName,
        Map<String, Object> arguments,
        String status,
        String summary,
        Object data,
        List<Evidence> evidence,
        Instant startedAt,
        Instant completedAt) {

    public ToolExecution {
        arguments = Map.copyOf(arguments);
        evidence = List.copyOf(evidence);
    }

    public boolean successful() {
        return "SUCCESS".equals(status);
    }

    public ToolExecution withEvidence(List<Evidence> newEvidence) {
        return new ToolExecution(
                toolName, arguments, status, summary, data, newEvidence, startedAt, completedAt);
    }

    public static ToolExecution failed(
            String toolName, Map<String, Object> arguments, String summary, Instant startedAt, Instant completedAt) {
        return new ToolExecution(toolName, arguments, "FAILED", summary, null, List.of(), startedAt, completedAt);
    }

    public static ToolExecution succeeded(
            String toolName, Map<String, Object> arguments, ToolResult result, Instant startedAt, Instant completedAt) {
        return new ToolExecution(
                toolName, arguments, "SUCCESS", result.summary(), result.data(), List.of(), startedAt, completedAt);
    }
}
