package ch.so.agi.mcp.constraint;

import ch.so.agi.mcp.constraint.ConstraintExpression.*;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine.TestGoal;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine.Undefined;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine.NotComputable;
import static ch.so.agi.mcp.constraint.ConstraintExpressionEngine.isUndefined;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer.ModelBinding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conservative definedness-only abstraction; numeric function results are never computed.
 * Each comparison may independently be true or false when its inputs are defined. SUM may
 * independently be defined or undefined. Only the existing strict arithmetic null propagation
 * is assumed, so an impossible state here is also impossible for concrete numeric values.
 */
final class ConstraintDefinednessReachability {
  private enum Present { VALUE }
  private static final Set<String> STRICT = Set.of("NUMERIC_ADD", "NUMERIC_SUB", "NUMERIC_MUL", "NUMERIC_DIV");
  private final ModelBinding binding;
  private final Map<ConstraintExpression, List<Object>> variables = new LinkedHashMap<>();

  private ConstraintDefinednessReachability(ModelBinding binding) { this.binding = binding; }

  static ConstraintGoalReachability.Result analyze(TestGoal goal, ModelBinding binding) {
    var analysis = new ConstraintDefinednessReachability(binding);
    if (!analysis.collect(goal.expression())) return unknown("No supported definedness abstraction for this expression.");
    long count = 1;
    for (List<Object> values : analysis.variables.values()) {
      if (count > 50_000 / values.size()) return unknown("Definedness partition exceeds 50000 states.");
      count *= values.size();
    }
    if (analysis.satisfiable(goal, new ArrayList<>(analysis.variables.keySet()), 0, new LinkedHashMap<>())) {
      return unknown("A conservative definedness state satisfies the goal; numeric reachability is unknown.");
    }
    return new ConstraintGoalReachability.Result(ConstraintGoalReachability.Status.PROVEN_UNREACHABLE,
        "Exhausted " + count + " conservative definedness states. Comparisons are unconstrained when defined; "
            + "SUM may be defined or undefined. Only strict ADD/SUB/MUL/DIV undefined propagation and "
            + "ordered, short-circuit boolean rules were used; no numerical function results or collection cardinalities were inferred.");
  }

  private static ConstraintGoalReachability.Result unknown(String reason) {
    return new ConstraintGoalReachability.Result(ConstraintGoalReachability.Status.UNKNOWN, reason);
  }

  private boolean collect(ConstraintExpression expression) {
    return switch (expression) {
      case ObjectCount count -> collectReference(count, count.key());
      case Attribute attribute -> collectReference(attribute, attribute.name());
      case Path path -> collectReference(path, path.path());
      case NumericLiteral ignored -> true;
      case BooleanLiteral ignored -> true;
      case EnumLiteral ignored -> true;
      case Defined defined -> collect(defined.operand());
      case Not not -> collect(not.operand());
      case And and -> and.operands().stream().allMatch(this::collect);
      case Or or -> or.operands().stream().allMatch(this::collect);
      case Implies implies -> collect(implies.antecedent()) && collect(implies.consequent());
      case Comparison comparison -> {
        // Keep unsupported reference-to-reference and text comparisons outside this abstraction.
        boolean literal = comparison.left() instanceof NumericLiteral || comparison.left() instanceof BooleanLiteral
            || comparison.left() instanceof EnumLiteral || comparison.right() instanceof NumericLiteral
            || comparison.right() instanceof BooleanLiteral || comparison.right() instanceof EnumLiteral;
        if (!literal || !collect(comparison.left()) || !collect(comparison.right())) yield false;
        variables.putIfAbsent(comparison, List.of(false, true));
        yield true;
      }
      case FunctionCall call -> {
        if ("COLLECTION_SUM".equals(call.semanticId()) && call.arguments().size() == 1
            && call.arguments().getFirst() instanceof Path path
            && path.type().isCollectionOf(ScalarKind.NUMERIC)
            && binding.references().containsKey(path.path())) {
          variables.putIfAbsent(call, List.of(Present.VALUE, Undefined.INSTANCE));
          yield true;
        }
        yield STRICT.contains(call.semanticId()) && call.arguments().stream().allMatch(this::collect);
      }
      default -> false;
    };
  }

