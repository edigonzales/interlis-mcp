package ch.so.agi.mcp.constraint;

import ch.so.agi.mcp.constraint.ConstraintExpression.And;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine.StateCondition;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine.GoalKind;
import ch.so.agi.mcp.constraint.ConstraintExpression.Attribute;
import ch.so.agi.mcp.constraint.ConstraintExpression.BooleanLiteral;
import ch.so.agi.mcp.constraint.ConstraintExpression.Comparison;
import ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator;
import ch.so.agi.mcp.constraint.ConstraintExpression.Defined;
import ch.so.agi.mcp.constraint.ConstraintExpression.EnumLiteral;
import ch.so.agi.mcp.constraint.ConstraintExpression.FunctionCall;
import ch.so.agi.mcp.constraint.ConstraintExpression.Implies;
import ch.so.agi.mcp.constraint.ConstraintExpression.Not;
import ch.so.agi.mcp.constraint.ConstraintExpression.NumericLiteral;
import ch.so.agi.mcp.constraint.ConstraintExpression.Or;
import ch.so.agi.mcp.constraint.ConstraintExpression.Path;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Derives model-aware semantic coverage probes and solves them through {@link ConstraintGoalSolver}.
 *
 * <p>The planner is independent of IR frontends. In addition to scalar boundaries and domain
 * categories it derives direct logical branch patterns, selected standard-function edge cases and
 * aggregate presence/cardinality probes. Proven unreachable structural goals are reported separately;
 * search exhaustion and required constraint outcomes remain proof-blocking gaps.</p>
 */
public final class ConstraintCoveragePlanner {

  private static final int MAX_RELEVANT_COLLECTION_SIZE = 3;

  public record CoverageCase(
      ConstraintExpressionEngine.TestGoal goal,
      ConstraintGoalSolver.Solution solution,
      List<ConstraintExpressionEngine.TestGoal> coveredGoals) {

    public CoverageCase {
      Objects.requireNonNull(goal, "goal");
      Objects.requireNonNull(solution, "solution");
      coveredGoals = List.copyOf(coveredGoals);
      if (!solution.solved()) {
        throw new IllegalArgumentException("CoverageCase requires a solved goal.");
      }
    }
  }

  public record CoveragePlan(
      List<CoverageCase> cases,
      List<ConstraintGoalSolver.Solution> unsolved,
      List<GoalExclusion> excluded) {

    public CoveragePlan {
      cases = cases == null ? List.of() : List.copyOf(cases);
      unsolved = unsolved == null ? List.of() : List.copyOf(unsolved);
      excluded = List.copyOf(excluded);
    }
  }

  public record GoalExclusion(ConstraintExpressionEngine.TestGoal goal, String justification) {}

  public static int solvedGoalCount(CoveragePlan plan) {
    return plan.cases().stream().mapToInt(item -> item.coveredGoals().size()).sum();
  }

  public static List<Map<String, Object>> excludedGoals(CoveragePlan plan, ConstraintExpression.IliVersion version) {
    return plan.excluded().stream().map(exclusion -> {
      Map<String, Object> result = new LinkedHashMap<>(describeGoal(exclusion.goal(), version));
      result.put("reasonCode", "PROVEN_UNREACHABLE");
      result.put("justification", exclusion.justification());
      return result;
    }).toList();
  }

  public static Map<String, Object> describeGoal(
      ConstraintExpressionEngine.TestGoal goal, ConstraintExpression.IliVersion version) {
    String description = goal.expression().toInterlis(version);
    if (!goal.conditions().isEmpty()) {
      description = "STATE CONDITIONS: " + goal.conditions().stream()
          .map(condition -> condition.state() + "[" + condition.expression().toInterlis(version) + "]")
          .collect(java.util.stream.Collectors.joining("; "));
    }
    return Map.of("goal", goal.kind().name(), "reason", goal.reason(), "expression", description);
  }

  private ConstraintCoveragePlanner() {
  }

