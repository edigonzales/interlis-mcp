package ch.so.agi.mcp.constraint;

import ch.so.agi.mcp.constraint.ConstraintExpression.*;
import org.jspecify.annotations.Nullable;

/** Boundaries of the pinned iox-ili 1.24.4 runtime, checked on unnormalised compiler output. */
public final class ConstraintValidatorCompatibility {
  public static final String NATIVE_IMPLICATION = "VALIDATOR_NATIVE_IMPLICATION_UNSUPPORTED";

  private ConstraintValidatorCompatibility() {}

  public static boolean hasNativeImplication(CompiledConstraintContext context) {
    // The AST translator preserves Expression.Implication as Implies. Neither the structural
    // comparator nor the renderer mutates this compiler-owned IR. Authored NOT/OR has no such node.
    return switch (context.semantics()) {
      case SemanticConstraint.Mandatory mandatory -> contains(mandatory.condition());
      case SemanticConstraint.Plausibility plausibility -> contains(plausibility.condition());
      case SemanticConstraint.Unique unique -> contains(unique.preCondition());
      case SemanticConstraint.Set set -> contains(set.preCondition())
          || set.condition() instanceof SemanticConstraint.ValueSetCondition value && contains(value.expression());
      case SemanticConstraint.Existence ignored -> false;
    };
  }

  private static boolean contains(@Nullable ConstraintExpression expression) {
    if (expression == null) return false;
    return switch (expression) {
      case Implies ignored -> true;
      case And and -> and.operands().stream().anyMatch(ConstraintValidatorCompatibility::contains);
      case Or or -> or.operands().stream().anyMatch(ConstraintValidatorCompatibility::contains);
      case Not not -> contains(not.operand());
      case Defined defined -> contains(defined.operand());
      case Comparison comparison -> contains(comparison.left()) || contains(comparison.right());
      case FunctionCall call -> call.arguments().stream().anyMatch(ConstraintValidatorCompatibility::contains);
      default -> false;
    };
  }
}
