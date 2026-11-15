package com.soumikseth.incidentinvestigator.engine.investigation;

import com.soumikseth.incidentinvestigator.engine.domain.HypothesisAssessment;
import com.soumikseth.incidentinvestigator.engine.domain.Recommendation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the best-supported hypotheses into advisory next steps.
 *
 * Recommendations are text only. Nothing in this project can execute a
 * remediation, which is a deliberate safety property: an investigation system
 * driven by untrusted telemetry must never be able to change production state.
 *
 * A hypothesis needs an evidence score of at least {@value #MINIMUM_SCORE} to
 * produce a recommendation. Otherwise the report would suggest acting on
 * speculation, which is worse than saying "collect more data".
 */
@Component
public class RecommendationGenerator {

    private static final double MINIMUM_SCORE = 0.2;

    public List<Recommendation> generate(List<HypothesisAssessment> rankedHypotheses) {
        List<Recommendation> recommendations = new ArrayList<>();
        for (HypothesisAssessment hypothesis : rankedHypotheses) {
            if (hypothesis.confidence() < MINIMUM_SCORE) {
                continue;
            }
            recommendations.add(forHypothesis(hypothesis));
        }
        return recommendations;
    }

    private Recommendation forHypothesis(HypothesisAssessment hypothesis) {
        String service = hypothesis.impactedServices().isEmpty()
                ? "the affected service"
                : hypothesis.impactedServices().getFirst();
        List<String> evidenceIds = hypothesis.supportingEvidenceIds().stream().limit(4).toList();

        return switch (hypothesis.category()) {
            case CONNECTION_POOL_EXHAUSTION -> new Recommendation(
                    "Inspect the database connection pool on " + service
                            + ": verify pool size, acquisition timeout, and whether connections are returned on every path.",
                    "Pool utilisation is at saturation while requests fail during connection acquisition, "
                            + "so capacity or a leak in the request path is the most direct explanation.",
                    "Increasing pool size raises load on the database; confirm the database connection limit first.",
                    "Requests should stop timing out during connection acquisition and error rate should fall.",
                    evidenceIds);

            case MEMORY_PRESSURE -> new Recommendation(
                    "Capture a heap dump from " + service
                            + " and compare retained-set growth across the incident window before restarting.",
                    "Heap utilisation is at saturation and latency rises with GC pauses, which points to retained "
                            + "objects rather than a transient traffic spike.",
                    "A restart temporarily clears the symptom and destroys the evidence needed to find the leak.",
                    "Identifying the retained object graph should allow a targeted fix instead of recurring restarts.",
                    evidenceIds);

            case DOWNSTREAM_DEPENDENCY_LATENCY -> new Recommendation(
                    "Check " + String.join(" and ", hypothesis.impactedServices())
                            + " end to end: downstream latency, its resource health, and the caller's timeout, "
                            + "retry, and circuit-breaker settings.",
                    "Slow spans are concentrated in the downstream call while the caller's own resources remain "
                            + "normal, so the caller is amplifying a dependency problem rather than causing it.",
                    "Tightening timeouts without fixing the dependency can convert latency into errors.",
                    "Isolating the slow dependency should restore the caller's latency without scaling the caller.",
                    evidenceIds);

            case DEPLOYMENT_REGRESSION -> new Recommendation(
                    "Compare the recent deployment on " + service
                            + " with its baseline and canary cohort before considering a rollback.",
                    "A deployment occurred inside the incident window, but timing alone does not prove causation.",
                    "Rolling back an unrelated deployment adds risk and may remove a needed fix.",
                    "A canary comparison distinguishes a release regression from a runtime or dependency cause.",
                    evidenceIds);

            case UNKNOWN -> new Recommendation(
                    "Collect additional telemetry for " + service
                            + " (service-specific metrics, dependency health, and structured error logs) before acting.",
                    "The collected evidence does not distinguish between candidate causes.",
                    "Acting without a supported hypothesis risks changing unrelated production behaviour.",
                    "A narrower evidence set should allow the competing hypotheses to be separated.",
                    evidenceIds);
        };
    }
}