  public static CoveragePlan solve(
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding) {
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(binding, "binding");

    LinkedHashSet<ConstraintExpressionEngine.TestGoal> goals = new LinkedHashSet<>();
    collect(expression, binding, goals);
    goals.add(new ConstraintExpressionEngine.TestGoal(
        ConstraintExpressionEngine.GoalKind.TRUE, expression, "constraint witness"));
    goals.add(new ConstraintExpressionEngine.TestGoal(
        ConstraintExpressionEngine.GoalKind.FALSE, expression, "constraint counterexample"));

    List<CoverageCase> cases = new ArrayList<>();
    List<ConstraintGoalSolver.Solution> unsolved = new ArrayList<>();
    List<GoalExclusion> excluded = new ArrayList<>();
    Map<String, List<ConstraintGoalSolver.Solution>> assignments = new LinkedHashMap<>();
    for (ConstraintExpressionEngine.TestGoal goal : goals) {
      ConstraintGoalSolver.Solution solution = ConstraintGoalSolver.solve(goal, binding);
      if (!solution.solved()) {
        GoalExclusion exclusion = exclusionFor(solution, binding);
        if (exclusion != null) {
          excluded.add(exclusion);
          continue;
        }
        unsolved.add(solution);
        continue;
      }
      assignments.computeIfAbsent(assignmentKey(solution.assignment()), ignored -> new ArrayList<>()).add(solution);
    }
    for (List<ConstraintGoalSolver.Solution> solutions : assignments.values()) {
      var first = solutions.getFirst();
      cases.add(new CoverageCase(first.goal(), first,
          solutions.stream().map(ConstraintGoalSolver.Solution::goal).toList()));
    }
    return new CoveragePlan(cases, unsolved, excluded);
  }

  static @Nullable GoalExclusion exclusionFor(ConstraintGoalSolver.Solution solution,
      ConstraintModelSynthesizer.ModelBinding binding) {
    var goal = solution.goal();
    boolean required = goal.reason().equals("constraint witness") || goal.reason().equals("constraint counterexample");
    // A bounded search or unsupported/failed materialization never justifies an exclusion.
    if (solution.solved() || required || !solution.reasonCode().equals("NO_SOLUTION_FOUND")) return null;
    var reachability = ConstraintGoalReachability.analyze(goal, binding);
    return reachability.status() == ConstraintGoalReachability.Status.PROVEN_UNREACHABLE
        ? new GoalExclusion(goal, reachability.justification()) : null;
  }

  private static void collect(
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding,
      Set<ConstraintExpressionEngine.TestGoal> goals) {
    switch (expression) {
      case ConstraintExpression.ObjectCount count -> {
        for (int value : List.of(0, 1, 2)) addNumericEquality(goals, count, BigDecimal.valueOf(value), "object count " + value + " for " + count.objects().path());
      }
      case Defined defined -> {
        goals.add(new ConstraintExpressionEngine.TestGoal(
            ConstraintExpressionEngine.GoalKind.DEFINED,
            defined.operand(),
            "expression defined"));
        goals.add(new ConstraintExpressionEngine.TestGoal(
            ConstraintExpressionEngine.GoalKind.UNDEFINED,
            defined.operand(),
            "expression undefined"));
        collect(defined.operand(), binding, goals);
      }
      case Comparison comparison -> {
        addComparisonProbes(comparison, binding, goals);
        collect(comparison.left(), binding, goals);
        collect(comparison.right(), binding, goals);
      }
      case Not not -> {
        goals.add(new ConstraintExpressionEngine.TestGoal(
            ConstraintExpressionEngine.GoalKind.TRUE,
            not.operand(),
            "NOT operand true"));
        goals.add(new ConstraintExpressionEngine.TestGoal(
            ConstraintExpressionEngine.GoalKind.FALSE,
            not.operand(),
            "NOT operand false"));
        goals.add(new ConstraintExpressionEngine.TestGoal(
            ConstraintExpressionEngine.GoalKind.UNDEFINED, not.operand(), "NOT operand undefined"));
        collect(not.operand(), binding, goals);
      }
      case And and -> {
        addAndProbes(and, goals);
        addDominatingProbes(and.operands(), false, goals);
        goals.add(new ConstraintExpressionEngine.TestGoal(
            ConstraintExpressionEngine.GoalKind.UNDEFINED, and, "AND result undefined"));
        and.operands().forEach(operand -> collect(operand, binding, goals));
      }
      case Or or -> {
        addOrProbes(or, goals);
        addDominatingProbes(or.operands(), true, goals);
        goals.add(new ConstraintExpressionEngine.TestGoal(
            ConstraintExpressionEngine.GoalKind.UNDEFINED, or, "OR result undefined"));
        or.operands().forEach(operand -> collect(operand, binding, goals));
      }
      case Implies implies -> {
        addImpliesProbes(implies, goals);
        collect(implies.antecedent(), binding, goals);
        collect(implies.consequent(), binding, goals);
      }
      case FunctionCall call -> {
        addFunctionEdgeProbes(call, binding, goals);
        call.arguments().forEach(argument -> collect(argument, binding, goals));
      }
      default -> {
      }
    }
  }

