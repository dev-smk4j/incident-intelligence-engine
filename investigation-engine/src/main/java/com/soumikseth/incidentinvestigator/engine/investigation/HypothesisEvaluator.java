package com.soumikseth.incidentinvestigator.engine.investigation;

import com.soumikseth.incidentinvestigator.engine.domain.Evidence;
import com.soumikseth.incidentinvestigator.engine.domain.HypothesisAssessment;
import com.soumikseth.incidentinvestigator.engine.domain.HypothesisProposal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scores competing hypotheses from the evidence they cite.
 *
 * A planner proposes a hypothesis and links evidence to it; the score is computed
 * here from the number of distinct evidence categories that support it: metrics
 * 0.32, logs 0.24, traces 0.24, health 0.10, deployments 0.10, topology 0.05.
 *
 * One category is worth its weight once, no matter how many samples it contains.
 * Ten metric points are not ten independent confirmations. Contradicting
 * categories subtract 0.12 each (capped at 0.36), and each unanswered check
 * subtracts 0.04 (capped at 0.16). The result is clamped to [0, 1] and rounded to
 * three decimals.
 *
 * The score is a transparent evidence score, not a calibrated probability. The
 * report exposes the calculation in {@code confidenceBasis} so a reviewer can see
 * why one hypothesis outranked another.
 */
@Component
public class HypothesisEvaluator {

    private static final Map<String, Double> CATEGORY_WEIGHTS = Map.of(
            "metrics", 0.32,
            "logs", 0.24,
            "traces", 0.24,
            "health", 0.10,
            "deployments", 0.10,
            "topology", 0.05);

    private static final double CONTRADICTION_PENALTY = 0.12;
    private static final double MAX_CONTRADICTION_PENALTY = 0.36;
    private static final double MISSING_CHECK_PENALTY = 0.04;
    private static final double MAX_MISSING_CHECK_PENALTY = 0.16;

    public List<HypothesisAssessment> evaluate(
            String investigationId, List<HypothesisProposal> proposals, List<Evidence> evidence) {
        Map<String, Evidence> evidenceById = indexById(evidence);
        List<HypothesisAssessment> assessments = new ArrayList<>(proposals.size());

        for (int index = 0; index < proposals.size(); index++) {
            HypothesisProposal proposal = proposals.get(index);
            assessments.add(assess(investigationId, index + 1, proposal, evidenceById));
        }

        assessments.sort(Comparator
                .comparingDouble(HypothesisAssessment::confidence)
                .reversed()
                .thenComparing(HypothesisAssessment::statement));

        List<HypothesisAssessment> ranked = new ArrayList<>(assessments.size());
        for (int index = 0; index < assessments.size(); index++) {
            ranked.add(assessments.get(index).withRank(index + 1));
        }
        return ranked;
    }

    private HypothesisAssessment assess(
            String investigationId,
            int ordinal,
            HypothesisProposal proposal,
            Map<String, Evidence> evidenceById) {

        WeightedCategories supporting = categoriesOf(proposal.supportingEvidenceIds(), evidenceById);
        WeightedCategories contradicting = categoriesOf(proposal.contradictingEvidenceIds(), evidenceById);
        int unresolved = supporting.unresolved() + contradicting.unresolved();

        double support = Math.min(1.0, supporting.totalWeight());
        double contradictionPenalty =
                Math.min(MAX_CONTRADICTION_PENALTY, contradicting.categories().size() * CONTRADICTION_PENALTY);
        double missingPenalty =
                Math.min(MAX_MISSING_CHECK_PENALTY, proposal.missingEvidence().size() * MISSING_CHECK_PENALTY);
        double score = round(clamp(support - contradictionPenalty - missingPenalty));

        String basis = String.format(
                Locale.ROOT,
                "distinct supporting categories: %s (%.2f); contradicting categories: %d (-%.2f); "
                        + "missing checks: %d (-%.2f)%s; score %.3f",
                describe(supporting),
                support,
                contradicting.categories().size(),
                contradictionPenalty,
                proposal.missingEvidence().size(),
                missingPenalty,
                unresolved == 0 ? "" : "; unresolved evidence links: " + unresolved,
                score);

        return new HypothesisAssessment(
                String.format("hyp-%03d", ordinal),
                investigationId,
                proposal.statement(),
                proposal.category(),
                score,
                basis,
                proposal.supportingEvidenceIds(),
                proposal.contradictingEvidenceIds(),
                proposal.missingEvidence(),
                proposal.impactedServices(),
                0);
    }

    private WeightedCategories categoriesOf(List<String> evidenceIds, Map<String, Evidence> evidenceById) {
        Set<String> categories = new HashSet<>();
        int unresolved = 0;
        for (String evidenceId : evidenceIds) {
            Evidence evidence = evidenceById.get(evidenceId);
            if (evidence == null) {
                unresolved++;
                continue;
            }
            String category = categoryOf(evidence.source());
            if (CATEGORY_WEIGHTS.containsKey(category)) {
                categories.add(category);
            }
        }
        return new WeightedCategories(categories, unresolved);
    }

    private String categoryOf(String source) {
        if (source == null) {
            return "unknown";
        }
        int separator = source.indexOf(':');
        return separator < 0 ? source : source.substring(0, separator);
    }

    private String describe(WeightedCategories supporting) {
        if (supporting.categories().isEmpty()) {
            return "none";
        }
        List<String> ordered = new ArrayList<>(supporting.categories());
        ordered.sort(Comparator.comparingDouble((String category) -> -CATEGORY_WEIGHTS.get(category))
                .thenComparing(Comparator.naturalOrder()));
        return String.join(", ", ordered);
    }

    private Map<String, Evidence> indexById(List<Evidence> evidence) {
        Map<String, Evidence> index = new LinkedHashMap<>();
        for (Evidence item : evidence) {
            index.put(item.id(), item);
        }
        return index;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }

    private record WeightedCategories(Set<String> categories, int unresolved) {

        double totalWeight() {
            return categories.stream()
                    .mapToDouble(CATEGORY_WEIGHTS::get)
                    .sum();
        }
    }
}
