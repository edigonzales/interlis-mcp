package ch.so.agi.mcp.tools;

import static ch.so.agi.mcp.tools.ConstraintCaseGenerationTools.summaryAssignment;

import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.ConstraintExpression;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine;
import ch.so.agi.mcp.constraint.ConstraintGoalSolver;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer;
import ch.so.agi.mcp.constraint.SemanticConstraint;
import ch.so.agi.mcp.model.IliAuthoringResult.CoverageGap;
import ch.so.agi.mcp.model.IliAuthoringResult.ProofCase;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validator-backed B9 proof planning for population-level PLAUSIBILITY semantics. */
final class PlausibilityConstraintCasePlanner {

  private static final int MAX_POPULATION = 20;
  private static final int DISPLAY_SCALE = 6;

  record Plan(
      List<ConstraintTestTools.TestCase> cases,
      List<ProofCase> summaries,
      List<CoverageGap> unsolved) {

    Plan {
      cases = cases == null ? List.of() : List.copyOf(cases);
      summaries = summaries == null ? List.of() : List.copyOf(summaries);
      unsolved = unsolved == null ? List.of() : List.copyOf(unsolved);
      if (cases.size() != summaries.size()) {
        throw new IllegalArgumentException(
            "PLAUSIBILITY proof cases and summaries must have equal size.");
      }
    }
  }

  private record RatioTarget(
      String boundary,
      int successful,
      int total,
      BigDecimal actualPercentage,
      boolean expectedValid) {

    int failed() {
      return total - successful;
    }
  }

  private PlausibilityConstraintCasePlanner() {
  }

  static Plan plan(
      CompiledConstraintContext context,
      SemanticConstraint.Plausibility plausibility) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(plausibility, "plausibility");

    ConstraintModelSynthesizer.ModelBinding binding = ch.so.agi.mcp.constraint.ViewProofScope.bind(
        context,
        plausibility.contextFqn(),
        plausibility.condition());

    ConstraintGoalSolver.Solution trueSolution = solve(
        plausibility.condition(),
        binding,
        ConstraintExpressionEngine.GoalKind.TRUE,
        "PLAUSIBILITY condition true population member");
    ConstraintGoalSolver.Solution falseSolution = solve(
        plausibility.condition(),
        binding,
        ConstraintExpressionEngine.GoalKind.FALSE,
        "PLAUSIBILITY condition false population member");

    List<ConstraintTestTools.TestCase> cases = new ArrayList<>();
    List<ProofCase> summaries = new ArrayList<>();
    List<CoverageGap> unsolved = new ArrayList<>();

    int caseIndex = 1;
    for (RatioTarget target : ratioTargets(plausibility)) {
      List<String> missing = new ArrayList<>();
      if (target.successful() > 0 && !trueSolution.solved()) {
        missing.add("TRUE");
      }
      if (target.failed() > 0 && !falseSolution.solved()) {
        missing.add("FALSE");
      }
      if (!missing.isEmpty()) {
        var gap = new CoverageGap("PLAUSIBILITY_POPULATION_MEMBER_UNSOLVED", "The finite-domain solver could not synthesize every condition result needed for this population boundary.", target.boundary());
        gap.details.put("missingConditionResults", List.copyOf(missing));
        gap.details.put("trueSolverReason", solverReason(trueSolution));
        gap.details.put("falseSolverReason", solverReason(falseSolution));
        unsolved.add(gap);
        continue;
      }

      Population population;
      try {
        population = population(
            plausibility,
            binding,
            trueSolution.assignment(),
            falseSolution.assignment(),
            target,
            caseIndex);
      } catch (IllegalArgumentException ex) {
        unsolved.add(new CoverageGap("PLAUSIBILITY_POPULATION_GRAPH_UNSAFE", ex.getMessage(), target.boundary()));
        continue;
      }

      ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
      testCase.name = "plausibility " + target.boundary().toLowerCase()
          + " boundary - " + display(target.actualPercentage()) + "%";
      testCase.expectedConstraintValid = target.expectedValid();
      testCase.objects = population.objects();
      testCase.links = population.links();
      cases.add(testCase);

      var summary = new ProofCase(testCase.name, target.expectedValid() ? "WITNESS" : "COUNTEREXAMPLE", target.expectedValid());
      summary.details.put("boundary", target.boundary());
      summary.details.put("direction", plausibility.direction().name());
      summary.details.put("thresholdPercentage", display(plausibility.percentage()));
      summary.details.put("actualPercentage", display(target.actualPercentage()));
      summary.details.put("successfulCount", target.successful());
      summary.details.put("failedCount", target.failed());
      summary.details.put("populationSize", target.total());
      if (target.successful() > 0) {
        summary.details.put("conditionTrueValues", summaryAssignment(trueSolution.assignment()));
      }
      if (target.failed() > 0) {
        summary.details.put("conditionFalseValues", summaryAssignment(falseSolution.assignment()));
      }
      summaries.add(summary);
      caseIndex++;
    }

