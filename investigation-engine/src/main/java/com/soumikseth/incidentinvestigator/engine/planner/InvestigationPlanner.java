package com.soumikseth.incidentinvestigator.engine.planner;

/**
 * Planner port. The deterministic scripted implementation drives tests and the
 * baseline demo; an LLM-backed implementation can be added behind the same port.
 */
public interface InvestigationPlanner {

    PlanDecision next(InvestigationContext context);
}
