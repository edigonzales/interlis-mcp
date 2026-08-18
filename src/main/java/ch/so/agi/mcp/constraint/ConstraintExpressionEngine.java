package ch.so.agi.mcp.constraint;

import ch.so.agi.mcp.constraint.ConstraintExpression.And;
import ch.so.agi.mcp.constraint.ConstraintExpression.Attribute;
import ch.so.agi.mcp.constraint.ConstraintExpression.BooleanLiteral;
import ch.so.agi.mcp.constraint.ConstraintExpression.Comparison;
import ch.so.agi.mcp.constraint.ConstraintExpression.Defined;
import ch.so.agi.mcp.constraint.ConstraintExpression.EnumLiteral;
import ch.so.agi.mcp.constraint.ConstraintExpression.FunctionCall;
import ch.so.agi.mcp.constraint.ConstraintExpression.Implies;
import ch.so.agi.mcp.constraint.ConstraintExpression.Not;
import ch.so.agi.mcp.constraint.ConstraintExpression.NumericLiteral;
import ch.so.agi.mcp.constraint.ConstraintExpression.Or;
import ch.so.agi.mcp.constraint.ConstraintExpression.Path;
import ch.so.agi.mcp.constraint.ConstraintExpression.TextLiteral;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Version-neutral evaluator and structural test-goal planner for {@link ConstraintExpression}.
 *
 * <p>The engine operates on semantic IDs, never on INTERLIS surface syntax. It therefore evaluates
 * {@code Math.add(a,b)} and the INTERLIS 2.4 {@code a + b} representation identically once both have
 * been translated to the same IR.</p>
 */
public final class ConstraintExpressionEngine {

  /** Explicit value used when an INTERLIS expression is not defined. */
  public enum Undefined {
    INSTANCE
  }

  /** Desired semantic state for a generated structural test goal. */
  public enum GoalKind {
    TRUE,
    FALSE,
    DEFINED,
    UNDEFINED
  }

  public record EvaluationContext(Map<String, Object> values) {
    public EvaluationContext {
      values = values == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(values));
    }

    public static EvaluationContext of(Map<String, Object> values) {
      return new EvaluationContext(values);
    }

