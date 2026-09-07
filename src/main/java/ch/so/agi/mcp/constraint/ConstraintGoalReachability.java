package ch.so.agi.mcp.constraint;

import ch.so.agi.mcp.constraint.ConstraintExpression.*;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine.TestGoal;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine.Undefined;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer.ModelBinding;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer.ReferenceBinding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exhaustive predicate partitions over a conservative superset of model-valid scalar values.
 * This is independent of the solver's sampled candidates and of fixture materialization.
 */
public final class ConstraintGoalReachability {
  private static final int MAX_STATES = 50_000;

  public enum Status { PROVEN_UNREACHABLE, UNKNOWN }

  public record Result(Status status, String justification) {}

  private record Domain(String reference, List<Object> values) {}

  private ConstraintGoalReachability() {}

  public static Result analyze(TestGoal goal, ModelBinding binding) {
    if (!supported(goal.expression())) return ConstraintDefinednessReachability.analyze(goal, binding);
    ConstraintExpression footprint = goal.expression();
    if (binding.viewScope() != null) {
      if (!binding.viewScope().filters().stream().allMatch(ConstraintGoalReachability::supported))
        return unknown("View filter has no complete independent scalar partition.");
      footprint = binding.viewScope().footprint(footprint);
    }
    Set<BigDecimal> pivots = new LinkedHashSet<>();
    collectPivots(footprint, pivots);
    List<Domain> domains = new ArrayList<>();
    long states = 1;
    for (Reference reference : footprint.references().stream()
        .sorted(java.util.Comparator.comparing(Reference::name)).toList()) {
      ReferenceBinding bound = binding.references().get(reference.name());
      if (bound == null || !bound.reference().type().equals(reference.type())) {
        return unknown("Missing or inconsistent model domain for " + reference.name() + ".");
      }
      List<Object> values = representatives(bound, pivots);
      if (values == null || values.isEmpty()) return ConstraintDefinednessReachability.analyze(goal, binding);
      if (states > MAX_STATES / values.size()) return unknown("Predicate partition exceeds 50000 states.");
      states *= values.size();
      domains.add(new Domain(reference.name(), values));
    }
    if (satisfiable(goal, domains, 0, new LinkedHashMap<>(), binding.viewScope())) {
      return unknown("A scalar predicate state satisfies the goal; model-valid materialization is not proven.");
    }
    String domainSummary = domains.stream()
        .map(domain -> domain.reference() + "=" + domain.values())
        .collect(java.util.stream.Collectors.joining("; "));
    return new Result(Status.PROVEN_UNREACHABLE,
        "Exhausted " + states + " truth-preserving scalar partition states without a satisfying state. "
            + "The partitions represent every model-valid comparison outcome, including undefined optional paths. "
            + domainSummary);
  }

  private static Result unknown(String reason) { return new Result(Status.UNKNOWN, reason); }

  private static boolean supported(ConstraintExpression expression) {
    return switch (expression) {
      case ObjectCount ignored -> true;
      case Attribute attribute -> scalar(attribute.type());
      case Path path -> scalar(path.type());
      case NumericLiteral literal -> Double.isFinite(literal.value().doubleValue());
      case BooleanLiteral ignored -> true;
      case EnumLiteral ignored -> true;
      case Defined defined -> supported(defined.operand());
      case Not not -> supported(not.operand());
      case And and -> and.operands().stream().allMatch(ConstraintGoalReachability::supported);
      case Or or -> or.operands().stream().allMatch(ConstraintGoalReachability::supported);
      case Implies implies -> supported(implies.antecedent()) && supported(implies.consequent());
      case Comparison comparison -> {
        ConstraintExpression ref = comparison.left();
        ConstraintExpression literal = comparison.right();
        if (isLiteral(ref)) { ref = comparison.right(); literal = comparison.left(); }
        boolean shape = (ref instanceof Attribute || ref instanceof Path || ref instanceof ObjectCount) && isLiteral(literal);
        boolean numeric = literal instanceof NumericLiteral && ref.type().isScalar(ScalarKind.NUMERIC);
        boolean discrete = (literal instanceof EnumLiteral && ref.type().isScalar(ScalarKind.ENUM)
            || literal instanceof BooleanLiteral && ref.type().isScalar(ScalarKind.BOOLEAN))
            && (comparison.operator() == ComparisonOperator.EQ || comparison.operator() == ComparisonOperator.NE);
        yield shape && (numeric || discrete) && supported(ref) && supported(literal);
      }
      default -> false; // No functions, collections, text, arithmetic or reference-to-reference comparisons.
    };
  }