  private static void addAndProbes(
      And and,
      Set<ConstraintExpressionEngine.TestGoal> goals) {
    List<ConstraintExpression> operands = and.operands();
    addTruthPattern(
        goals, operands, all(operands.size(), true), "AND all operands true");
    for (int index = 0; index < operands.size(); index++) {
      boolean[] truth = all(operands.size(), true);
      truth[index] = false;
      addTruthPattern(
          goals,
          operands,
          truth,
          "AND operand " + (index + 1) + " independently false");
    }
  }

  private static void addOrProbes(
      Or or,
      Set<ConstraintExpressionEngine.TestGoal> goals) {
    List<ConstraintExpression> operands = or.operands();
    addTruthPattern(
        goals, operands, all(operands.size(), false), "OR all branches false");
    for (int index = 0; index < operands.size(); index++) {
      boolean[] truth = all(operands.size(), false);
      truth[index] = true;
      addTruthPattern(
          goals,
          operands,
          truth,
          "OR branch " + (index + 1) + " independently true");
    }
  }

  private static void addDominatingProbes(
      List<ConstraintExpression> operands, boolean or, Set<ConstraintExpressionEngine.TestGoal> goals) {
    for (int selected = 0; selected < operands.size(); selected++) {
      List<StateCondition> required = new ArrayList<>();
      for (int index = 0; index < selected; index++) {
        required.add(new StateCondition(or ? GoalKind.FALSE : GoalKind.TRUE, operands.get(index)));
      }
      required.add(new StateCondition(or ? GoalKind.TRUE : GoalKind.FALSE, operands.get(selected)));
      goals.add(new ConstraintExpressionEngine.TestGoal(GoalKind.TRUE,
          or ? new Or(operands) : new And(operands),
          (or ? "OR branch " : "AND operand ") + (selected + 1)
              + (or ? " dominates true" : " dominates false"), required));
    }
  }

  private static void addImpliesProbes(
      Implies implies, Set<ConstraintExpressionEngine.TestGoal> goals) {
    List<ConstraintExpression> operands = List.of(implies.antecedent(), implies.consequent());
    addTruthPattern(goals, operands, new boolean[] {false, false}, "IMPLIES false -> false");
    addTruthPattern(goals, operands, new boolean[] {false, true}, "IMPLIES false -> true");
    addTruthPattern(goals, operands, new boolean[] {true, true}, "IMPLIES true -> true");
    addTruthPattern(goals, operands, new boolean[] {true, false}, "IMPLIES true -> false violation");
    for (var left : List.of(ConstraintExpressionEngine.GoalKind.TRUE,
        ConstraintExpressionEngine.GoalKind.FALSE, ConstraintExpressionEngine.GoalKind.UNDEFINED)) {
      for (var right : List.of(ConstraintExpressionEngine.GoalKind.TRUE,
          ConstraintExpressionEngine.GoalKind.FALSE, ConstraintExpressionEngine.GoalKind.UNDEFINED)) {
        if (left != ConstraintExpressionEngine.GoalKind.UNDEFINED && right != ConstraintExpressionEngine.GoalKind.UNDEFINED) continue;
        goals.add(new ConstraintExpressionEngine.TestGoal(ConstraintExpressionEngine.GoalKind.TRUE,
            implies, "IMPLIES " + left.name().toLowerCase(java.util.Locale.ROOT) + " -> "
                + right.name().toLowerCase(java.util.Locale.ROOT),
            List.of(new StateCondition(left, implies.antecedent()), new StateCondition(right, implies.consequent()))));
      }
    }
  }

