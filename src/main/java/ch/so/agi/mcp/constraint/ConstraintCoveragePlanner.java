package ch.so.agi.mcp.constraint;

import ch.so.agi.mcp.constraint.ConstraintExpression.And;
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
 * <p>This planner is intentionally independent of decision tables. Numeric comparisons produce
 * boundary probes, enum/boolean comparisons cover the complete bound domain and DEFINED expressions
 * produce both presence states. The resulting assignments can be materialized by
 * {@link ConstraintModelSynthesizer} for any IR frontend.</p>
 */
public final class ConstraintCoveragePlanner {

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
    if (goals.isEmpty()) {
      goals.add(new ConstraintExpressionEngine.TestGoal(
          ConstraintExpressionEngine.GoalKind.TRUE, expression, "constraint witness"));
      goals.add(new ConstraintExpressionEngine.TestGoal(
          ConstraintExpressionEngine.GoalKind.FALSE, expression, "constraint counterexample"));
    }

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
      case Not not -> collect(not.operand(), binding, goals);
      case And and -> and.operands().forEach(operand -> collect(operand, binding, goals));
      case Or or -> or.operands().forEach(operand -> collect(operand, binding, goals));
      case Implies implies -> {
        collect(implies.antecedent(), binding, goals);
        collect(implies.consequent(), binding, goals);
      }
      case FunctionCall call -> call.arguments().forEach(argument -> collect(argument, binding, goals));
      default -> {
      }
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
