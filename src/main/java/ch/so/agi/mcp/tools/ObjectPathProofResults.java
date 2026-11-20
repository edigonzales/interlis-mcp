package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.IliAuthoringResult.ConstraintProof;
import ch.so.agi.mcp.model.IliAuthoringResult.ProofVerification;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Every concrete route must prove the same requested constraint. */
final class ObjectPathProofResults {
  private ObjectPathProofResults() {}

  static ConstraintProof merge(List<ConstraintProof> results, List<Map<String, String>> routes) {
    if (results.size() == 1) return results.getFirst();
    var merged = results.getFirst();
    var firstUnverified = results.stream()
        .filter(result -> !result.generationVerified() || !Boolean.TRUE.equals(result.coverageComplete))
        .findFirst().orElse(null);
    if (firstUnverified != null && firstUnverified.reasonCode != null) {
      merged.reasonCode = firstUnverified.reasonCode;
      merged.reason = firstUnverified.reason;
      merged.reportIncomplete |= firstUnverified.reportIncomplete;
    }

    var verification = new ProofVerification();
    verification.cases = new ArrayList<>();
    verification.viewScopeChecked = true;
    verification.viewScopeGoals = new ArrayList<>();
    verification.viewScopeGaps = new ArrayList<>();
    verification.viewScopeExcluded = new ArrayList<>();
    for (int index = 0; index < results.size(); index++) {
      var result = results.get(index);
      var route = routes.get(index);
      String prefix = "route " + (index + 1) + ": ";
      for (var summary : result.generatedCases) {
        summary.name = prefix + summary.name;
        summary.details.put("concreteRoute", route);
      }
      result.coverageGaps.forEach(gap -> gap.details.put("concreteRoute", route));
      result.coverageExcludedGoals.forEach(exclusion -> exclusion.details.put("concreteRoute", route));
      if (result.verification != null) {
        var checked = result.verification;
        checked.cases.forEach(test -> test.name = prefix + test.name);
        checked.viewScopeGoals.forEach(goal -> goal.name = prefix + goal.name);
        checked.viewScopeGaps.forEach(gap -> gap.name = prefix + gap.name);
        checked.viewScopeExcluded.forEach(exclusion -> exclusion.name = prefix + exclusion.name);
        verification.cases.addAll(checked.cases);
        verification.viewScopeGoals.addAll(checked.viewScopeGoals);
        verification.viewScopeGaps.addAll(checked.viewScopeGaps);
        verification.viewScopeExcluded.addAll(checked.viewScopeExcluded);
      }
    }
    merged.coverageComplete = results.stream().allMatch(result -> Boolean.TRUE.equals(result.coverageComplete));
    merged.casesGenerated = results.stream().allMatch(result -> result.casesGenerated);
    merged.coverageGoalCount = results.stream().mapToInt(result -> Objects.requireNonNullElse(result.coverageGoalCount, 0)).sum();
    merged.coverageSolvedCount = results.stream().mapToInt(result -> Objects.requireNonNullElse(result.coverageSolvedCount, 0)).sum();
    merged.coverageGaps = results.stream().flatMap(result -> result.coverageGaps.stream()).toList();
    merged.coverageExcludedGoals = results.stream().flatMap(result -> result.coverageExcludedGoals.stream()).toList();
    merged.coverageExcludedCount = merged.coverageExcludedGoals.size();
    merged.generatedCases = results.stream().flatMap(result -> result.generatedCases.stream()).toList();
    merged.objectPathGoals = results.stream().flatMap(result -> result.objectPathGoals.stream()).toList();
    merged.objectPathGaps = results.stream().flatMap(result -> result.objectPathGaps.stream()).toList();
    merged.objectPathExcluded = results.stream().flatMap(result -> result.objectPathExcluded.stream()).toList();
    merged.verification = verification;
    merged.multipleRoutes = true;
    return merged;
  }
}