    ConstraintGoalSolver.Solution undefinedSolution = solve(
        plausibility.condition(),
        binding,
        ConstraintExpressionEngine.GoalKind.UNDEFINED,
        "PLAUSIBILITY undefined/skipEvaluation population member");
    if (undefinedSolution.solved()) {
      try {
        RatioTarget undefinedTarget = new RatioTarget(
            "UNDEFINED_COUNTS_AS_SUCCESS",
            1,
            1,
            BigDecimal.valueOf(100),
            validFor(plausibility, 1, 1));
        Population population = population(
            plausibility,
            binding,
            undefinedSolution.assignment(),
            Map.of(),
            undefinedTarget,
            caseIndex);
        ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
        testCase.name = "plausibility undefined condition counts as success";
        testCase.expectedConstraintValid = undefinedTarget.expectedValid();
        testCase.objects = population.objects();
        testCase.links = population.links();
        cases.add(testCase);

        var summary = new ProofCase(testCase.name, undefinedTarget.expectedValid() ? "WITNESS" : "COUNTEREXAMPLE", undefinedTarget.expectedValid());
        summary.details.put("boundary", undefinedTarget.boundary());
        summary.details.put("direction", plausibility.direction().name());
        summary.details.put("thresholdPercentage", display(plausibility.percentage()));
        summary.details.put("actualPercentage", "100");
        summary.details.put("successfulCount", 1);
        summary.details.put("failedCount", 0);
        summary.details.put("populationSize", 1);
        summary.details.put("conditionUndefinedValues", summaryAssignment(undefinedSolution.assignment()));
        summary.details.put("validatorSemantics", "skipEvaluation counts as successful");
        summaries.add(summary);
      } catch (IllegalArgumentException ex) {
        unsolved.add(new CoverageGap("PLAUSIBILITY_UNDEFINED_GRAPH_UNSAFE", ex.getMessage(), "UNDEFINED_COUNTS_AS_SUCCESS"));
      }
    }

