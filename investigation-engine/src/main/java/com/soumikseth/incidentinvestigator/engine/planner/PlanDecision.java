package com.soumikseth.incidentinvestigator.engine.planner;

import com.soumikseth.incidentinvestigator.engine.domain.HypothesisProposal;

import java.util.List;
import java.util.Map;

/**
 * The only two things a planner may do: request one allowlisted tool call, or
 * stop and propose hypotheses. This keeps tool execution under orchestrator
 * control rather than letting a model run arbitrary code.
 */
public sealed interface PlanDecision {

    record CallTool(String toolName, Map<String, Object> arguments) implements PlanDecision {

        public CallTool {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    record Conclude(List<HypothesisProposal> hypotheses) implements PlanDecision {

        public Conclude {
            hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
        }
    }
}
