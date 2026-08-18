package ch.so.agi.mcp.constraint;

import ch.so.agi.mcp.constraint.ConstraintExpression.And;
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

/**
 * Derives model-aware semantic coverage probes and solves them through {@link ConstraintGoalSolver}.
 *
 * <p>The planner is independent of IR frontends. In addition to scalar boundaries and domain
 * categories it derives direct logical branch patterns, selected standard-function edge cases and
 * aggregate presence/cardinality probes. Unreachable goals remain explicit in {@link
 * CoveragePlan#unsolved()} instead of being guessed away.</p>
 */
public final class ConstraintCoveragePlanner {

  private static final int MAX_RELEVANT_COLLECTION_SIZE = 3;

  public record CoverageCase(
      ConstraintExpressionEngine.TestGoal goal,
      ConstraintGoalSolver.Solution solution) {

    public CoverageCase {
      Objects.requireNonNull(goal, "goal");
      Objects.requireNonNull(solution, "solution");
      if (!solution.solved()) {
        throw new IllegalArgumentException("CoverageCase requires a solved goal.");
      }
    }
  }

  public record CoveragePlan(
      List<CoverageCase> cases,
      List<ConstraintGoalSolver.Solution> unsolved) {

    public CoveragePlan {
      cases = cases == null ? List.of() : List.copyOf(cases);
      unsolved = unsolved == null ? List.of() : List.copyOf(unsolved);
    }
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
    Set<String> assignments = new LinkedHashSet<>();
    for (ConstraintExpressionEngine.TestGoal goal : goals) {
      ConstraintGoalSolver.Solution solution = ConstraintGoalSolver.solve(goal, binding);
      if (!solution.solved()) {
        unsolved.add(solution);
        continue;
      }
      if (assignments.add(assignmentKey(solution.assignment()))) {
        cases.add(new CoverageCase(goal, solution));
      }
    }
    return new CoveragePlan(cases, unsolved);
  }

  private static void collect(
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding,
      Set<ConstraintExpressionEngine.TestGoal> goals) {
    switch (expression) {
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
        collect(not.operand(), binding, goals);
      }
      case And and -> {
        addAndProbes(and, goals);
        and.operands().forEach(operand -> collect(operand, binding, goals));
      }
      case Or or -> {
        addOrProbes(or, goals);
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
    addTruthPattern(goals, operands, all(operands.size(), true), "AND all operands true");
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
    addTruthPattern(goals, operands, all(operands.size(), false), "OR all branches false");
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

  private static void addImpliesProbes(
      Implies implies,
      Set<ConstraintExpressionEngine.TestGoal> goals) {
    List<ConstraintExpression> operands = List.of(implies.antecedent(), implies.consequent());
    addTruthPattern(goals, operands, new boolean[] {false, false}, "IMPLIES false -> false");
    addTruthPattern(goals, operands, new boolean[] {false, true}, "IMPLIES false -> true");
    addTruthPattern(goals, operands, new boolean[] {true, true}, "IMPLIES true -> true");
    addTruthPattern(goals, operands, new boolean[] {true, false}, "IMPLIES true -> false violation");
  }

  private static void addTruthPattern(
      Set<ConstraintExpressionEngine.TestGoal> goals,
      List<ConstraintExpression> operands,
      boolean[] truth,
      String reason) {
    List<ConstraintExpression> required = new ArrayList<>();
    for (int index = 0; index < operands.size(); index++) {
      required.add(truth[index] ? operands.get(index) : new Not(operands.get(index)));
    }
    goals.add(new ConstraintExpressionEngine.TestGoal(
        ConstraintExpressionEngine.GoalKind.TRUE,
        required.size() == 1 ? required.getFirst() : new And(required),
        reason));
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
        addNumericEquality(goals, operand, pivot, "at comparison value");
        addNumericEquality(goals, operand, below, "just below comparison value");
        addNumericEquality(goals, operand, above, "just above comparison value");
      }
      case LT -> {
        addNumericEquality(goals, operand, below, "just below exclusive upper bound");
        addNumericEquality(goals, operand, pivot, "at exclusive upper bound");
      }
      case LE -> {
        addNumericEquality(goals, operand, pivot, "at inclusive upper bound");
        addNumericEquality(goals, operand, above, "just above inclusive upper bound");
      }
      case GT -> {
        addNumericEquality(goals, operand, pivot, "at exclusive lower bound");
        addNumericEquality(goals, operand, above, "just above exclusive lower bound");
      }
      case GE -> {
        addNumericEquality(goals, operand, below, "just below inclusive lower bound");
        addNumericEquality(goals, operand, pivot, "at inclusive lower bound");
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