  private static void addTruthPattern(
      Set<ConstraintExpressionEngine.TestGoal> goals,
      List<ConstraintExpression> operands,
      boolean[] truth,
      String reason) {
    List<StateCondition> required = new ArrayList<>();
    for (int index = 0; index < operands.size(); index++) {
      required.add(new StateCondition(truth[index] ? GoalKind.TRUE : GoalKind.FALSE, operands.get(index)));
    }
    goals.add(new ConstraintExpressionEngine.TestGoal(GoalKind.TRUE,
        operands.size() == 1 ? operands.getFirst() : new And(operands), reason, required));
  }

  private static boolean[] all(int size, boolean value) {
    boolean[] result = new boolean[size];
    if (value) {
      java.util.Arrays.fill(result, true);
    }
    return result;
  }

  private static void addFunctionEdgeProbes(
      FunctionCall call,
      ConstraintModelSynthesizer.ModelBinding binding,
      Set<ConstraintExpressionEngine.TestGoal> goals) {
    if (isCollectionAggregate(call.semanticId())) {
      addAggregateProbes(call, binding, goals);
    } else if (hasOptionalReference(call, binding)) {
      goals.add(new ConstraintExpressionEngine.TestGoal(
          ConstraintExpressionEngine.GoalKind.DEFINED,
          call,
          "function result defined"));
      goals.add(new ConstraintExpressionEngine.TestGoal(
          ConstraintExpressionEngine.GoalKind.UNDEFINED,
          call,
          "function undefined propagation"));
    }

    switch (call.semanticId()) {
      case "NUMERIC_DIV" -> addAroundZeroProbes(
          call.arguments().get(1), binding, goals, "division denominator");
      case "NUMERIC_LOG", "NUMERIC_LOG10" -> addAroundZeroProbes(
          call.arguments().getFirst(), binding, goals, "logarithm domain");
      case "NUMERIC_SQRT" -> addAroundZeroProbes(
          call.arguments().getFirst(), binding, goals, "square-root domain");
      case "NUMERIC_ROUND" -> addRoundingProbes(call.arguments().getFirst(), binding, goals);
      default -> {
      }
    }
  }