  private static boolean scalar(Type type) {
    return type.isScalar(ScalarKind.NUMERIC) || type.isScalar(ScalarKind.ENUM) || type.isScalar(ScalarKind.BOOLEAN);
  }

  private static boolean isLiteral(ConstraintExpression expression) {
    return expression instanceof NumericLiteral || expression instanceof BooleanLiteral || expression instanceof EnumLiteral;
  }

  private static void collectPivots(ConstraintExpression expression, Set<BigDecimal> result) {
    switch (expression) {
      case NumericLiteral literal -> result.add(literal.value());
      case Comparison comparison -> { collectPivots(comparison.left(), result); collectPivots(comparison.right(), result); }
      case Defined defined -> collectPivots(defined.operand(), result);
      case Not not -> collectPivots(not.operand(), result);
      case And and -> and.operands().forEach(operand -> collectPivots(operand, result));
      case Or or -> or.operands().forEach(operand -> collectPivots(operand, result));
      case Implies implies -> { collectPivots(implies.antecedent(), result); collectPivots(implies.consequent(), result); }
      default -> { }
    }
  }

  private static List<Object> representatives(ReferenceBinding bound, Set<BigDecimal> pivots) {
    if ((bound.reference().kind() != ReferenceKind.OBJECT_COUNT && bound.navigation().stream().anyMatch(ConstraintModelSynthesizer.NavigationBinding::multiValued))) return null;
    Set<Object> values = new LinkedHashSet<>();
    switch (bound.domain().kind()) {
      case BOOLEAN -> { values.add(false); values.add(true); }
      case ENUM -> {
        if (bound.domain().values().isEmpty()) return null;
        values.addAll(bound.domain().values());
      }
      case NUMERIC -> {
        var domain = bound.domain().numeric();
        if (domain == null || domain.minimum() == null
            || domain.maximum() == null && bound.reference().kind() != ReferenceKind.OBJECT_COUNT
            || !Double.isFinite(domain.minimum().doubleValue())
            || domain.maximum() != null && !Double.isFinite(domain.maximum().doubleValue())) return null;
        // NumericDomain.contains uses the declared precision (scale), not a pivot sampling cap.
        int scale = domain.step().scale();
        BigDecimal quantum = BigDecimal.ONE.scaleByPowerOfTen(-scale);
        Set<BigDecimal> cuts = new LinkedHashSet<>(pivots);
        cuts.add(domain.minimum());
        // For an unbounded count, the point above the greatest predicate pivot represents
        // the entire final interval. This is a truth partition, not a fixture/search limit.
        if(domain.maximum()!=null)cuts.add(domain.maximum());
        for (BigDecimal cut : cuts) {
          BigDecimal floor = cut.setScale(scale, RoundingMode.FLOOR);
          BigDecimal ceil = cut.setScale(scale, RoundingMode.CEILING);
          for (BigDecimal value : List.of(floor.subtract(quantum), floor, ceil, ceil.add(quantum))) {
            if (domain.contains(value)) values.add(value);
          }
        }
      }
      default -> { return null; }
    }
    if (!bound.domain().mandatory() || (bound.reference().kind() != ReferenceKind.OBJECT_COUNT && bound.navigation().stream().anyMatch(step -> step.minimum() == 0))) {
      values.add(Undefined.INSTANCE);
    }
    return List.copyOf(values);
  }

  private static boolean satisfiable(TestGoal goal, List<Domain> domains, int index, Map<String, Object> assignment, ViewProofScope scope) {
    if (index == domains.size()) return (scope == null || scope.includes(assignment)) && ConstraintGoalSolver.goalSatisfied(goal, assignment);
    Domain domain = domains.get(index);
    for (Object value : domain.values()) {
      assignment.put(domain.reference(), value);
      if (satisfiable(goal, domains, index + 1, assignment, scope)) return true;
    }
    assignment.remove(domain.reference());
    return false;
  }
}
