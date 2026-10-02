package ch.so.agi.mcp.constraint;

import ch.so.agi.mcp.constraint.ConstraintExpression.*;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine.Undefined;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer.ReferenceBinding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Truth-preserving scalar representatives shared by independent reachability and interaction checks. */
final class ScalarTruthPartitions {
  private ScalarTruthPartitions() {}
  static void collectPivots(ConstraintExpression expression, Set<BigDecimal> result) {
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

  static List<Object> representatives(ReferenceBinding bound, Set<BigDecimal> pivots) {
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

}
