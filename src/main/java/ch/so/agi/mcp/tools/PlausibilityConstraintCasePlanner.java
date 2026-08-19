package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.ConstraintExpression;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine;
import ch.so.agi.mcp.constraint.ConstraintGoalSolver;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer;
import ch.so.agi.mcp.constraint.SemanticConstraint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validator-backed B9 proof planning for population-level PLAUSIBILITY semantics. */
final class PlausibilityConstraintCasePlanner {

  private static final int MAX_POPULATION = 20;
  private static final int DISPLAY_SCALE = 6;

  record Plan(
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries,
      List<Map<String, Object>> unsolved) {

    Plan {
      cases = cases == null ? List.of() : List.copyOf(cases);
      summaries = summaries == null ? List.of() : List.copyOf(summaries);
      unsolved = unsolved == null ? List.of() : List.copyOf(unsolved);
      if (cases.size() != summaries.size()) {
        throw new IllegalArgumentException(
            "PLAUSIBILITY proof cases and summaries must have equal size.");
      }
    }

    int goalCount() {
      return cases.size() + unsolved.size();
    }

    boolean complete() {
      return unsolved.isEmpty();
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

    ConstraintModelSynthesizer.ModelBinding binding = ConstraintModelSynthesizer.bind(
        context.transferDescription(),
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
    List<Map<String, Object>> summaries = new ArrayList<>();
    List<Map<String, Object>> unsolved = new ArrayList<>();

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
        unsolved.add(Map.of(
            "reasonCode", "PLAUSIBILITY_POPULATION_MEMBER_UNSOLVED",
            "reason", "The finite-domain solver could not synthesize every condition result needed for this population boundary.",
            "goal", target.boundary(),
            "missingConditionResults", List.copyOf(missing),
            "trueSolverReason", solverReason(trueSolution),
            "falseSolverReason", solverReason(falseSolution)));
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
        unsolved.add(Map.of(
            "reasonCode", "PLAUSIBILITY_POPULATION_GRAPH_UNSAFE",
            "reason", ex.getMessage(),
            "goal", target.boundary()));
        continue;
      }

      ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
      testCase.name = "plausibility " + target.boundary().toLowerCase()
          + " boundary - " + display(target.actualPercentage()) + "%";
      testCase.expectedConstraintValid = target.expectedValid();
      testCase.objects = population.objects();
      testCase.links = population.links();
      cases.add(testCase);

      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("purpose", target.expectedValid() ? "WITNESS" : "COUNTEREXAMPLE");
      summary.put("name", testCase.name);
      summary.put("boundary", target.boundary());
      summary.put("direction", plausibility.direction().name());
      summary.put("thresholdPercentage", display(plausibility.percentage()));
      summary.put("actualPercentage", display(target.actualPercentage()));
      summary.put("successfulCount", target.successful());
      summary.put("failedCount", target.failed());
      summary.put("populationSize", target.total());
      summary.put("expectedConstraintValid", target.expectedValid());
      if (target.successful() > 0) {
        summary.put("conditionTrueValues", summaryAssignment(trueSolution.assignment()));
      }
      if (target.failed() > 0) {
        summary.put("conditionFalseValues", summaryAssignment(falseSolution.assignment()));
      }
      summaries.add(Map.copyOf(summary));
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

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("purpose", undefinedTarget.expectedValid() ? "WITNESS" : "COUNTEREXAMPLE");
        summary.put("name", testCase.name);
        summary.put("boundary", undefinedTarget.boundary());
        summary.put("direction", plausibility.direction().name());
        summary.put("thresholdPercentage", display(plausibility.percentage()));
        summary.put("actualPercentage", "100");
        summary.put("successfulCount", 1);
        summary.put("failedCount", 0);
        summary.put("populationSize", 1);
        summary.put("expectedConstraintValid", undefinedTarget.expectedValid());
        summary.put("conditionUndefinedValues", summaryAssignment(undefinedSolution.assignment()));
        summary.put("validatorSemantics", "skipEvaluation counts as successful");
        summaries.add(Map.copyOf(summary));
      } catch (IllegalArgumentException ex) {
        unsolved.add(Map.of(
            "reasonCode", "PLAUSIBILITY_UNDEFINED_GRAPH_UNSAFE",
            "reason", ex.getMessage(),
            "goal", "UNDEFINED_COUNTS_AS_SUCCESS"));
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

    for (ConstraintModelSynthesizer.GraphObject object : graph.objects()) {
      ConstraintTestTools.TestObject result = new ConstraintTestTools.TestObject();
      result.classFqn = object.classFqn();
      result.oid = object.oid();
      result.values = object.values();
      result.references = object.references();
      objects.add(result);
    }
    for (ConstraintModelSynthesizer.GraphLink link : graph.links()) {
      ConstraintTestTools.TestLink result = new ConstraintTestTools.TestLink();
      result.associationFqn = link.associationFqn();
      result.roles = link.roles();
      links.add(result);
    }
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

  private static Map<String, Object> summaryAssignment(Map<String, Object> assignment) {
    Map<String, Object> result = new LinkedHashMap<>();
    assignment.forEach((name, value) -> result.put(name, summaryValue(value)));
    return Map.copyOf(result);
  }

  private static Object summaryValue(Object value) {
    if (value == null || value == ConstraintExpressionEngine.Undefined.INSTANCE) {
      return "UNDEFINED";
    }
    if (value instanceof BigDecimal number) {
      return number.stripTrailingZeros().toPlainString();
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(PlausibilityConstraintCasePlanner::summaryValue).toList();
    }
    return value;
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