  private boolean collectReference(ConstraintExpression expression, String name) {
    var bound = binding.references().get(name);
    if (bound == null || expression.type().collection() || !bound.reference().type().equals(expression.type())
        || (bound.reference().kind() != ReferenceKind.OBJECT_COUNT && bound.navigation().stream().anyMatch(ConstraintModelSynthesizer.NavigationBinding::multiValued))) return false;
    List<Object> values = new ArrayList<>();
    if (expression.type().isScalar(ScalarKind.BOOLEAN)) values.addAll(List.of(false, true));
    else if (expression.type().isScalar(ScalarKind.NUMERIC) || expression.type().isScalar(ScalarKind.ENUM)) values.add(Present.VALUE);
    else return false;
    if (!bound.domain().mandatory() || (bound.reference().kind() != ReferenceKind.OBJECT_COUNT && bound.navigation().stream().anyMatch(step -> step.minimum() == 0))) values.add(Undefined.INSTANCE);
    variables.putIfAbsent(expression, List.copyOf(values));
    return true;
  }

  private boolean satisfiable(TestGoal goal, List<ConstraintExpression> keys, int index, Map<ConstraintExpression, Object> state) {
    if (index == keys.size()) {
      if (!goal.conditions().isEmpty()) return goal.conditions().stream().allMatch(condition ->
          ConstraintExpressionEngine.matchesState(condition.state(), evaluate(condition.expression(), state)));
      return ConstraintExpressionEngine.matchesState(goal.kind(), evaluate(goal.expression(), state));
    }
    var key = keys.get(index);
    for (Object value : variables.get(key)) {
      state.put(key, value);
      if (satisfiable(goal, keys, index + 1, state)) return true;
    }
    state.remove(key);
    return false;
  }

  private static Object evaluate(ConstraintExpression expression, Map<ConstraintExpression, Object> state) {
    return switch (expression) {
      case ObjectCount ignored -> state.get(expression);
      case Attribute ignored -> state.get(expression);
      case Path ignored -> state.get(expression);
      case NumericLiteral ignored -> Present.VALUE;
      case EnumLiteral ignored -> Present.VALUE;
      case BooleanLiteral literal -> literal.value();
      case Defined defined -> {
        Object value = evaluate(defined.operand(), state);
        yield value == NotComputable.INSTANCE ? value : value != Undefined.INSTANCE;
      }
      case Not not -> negate(evaluate(not.operand(), state));
      case And and -> ordered(and.operands(), state, false);
      case Or or -> ordered(or.operands(), state, true);
      case Implies implies -> {
        Object left = negate(evaluate(implies.antecedent(), state));
        yield Boolean.FALSE.equals(left) ? booleanValue(evaluate(implies.consequent(), state)) : left;
      }
      case Comparison comparison -> isUndefined(evaluate(comparison.left(), state))
          || isUndefined(evaluate(comparison.right(), state)) ? NotComputable.INSTANCE : state.get(comparison);
      case FunctionCall call -> "COLLECTION_SUM".equals(call.semanticId()) ? state.get(call)
          : call.arguments().stream().anyMatch(argument -> evaluate(argument, state) == NotComputable.INSTANCE)
              ? NotComputable.INSTANCE
              : call.arguments().stream().anyMatch(argument -> evaluate(argument, state) == Undefined.INSTANCE)
                  ? Undefined.INSTANCE : Present.VALUE;
      default -> throw new IllegalArgumentException("Unsupported definedness expression.");
    };
  }

  private static Object booleanValue(Object value) {
    return isUndefined(value) ? NotComputable.INSTANCE : value;
  }

  private static Object negate(Object value) {
    return isUndefined(value) ? NotComputable.INSTANCE : !Boolean.TRUE.equals(value);
  }

  private static Object ordered(List<ConstraintExpression> operands,
      Map<ConstraintExpression, Object> state, boolean or) {
    for (var operand : operands) {
      Object value = booleanValue(evaluate(operand, state));
      if (!Boolean.valueOf(!or).equals(value)) return value;
    }
    return !or;
  }
}