    Object value(String name) {
      return values.containsKey(name) ? normalize(values.get(name)) : Undefined.INSTANCE;
    }
  }

  public record TestGoal(GoalKind kind, ConstraintExpression expression, String reason) {
    public TestGoal {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(expression, "expression");
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason must not be blank.");
      }
    }
  }

  public static final class UnsupportedFunctionSemanticsException extends IllegalArgumentException {
    private final String semanticId;

    UnsupportedFunctionSemanticsException(String semanticId) {
      super("Unsupported function semantics: " + semanticId);
      this.semanticId = semanticId;
    }

    public String semanticId() {
      return semanticId;
    }
  }

  private ConstraintExpressionEngine() {
  }

  /** Evaluates a constraint. Undefined final truth is treated as not satisfied. */
  public static boolean evaluateConstraint(
      ConstraintExpression expression,
      EvaluationContext context) {
    return Boolean.TRUE.equals(evaluate(expression, context));
  }

  /** Evaluates any IR expression and returns a scalar, collection, or {@link Undefined#INSTANCE}. */
  public static Object evaluate(
      ConstraintExpression expression,
      EvaluationContext context) {
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(context, "context");
    return switch (expression) {
      case NumericLiteral literal -> literal.value();
      case BooleanLiteral literal -> literal.value();
      case EnumLiteral literal -> literal.value();
      case TextLiteral literal -> literal.value();
      case Attribute attribute -> context.value(attribute.name());
      case Path path -> context.value(path.path());
      case FunctionCall call -> evaluateFunction(call, context);
      case Defined defined -> evaluate(defined.operand(), context) != Undefined.INSTANCE;
      case Not not -> not(evaluate(not.operand(), context));
      case And and -> and(and.operands(), context);
      case Or or -> or(or.operands(), context);
      case Implies implies -> orValues(
          not(evaluate(implies.antecedent(), context)),
          evaluate(implies.consequent(), context));
      case Comparison comparison -> compare(comparison, context);
    };
  }

  /**
   * Derives semantic coverage obligations independently of domains and XTF fixture construction.
   * Later synthesis stages can solve these goals against model domains and cardinalities.
   */
  public static List<TestGoal> testGoals(ConstraintExpression expression) {
    Objects.requireNonNull(expression, "expression");
    LinkedHashSet<TestGoal> goals = new LinkedHashSet<>();
    goals.add(new TestGoal(GoalKind.TRUE, expression, "constraint witness"));
    goals.add(new TestGoal(GoalKind.FALSE, expression, "constraint counterexample"));
    collectGoals(expression, goals);
    return List.copyOf(goals);
  }

  private static void collectGoals(ConstraintExpression expression, Set<TestGoal> goals) {
    switch (expression) {
      case Defined defined -> {
        goals.add(new TestGoal(GoalKind.DEFINED, defined.operand(), "DEFINED branch"));
        goals.add(new TestGoal(GoalKind.UNDEFINED, defined.operand(), "NOT DEFINED branch"));
        collectGoals(defined.operand(), goals);
      }
      case Comparison comparison -> {
        goals.add(new TestGoal(GoalKind.TRUE, comparison, "comparison satisfied"));
        goals.add(new TestGoal(GoalKind.FALSE, comparison, "comparison violated"));
        collectGoals(comparison.left(), goals);
        collectGoals(comparison.right(), goals);
      }
      case Not not -> {
        goals.add(new TestGoal(GoalKind.TRUE, not.operand(), "NOT operand true branch"));
        goals.add(new TestGoal(GoalKind.FALSE, not.operand(), "NOT operand false branch"));
        collectGoals(not.operand(), goals);
      }
      case And and -> {
        for (ConstraintExpression operand : and.operands()) {
          goals.add(new TestGoal(GoalKind.TRUE, operand, "AND operand satisfied"));
          goals.add(new TestGoal(GoalKind.FALSE, operand, "AND operand violated"));
          collectGoals(operand, goals);
        }
      }
      case Or or -> {
        for (ConstraintExpression operand : or.operands()) {
          goals.add(new TestGoal(GoalKind.TRUE, operand, "OR branch selected"));
          goals.add(new TestGoal(GoalKind.FALSE, operand, "OR branch rejected"));
          collectGoals(operand, goals);
        }
      }
      case Implies implies -> {
        goals.add(new TestGoal(GoalKind.TRUE, implies.antecedent(), "IMPLIES antecedent true"));
        goals.add(new TestGoal(GoalKind.FALSE, implies.antecedent(), "IMPLIES antecedent false"));
        goals.add(new TestGoal(GoalKind.TRUE, implies.consequent(), "IMPLIES consequent true"));
        goals.add(new TestGoal(GoalKind.FALSE, implies.consequent(), "IMPLIES consequent false"));
        collectGoals(implies.antecedent(), goals);
        collectGoals(implies.consequent(), goals);
      }
      case FunctionCall call -> call.arguments().forEach(argument -> collectGoals(argument, goals));
      case NumericLiteral ignored -> {
      }
      case BooleanLiteral ignored -> {
      }
      case EnumLiteral ignored -> {
      }
      case TextLiteral ignored -> {
      }
      case Attribute ignored -> {
      }
      case Path ignored -> {
      }
    }
  }

  private static Object evaluateFunction(FunctionCall call, EvaluationContext context) {
    List<Object> arguments = call.arguments().stream()
        .map(argument -> evaluate(argument, context))
        .toList();
    return switch (call.semanticId()) {
      case "NUMERIC_ADD" -> numericBinary(arguments, BigDecimal::add);
      case "NUMERIC_SUB" -> numericBinary(arguments, BigDecimal::subtract);
      case "NUMERIC_MUL" -> numericBinary(arguments, BigDecimal::multiply);
      case "NUMERIC_DIV" -> numericDivide(arguments);
      case "NUMERIC_ABS" -> numericUnary(arguments, BigDecimal::abs);
      case "NUMERIC_ACOS" -> numericDoubleUnary(
          arguments, value -> java.lang.Math.acos(java.lang.Math.toRadians(value)));
      case "NUMERIC_ASIN" -> numericDoubleUnary(
          arguments, value -> java.lang.Math.asin(java.lang.Math.toRadians(value)));
      case "NUMERIC_ATAN" -> numericDoubleUnary(
          arguments, value -> java.lang.Math.atan(java.lang.Math.toRadians(value)));
      case "NUMERIC_ATAN2" -> numericDoubleBinary(
          arguments,
          (ordinate, abscissa) -> java.lang.Math.atan2(
              java.lang.Math.toDegrees(ordinate),
              java.lang.Math.toDegrees(abscissa)));
      case "NUMERIC_CBRT" -> numericCbrt(arguments);
      case "NUMERIC_COS" -> numericDoubleUnary(
          arguments, value -> java.lang.Math.cos(java.lang.Math.toRadians(value)));
      case "NUMERIC_COSH" -> numericDoubleUnary(
          arguments, value -> java.lang.Math.cosh(java.lang.Math.toRadians(value)));
      case "NUMERIC_EXP" -> numericDoubleUnary(arguments, java.lang.Math::exp);
      case "NUMERIC_HYPOT" -> numericDoubleBinary(arguments, java.lang.Math::hypot);
      case "NUMERIC_LOG" -> numericDoubleUnary(arguments, java.lang.Math::log);
      case "NUMERIC_LOG10" -> numericDoubleUnary(arguments, java.lang.Math::log10);
      case "NUMERIC_POW" -> numericDoubleBinary(arguments, java.lang.Math::pow);
      case "NUMERIC_ROUND" -> numericUnary(
          arguments, value -> BigDecimal.valueOf(java.lang.Math.round(value.doubleValue())));
      case "NUMERIC_SIGNUM" -> numericUnary(arguments, value -> BigDecimal.valueOf(value.signum()));
      case "NUMERIC_SIN" -> numericDoubleUnary(
          arguments, value -> java.lang.Math.sin(java.lang.Math.toRadians(value)));
      case "NUMERIC_SINH" -> numericDoubleUnary(
          arguments, value -> java.lang.Math.sinh(java.lang.Math.toRadians(value)));
      case "NUMERIC_SQRT" -> numericDoubleUnary(arguments, java.lang.Math::sqrt);
      case "NUMERIC_TAN" -> numericDoubleUnary(
          arguments, value -> java.lang.Math.tan(java.lang.Math.toRadians(value)));
      case "NUMERIC_TANH" -> numericDoubleUnary(
          arguments, value -> java.lang.Math.tanh(java.lang.Math.toRadians(value)));
      case "NUMERIC_MIN" -> numericBinary(arguments, BigDecimal::min);
      case "NUMERIC_MAX" -> numericBinary(arguments, BigDecimal::max);
      case "COLLECTION_SUM" -> collectionAggregate(arguments, Aggregate.SUM);
      case "COLLECTION_AVG" -> collectionAggregate(arguments, Aggregate.AVG);
      case "COLLECTION_MIN" -> collectionAggregate(arguments, Aggregate.MIN);
      case "COLLECTION_MAX" -> collectionAggregate(arguments, Aggregate.MAX);
      case "TEXT_CONCAT", "MTEXT_CONCAT" -> textBinary(arguments, (left, right) -> left + right);
      case "TEXT_STARTS_WITH", "MTEXT_STARTS_WITH" -> textPredicate(arguments, String::startsWith);
      case "TEXT_ENDS_WITH", "MTEXT_ENDS_WITH" -> textPredicate(arguments, String::endsWith);
      case "TEXT_EQUALS_IGNORE_CASE", "MTEXT_EQUALS_IGNORE_CASE" ->
          textPredicate(arguments, String::equalsIgnoreCase);
      case "TEXT_TO_LOWER_CASE", "MTEXT_TO_LOWER_CASE" -> textUnary(arguments, String::toLowerCase);
      case "TEXT_TO_UPPER_CASE", "MTEXT_TO_UPPER_CASE" -> textUnary(arguments, String::toUpperCase);
      case "TEXT_REPLACE", "MTEXT_REPLACE" -> textReplace(arguments);
      case "TEXT_MATCHES", "MTEXT_MATCHES" -> textMatches(arguments);
      case "TEXT_COMPARE_IGNORE_CASE", "MTEXT_COMPARE_IGNORE_CASE" -> textCompareIgnoreCase(arguments);
      case "TEXT_INDEX_OF", "MTEXT_INDEX_OF" -> textIndexOf(arguments, false);
      case "TEXT_LAST_INDEX_OF", "MTEXT_LAST_INDEX_OF" -> textIndexOf(arguments, true);
      case "TEXT_SUBSTRING", "MTEXT_SUBSTRING" -> textSubstring(arguments);
      default -> throw new UnsupportedFunctionSemanticsException(call.semanticId());
    };
  }

  private enum Aggregate {
    SUM,
    AVG,
    MIN,
    MAX
  }

  @FunctionalInterface
  private interface NumericBinary {
    BigDecimal apply(BigDecimal left, BigDecimal right);
  }

  @FunctionalInterface
  private interface NumericUnary {
    BigDecimal apply(BigDecimal value);
  }

  @FunctionalInterface
  private interface NumericDoubleBinary {
    double apply(double left, double right);
  }

  @FunctionalInterface
  private interface NumericDoubleUnary {
    double apply(double value);
  }

  @FunctionalInterface
  private interface TextBinary {
    String apply(String left, String right);
  }

  @FunctionalInterface
  private interface TextPredicate {
    boolean test(String left, String right);
  }

  @FunctionalInterface
  private interface TextUnary {
    String apply(String value);
  }

  private static Object numericBinary(List<Object> arguments, NumericBinary operation) {
    BigDecimal left = numeric(arguments.get(0));
    BigDecimal right = numeric(arguments.get(1));
    return left == null || right == null ? Undefined.INSTANCE : operation.apply(left, right);
  }

  private static Object numericUnary(List<Object> arguments, NumericUnary operation) {
    BigDecimal value = numeric(arguments.get(0));
    return value == null ? Undefined.INSTANCE : operation.apply(value);
  }

  private static Object numericDoubleBinary(List<Object> arguments, NumericDoubleBinary operation) {
    BigDecimal left = numeric(arguments.get(0));
    BigDecimal right = numeric(arguments.get(1));
    if (left == null || right == null) {
      return Undefined.INSTANCE;
    }
    return finiteDecimal(operation.apply(left.doubleValue(), right.doubleValue()));
  }

  private static Object numericDoubleUnary(List<Object> arguments, NumericDoubleUnary operation) {
    BigDecimal value = numeric(arguments.get(0));
    if (value == null) {
      return Undefined.INSTANCE;
    }
    return finiteDecimal(operation.apply(value.doubleValue()));
  }

  private static Object numericCbrt(List<Object> arguments) {
    BigDecimal value = numeric(arguments.get(0));
    if (value == null) {
      return Undefined.INSTANCE;
    }
    double result = java.lang.Math.cbrt(value.doubleValue());
    if (!Double.isFinite(result)) {
      return Undefined.INSTANCE;
    }
    // iox-ili's current Math function implementation returns cbrt.intValue().
    return BigDecimal.valueOf((int) result);
  }

  private static Object finiteDecimal(double value) {
    return Double.isFinite(value) ? BigDecimal.valueOf(value) : Undefined.INSTANCE;
  }

  private static Object numericDivide(List<Object> arguments) {
    BigDecimal left = numeric(arguments.get(0));
    BigDecimal right = numeric(arguments.get(1));
    if (left == null || right == null || right.compareTo(BigDecimal.ZERO) == 0) {
      return Undefined.INSTANCE;
    }
    return left.divide(right, MathContext.DECIMAL128);
  }

  private static Object collectionAggregate(List<Object> arguments, Aggregate aggregate) {
    Object raw = arguments.get(0);
    if (raw == Undefined.INSTANCE || !(raw instanceof Collection<?> collection) || collection.isEmpty()) {
      return Undefined.INSTANCE;
    }
    List<BigDecimal> numbers = new ArrayList<>();
    for (Object value : collection) {
      BigDecimal number = numeric(normalize(value));
      if (number == null) {
        return Undefined.INSTANCE;
      }
      numbers.add(number);
    }
    return switch (aggregate) {
      case SUM -> numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      case AVG -> numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
          .divide(BigDecimal.valueOf(numbers.size()), MathContext.DECIMAL128);
      case MIN -> numbers.stream().min(BigDecimal::compareTo).orElseThrow();
      case MAX -> numbers.stream().max(BigDecimal::compareTo).orElseThrow();
    };
  }

  private static Object textBinary(List<Object> arguments, TextBinary operation) {
    String left = text(arguments.get(0));
    String right = text(arguments.get(1));
    return left == null || right == null ? Undefined.INSTANCE : operation.apply(left, right);
  }

  private static Object textPredicate(List<Object> arguments, TextPredicate predicate) {
    String left = text(arguments.get(0));
    String right = text(arguments.get(1));
    return left == null || right == null ? Undefined.INSTANCE : predicate.test(left, right);
  }

  private static Object textUnary(List<Object> arguments, TextUnary operation) {
    String value = text(arguments.get(0));
    return value == null ? Undefined.INSTANCE : operation.apply(value);
  }

  private static Object textReplace(List<Object> arguments) {
    String value = text(arguments.get(0));
    String oldValue = text(arguments.get(1));
    String newValue = text(arguments.get(2));
    return value == null || oldValue == null || newValue == null
        ? Undefined.INSTANCE
        : value.replace(oldValue, newValue);
  }

  private static Object textMatches(List<Object> arguments) {
    String value = text(arguments.get(0));
    String regex = text(arguments.get(1));
    if (value == null || regex == null) {
      return Undefined.INSTANCE;
    }
    try {
      return Pattern.compile(regex).matcher(value).matches();
    } catch (RuntimeException ex) {
      return Undefined.INSTANCE;
    }
  }

  private static Object textCompareIgnoreCase(List<Object> arguments) {
    String left = text(arguments.get(0));
    String right = text(arguments.get(1));
    return left == null || right == null
        ? Undefined.INSTANCE
        : BigDecimal.valueOf(Integer.signum(left.compareToIgnoreCase(right)));
  }

  private static Object textIndexOf(List<Object> arguments, boolean last) {
    String value = text(arguments.get(0));
    String needle = text(arguments.get(1));
    BigDecimal from = numeric(arguments.get(2));
    if (value == null || needle == null || from == null) {
      return Undefined.INSTANCE;
    }
    int index = exactInt(from);
    if (index < 0) {
      return Undefined.INSTANCE;
    }
    int result = last ? value.lastIndexOf(needle, index) : value.indexOf(needle, index);
    return BigDecimal.valueOf(result);
  }

  private static Object textSubstring(List<Object> arguments) {
    String value = text(arguments.get(0));
    BigDecimal begin = numeric(arguments.get(1));
    BigDecimal end = numeric(arguments.get(2));
    if (value == null || begin == null || end == null) {
      return Undefined.INSTANCE;
    }
    int beginIndex = exactInt(begin);
    int endIndex = exactInt(end);
    if (beginIndex < 0 || endIndex < beginIndex || endIndex > value.length()) {
      return Undefined.INSTANCE;
    }
    return value.substring(beginIndex, endIndex);
  }

  private static int exactInt(BigDecimal value) {
    try {
      return value.intValueExact();
    } catch (ArithmeticException ex) {
      return -1;
    }
  }

  private static Object compare(Comparison comparison, EvaluationContext context) {
    Object left = evaluate(comparison.left(), context);
    Object right = evaluate(comparison.right(), context);
    if (left == Undefined.INSTANCE || right == Undefined.INSTANCE) {
      return false;
    }

    if (left instanceof Number && right instanceof Number) {
      int cmp = number(left).compareTo(number(right));
      return switch (comparison.operator()) {
        case EQ -> cmp == 0;
        case NE -> cmp != 0;
        case LT -> cmp < 0;
        case LE -> cmp <= 0;
        case GT -> cmp > 0;
        case GE -> cmp >= 0;
      };
    }

    boolean equal = Objects.equals(left, right);
    return switch (comparison.operator()) {
      case EQ -> equal;
      case NE -> !equal;
      default -> false;
    };
  }

  private static Object not(Object value) {
    return value == Undefined.INSTANCE ? Undefined.INSTANCE : !Boolean.TRUE.equals(value);
  }

  private static Object and(List<ConstraintExpression> operands, EvaluationContext context) {
    boolean undefined = false;
    for (ConstraintExpression operand : operands) {
      Object value = evaluate(operand, context);
      if (Boolean.FALSE.equals(value)) {
        return false;
      }
      if (value == Undefined.INSTANCE) {
        undefined = true;
      } else if (!Boolean.TRUE.equals(value)) {
        return false;
      }
    }
    return undefined ? Undefined.INSTANCE : true;
  }

  private static Object or(List<ConstraintExpression> operands, EvaluationContext context) {
    Object result = false;
    for (ConstraintExpression operand : operands) {
      result = orValues(result, evaluate(operand, context));
      if (Boolean.TRUE.equals(result)) {
        return true;
      }
    }
    return result;
  }

  private static Object orValues(Object left, Object right) {
    if (Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right)) {
      return true;
    }
    if (left == Undefined.INSTANCE || right == Undefined.INSTANCE) {
      return Undefined.INSTANCE;
    }
    return false;
  }

  private static BigDecimal number(Object value) {
    if (value instanceof BigDecimal decimal) {
      return decimal;
    }
    return new BigDecimal(String.valueOf(value));
  }

  private static BigDecimal numeric(Object value) {
    return value == Undefined.INSTANCE || !(value instanceof Number) ? null : number(value);
  }

  private static String text(Object value) {
    return value == Undefined.INSTANCE || !(value instanceof String text) ? null : text;
  }

  private static Object normalize(Object value) {
    if (value == null || value == Undefined.INSTANCE) {
      return Undefined.INSTANCE;
    }
    if (value instanceof Number) {
      return number(value);
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(ConstraintExpressionEngine::normalize).toList();
    }
    return value;
  }
}