  private static void addAggregateProbes(
      FunctionCall call,
      ConstraintModelSynthesizer.ModelBinding binding,
      Set<ConstraintExpressionEngine.TestGoal> goals) {
    if (call.arguments().isEmpty() || !(call.arguments().getFirst() instanceof Path path)) {
      return;
    }
    ConstraintModelSynthesizer.ReferenceBinding reference = binding.references().get(path.path());
    if (reference == null || reference.association() == null) {
      return;
    }
    ConstraintModelSynthesizer.AssociationBinding association = reference.association();
    if (association.minimum() == 0) {
      goals.add(new ConstraintExpressionEngine.TestGoal(
          ConstraintExpressionEngine.GoalKind.UNDEFINED,
          call,
          "aggregate empty collection"));
    }
    if (association.effectiveMaximum(MAX_RELEVANT_COLLECTION_SIZE) > 0) {
      goals.add(new ConstraintExpressionEngine.TestGoal(
          ConstraintExpressionEngine.GoalKind.DEFINED,
          call,
          "aggregate non-empty collection"));
    }

    if (!"COLLECTION_SUM".equals(call.semanticId())
        || reference.domain().kind() != ConstraintExpression.ScalarKind.NUMERIC
        || reference.domain().numeric() == null
        || reference.domain().numeric().maximum() == null
        || reference.domain().numeric().maximum().compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    int maximumCount = association.effectiveMaximum(MAX_RELEVANT_COLLECTION_SIZE);
    if (maximumCount <= Math.max(1, association.minimum())) {
      return;
    }
    BigDecimal target = reference.domain().numeric().maximum()
        .multiply(BigDecimal.valueOf(maximumCount));
    goals.add(new ConstraintExpressionEngine.TestGoal(
        ConstraintExpressionEngine.GoalKind.TRUE,
        new Comparison(ComparisonOperator.EQ, call, new NumericLiteral(target)),
        "aggregate maximum relevant cardinality " + maximumCount));
  }

  private static boolean isCollectionAggregate(String semanticId) {
    return "COLLECTION_SUM".equals(semanticId)
        || "COLLECTION_AVG".equals(semanticId)
        || "COLLECTION_MIN".equals(semanticId)
        || "COLLECTION_MAX".equals(semanticId);
  }

  private static boolean hasOptionalReference(
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding) {
    for (ConstraintExpression.Reference reference : expression.references()) {
      ConstraintModelSynthesizer.ReferenceBinding bound = binding.references().get(reference.name());
      if (bound == null) {
        continue;
      }
      if (!bound.domain().mandatory()) {
        return true;
      }
      if (bound.association() != null && bound.association().minimum() == 0) {
        return true;
      }
    }
    return false;
  }

  private static void addAroundZeroProbes(
      ConstraintExpression operand,
      ConstraintModelSynthesizer.ModelBinding binding,
      Set<ConstraintExpressionEngine.TestGoal> goals,
      String reasonPrefix) {
    ConstraintModelSynthesizer.NumericDomain domain = directNumericDomain(operand, binding);
    if (domain == null) {
      return;
    }
    addNumericInputProbe(goals, operand, domain, BigDecimal.ZERO, reasonPrefix + " at zero");
    addNumericInputProbe(
        goals,
        operand,
        domain,
        domain.step().negate(),
        reasonPrefix + " just below zero");
    addNumericInputProbe(
        goals,
        operand,
        domain,
        domain.step(),
        reasonPrefix + " just above zero");
  }

  private static void addRoundingProbes(
      ConstraintExpression operand,
      ConstraintModelSynthesizer.ModelBinding binding,
      Set<ConstraintExpressionEngine.TestGoal> goals) {
    ConstraintModelSynthesizer.NumericDomain domain = directNumericDomain(operand, binding);
    if (domain == null) {
      return;
    }
    for (BigDecimal half : List.of(new BigDecimal("-0.5"), new BigDecimal("0.5"))) {
      addNumericInputProbe(goals, operand, domain, half, "rounding half boundary " + half);
      addNumericInputProbe(
          goals,
          operand,
          domain,
          half.subtract(domain.step()),
          "rounding just below " + half);
      addNumericInputProbe(
          goals,
          operand,
          domain,
          half.add(domain.step()),
          "rounding just above " + half);
    }
  }

  private static ConstraintModelSynthesizer.NumericDomain directNumericDomain(
      ConstraintExpression operand,
      ConstraintModelSynthesizer.ModelBinding binding) {
    if (!(operand instanceof Attribute) && !(operand instanceof Path)) {
      return null;
    }
    if (!operand.type().isScalar(ConstraintExpression.ScalarKind.NUMERIC)
        || operand.references().size() != 1) {
      return null;
    }
    ConstraintExpression.Reference reference = operand.references().iterator().next();
    ConstraintModelSynthesizer.ReferenceBinding bound = binding.references().get(reference.name());
    if (bound == null || bound.domain().kind() != ConstraintExpression.ScalarKind.NUMERIC) {
      return null;
    }
    return bound.domain().numeric();
  }

  private static void addNumericInputProbe(
      Set<ConstraintExpressionEngine.TestGoal> goals,
      ConstraintExpression operand,
      ConstraintModelSynthesizer.NumericDomain domain,
      BigDecimal value,
      String reason) {
    if (domain.contains(value)) {
      addNumericEquality(goals, operand, value, reason);
    }
  }

  private static void addComparisonProbes(
      Comparison comparison,
      ConstraintModelSynthesizer.ModelBinding binding,
      Set<ConstraintExpressionEngine.TestGoal> goals) {
    ConstraintExpression operand;
    ConstraintExpression literal;
    ComparisonOperator operator;
    if (isLiteral(comparison.right())) {
      operand = comparison.left();
      literal = comparison.right();
      operator = comparison.operator();
    } else if (isLiteral(comparison.left())) {
      operand = comparison.right();
      literal = comparison.left();
      operator = reverse(comparison.operator());
    } else {
      goals.add(new ConstraintExpressionEngine.TestGoal(
          ConstraintExpressionEngine.GoalKind.TRUE, comparison, "comparison satisfied"));
      goals.add(new ConstraintExpressionEngine.TestGoal(
          ConstraintExpressionEngine.GoalKind.FALSE, comparison, "comparison violated"));
      return;
    }

    if (literal instanceof NumericLiteral numeric
        && operand.type().isScalar(ConstraintExpression.ScalarKind.NUMERIC)) {
      addNumericProbes(operand, operator, numeric.value(), binding, goals);
      return;
    }
    if (literal instanceof BooleanLiteral
        && operand.type().isScalar(ConstraintExpression.ScalarKind.BOOLEAN)) {
      addBooleanProbes(operand, binding, goals);
      return;
    }
    if (literal instanceof EnumLiteral
        && operand.type().isScalar(ConstraintExpression.ScalarKind.ENUM)) {
      addEnumProbes(operand, binding, goals);
      return;
    }

    goals.add(new ConstraintExpressionEngine.TestGoal(
        ConstraintExpressionEngine.GoalKind.TRUE, comparison, "comparison satisfied"));
    goals.add(new ConstraintExpressionEngine.TestGoal(
        ConstraintExpressionEngine.GoalKind.FALSE, comparison, "comparison violated"));
  }

  private static void addNumericProbes(
      ConstraintExpression operand,
      ComparisonOperator operator,
      BigDecimal pivot,
      ConstraintModelSynthesizer.ModelBinding binding,
      Set<ConstraintExpressionEngine.TestGoal> goals) {
    BigDecimal step = numericStep(operand, pivot, binding);
    BigDecimal below = pivot.subtract(step);
    BigDecimal above = pivot.add(step);
    switch (operator) {
      case EQ, NE -> {
        addNumericEqualityIfInDomain(goals, operand, pivot, binding, "at comparison value");
        addNumericEqualityIfInDomain(goals, operand, below, binding, "just below comparison value");
        addNumericEqualityIfInDomain(goals, operand, above, binding, "just above comparison value");
      }
      case LT -> {
        addNumericEqualityIfInDomain(goals, operand, below, binding, "just below exclusive upper bound");
        addNumericEqualityIfInDomain(goals, operand, pivot, binding, "at exclusive upper bound");
      }
      case LE -> {
        addNumericEqualityIfInDomain(goals, operand, pivot, binding, "at inclusive upper bound");
        addNumericEqualityIfInDomain(goals, operand, above, binding, "just above inclusive upper bound");
      }
      case GT -> {
        addNumericEqualityIfInDomain(goals, operand, pivot, binding, "at exclusive lower bound");
        addNumericEqualityIfInDomain(goals, operand, above, binding, "just above exclusive lower bound");
      }
      case GE -> {
        addNumericEqualityIfInDomain(goals, operand, below, binding, "just below inclusive lower bound");
        addNumericEqualityIfInDomain(goals, operand, pivot, binding, "at inclusive lower bound");
      }
    }
  }

  private static void addNumericEquality(
      Set<ConstraintExpressionEngine.TestGoal> goals,
      ConstraintExpression operand,
      BigDecimal value,
      String reason) {
    Comparison probe = new Comparison(
        ComparisonOperator.EQ,
        operand,
        new NumericLiteral(value));
    goals.add(new ConstraintExpressionEngine.TestGoal(
        ConstraintExpressionEngine.GoalKind.TRUE, probe, reason));
  }

  private static void addNumericEqualityIfInDomain(
      Set<ConstraintExpressionEngine.TestGoal> goals,
      ConstraintExpression operand,
      BigDecimal value,
      ConstraintModelSynthesizer.ModelBinding binding,
      String reason) {
    ConstraintModelSynthesizer.ValueDomain domain = operand instanceof Attribute || operand instanceof Path
        ? singleReferenceDomain(operand, binding) : null;
    if (domain != null && domain.numeric() != null && !domain.numeric().contains(value)) return;
    addNumericEquality(goals, operand, value, reason);
  }

  private static void addBooleanProbes(
      ConstraintExpression operand,
      ConstraintModelSynthesizer.ModelBinding binding,
      Set<ConstraintExpressionEngine.TestGoal> goals) {
    ConstraintModelSynthesizer.ValueDomain domain = singleReferenceDomain(operand, binding);
    if (domain == null || domain.kind() != ConstraintExpression.ScalarKind.BOOLEAN) {
      return;
    }
    for (boolean value : List.of(false, true)) {
      Comparison probe = new Comparison(
          ComparisonOperator.EQ,
          operand,
          new BooleanLiteral(value));
      goals.add(new ConstraintExpressionEngine.TestGoal(
          ConstraintExpressionEngine.GoalKind.TRUE,
          probe,
          "boolean domain value " + value));
    }
  }

  private static void addEnumProbes(
      ConstraintExpression operand,
      ConstraintModelSynthesizer.ModelBinding binding,
      Set<ConstraintExpressionEngine.TestGoal> goals) {
    ConstraintModelSynthesizer.ValueDomain domain = singleReferenceDomain(operand, binding);
    if (domain == null || domain.kind() != ConstraintExpression.ScalarKind.ENUM) {
      return;
    }
    for (String value : domain.values()) {
      Comparison probe = new Comparison(
          ComparisonOperator.EQ,
          operand,
          new EnumLiteral(value));
      goals.add(new ConstraintExpressionEngine.TestGoal(
          ConstraintExpressionEngine.GoalKind.TRUE,
          probe,
          "enum domain value #" + value));
    }
  }

  private static BigDecimal numericStep(
      ConstraintExpression operand,
      BigDecimal pivot,
      ConstraintModelSynthesizer.ModelBinding binding) {
    BigDecimal step = BigDecimal.ONE.movePointLeft(Math.max(0, pivot.scale()));
    for (ConstraintExpression.Reference reference : operand.references()) {
      ConstraintModelSynthesizer.ReferenceBinding bound = binding.references().get(reference.name());
      if (bound == null
          || bound.domain().kind() != ConstraintExpression.ScalarKind.NUMERIC
          || bound.domain().numeric() == null) {
        continue;
      }
      BigDecimal candidate = bound.domain().numeric().step();
      if (candidate.compareTo(step) < 0) {
        step = candidate;
      }
    }
    return step;
  }

  private static ConstraintModelSynthesizer.ValueDomain singleReferenceDomain(
      ConstraintExpression operand,
      ConstraintModelSynthesizer.ModelBinding binding) {
    if (operand.references().size() != 1) {
      return null;
    }
    ConstraintExpression.Reference reference = operand.references().iterator().next();
    ConstraintModelSynthesizer.ReferenceBinding bound = binding.references().get(reference.name());
    return bound == null ? null : bound.domain();
  }

  private static boolean isLiteral(ConstraintExpression expression) {
    return expression instanceof NumericLiteral
        || expression instanceof BooleanLiteral
        || expression instanceof EnumLiteral;
  }

  private static ComparisonOperator reverse(ComparisonOperator operator) {
    return switch (operator) {
      case EQ -> ComparisonOperator.EQ;
      case NE -> ComparisonOperator.NE;
      case LT -> ComparisonOperator.GT;
      case LE -> ComparisonOperator.GE;
      case GT -> ComparisonOperator.LT;
      case GE -> ComparisonOperator.LE;
    };
  }

  private static String assignmentKey(Map<String, Object> assignment) {
    Map<String, Object> sorted = new LinkedHashMap<>();
    assignment.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
    return sorted.toString();
  }
}