    return new Plan(cases, summaries, unsolved);
  }

  private static ConstraintGoalSolver.Solution solve(
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding,
      ConstraintExpressionEngine.GoalKind kind,
      String reason) {
    return ConstraintGoalSolver.solve(
        new ConstraintExpressionEngine.TestGoal(kind, expression, reason),
        binding);
  }

  private static List<RatioTarget> ratioTargets(SemanticConstraint.Plausibility plausibility) {
    RatioCandidate lower = null;
    RatioCandidate exact = null;
    RatioCandidate upper = null;
    BigDecimal threshold = plausibility.percentage();

    for (int total = 1; total <= MAX_POPULATION; total++) {
      for (int successful = 0; successful <= total; successful++) {
        int comparison = BigDecimal.valueOf(successful)
            .multiply(BigDecimal.valueOf(100))
            .compareTo(threshold.multiply(BigDecimal.valueOf(total)));
        RatioCandidate candidate = new RatioCandidate(successful, total);
        if (comparison < 0 && (lower == null || compareRatio(candidate, lower) > 0
            || compareRatio(candidate, lower) == 0 && candidate.total() < lower.total())) {
          lower = candidate;
        } else if (comparison == 0 && (exact == null || candidate.total() < exact.total())) {
          exact = candidate;
        } else if (comparison > 0 && (upper == null || compareRatio(candidate, upper) < 0
            || compareRatio(candidate, upper) == 0 && candidate.total() < upper.total())) {
          upper = candidate;
        }
      }
    }

    List<RatioTarget> result = new ArrayList<>();
    if (lower != null) {
      result.add(target("BELOW", lower, plausibility));
    }
    if (exact != null) {
      result.add(target("EXACT", exact, plausibility));
    }
    if (upper != null) {
      result.add(target("ABOVE", upper, plausibility));
    }
    return List.copyOf(result);
  }

  private static RatioTarget target(
      String boundary,
      RatioCandidate candidate,
      SemanticConstraint.Plausibility plausibility) {
    BigDecimal actual = percentage(candidate.successful(), candidate.total());
    return new RatioTarget(
        boundary,
        candidate.successful(),
        candidate.total(),
        actual,
        validFor(plausibility, candidate.successful(), candidate.total()));
  }

  private static boolean validFor(
      SemanticConstraint.Plausibility plausibility,
      int successful,
      int total) {
    int comparison = BigDecimal.valueOf(successful)
        .multiply(BigDecimal.valueOf(100))
        .compareTo(plausibility.percentage().multiply(BigDecimal.valueOf(total)));
    return switch (plausibility.direction()) {
      case AT_LEAST -> comparison >= 0;
      case AT_MOST -> comparison <= 0;
    };
  }

  private static int compareRatio(RatioCandidate left, RatioCandidate right) {
    return Long.compare(
        (long) left.successful() * right.total(),
        (long) right.successful() * left.total());
  }

  private static BigDecimal percentage(int successful, int total) {
    return BigDecimal.valueOf(successful)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(total), 12, RoundingMode.HALF_UP)
        .stripTrailingZeros();
  }

  private static Population population(
      SemanticConstraint.Plausibility plausibility,
      ConstraintModelSynthesizer.ModelBinding binding,
      Map<String, Object> trueAssignment,
      Map<String, Object> falseAssignment,
      RatioTarget target,
      int caseIndex) {
    List<ConstraintTestTools.TestObject> objects = new ArrayList<>();
    List<ConstraintTestTools.TestLink> links = new ArrayList<>();

    for (int i = 0; i < target.successful(); i++) {
      addGraph(
          plausibility,
          ConstraintModelSynthesizer.synthesize(
              binding,
              trueAssignment,
              "plausibility_" + caseIndex + "_success_" + (i + 1)),
          objects,
          links);
    }
    for (int i = 0; i < target.failed(); i++) {
      addGraph(
          plausibility,
          ConstraintModelSynthesizer.synthesize(
              binding,
              falseAssignment,
              "plausibility_" + caseIndex + "_fail_" + (i + 1)),
          objects,
          links);
    }
    if (objects.isEmpty()) {
      throw new IllegalArgumentException("PLAUSIBILITY proof population must contain at least one object.");
    }
    return new Population(List.copyOf(objects), List.copyOf(links));
  }

  private static void addGraph(
      SemanticConstraint.Plausibility plausibility,
      ConstraintModelSynthesizer.ObjectGraph graph,
      List<ConstraintTestTools.TestObject> objects,
      List<ConstraintTestTools.TestLink> links) {
    long populationObjects = graph.objects().stream()
        .filter(object -> plausibility.contextFqn().equals(object.classFqn()))
        .count();
    if (populationObjects != 1) {
      throw new IllegalArgumentException(
          "A synthesized PLAUSIBILITY member must materialize exactly one object of the constraint context; got "
              + populationObjects + " for " + plausibility.contextFqn() + ".");
    }

    graph.objects().stream().map(ConstraintTestTools.TestObject::fromGraph).forEach(objects::add);
    graph.links().stream().map(ConstraintTestTools.TestLink::fromGraph).forEach(links::add);
  }

  private static String solverReason(ConstraintGoalSolver.Solution solution) {
    if (solution.solved()) {
      return "SOLVED";
    }
    String code = solution.reasonCode() == null || solution.reasonCode().isBlank()
        ? "UNSOLVED"
        : solution.reasonCode();
    return code + ": " + solution.reason();
  }

  private static String display(BigDecimal value) {
    BigDecimal normalized = value.setScale(
        Math.min(DISPLAY_SCALE, Math.max(0, value.scale())),
        RoundingMode.HALF_UP).stripTrailingZeros();
    return normalized.toPlainString();
  }

  private record RatioCandidate(int successful, int total) {
  }

  private record Population(
      List<ConstraintTestTools.TestObject> objects,
      List<ConstraintTestTools.TestLink> links) {
  }
}
