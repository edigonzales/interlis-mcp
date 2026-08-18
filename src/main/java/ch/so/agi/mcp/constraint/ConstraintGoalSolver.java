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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Small deterministic finite-domain solver for semantic constraint test goals.
 *
 * <p>The solver derives interesting values from INTERLIS model domains and IR literals, searches a
 * bounded cartesian product and validates every candidate assignment through
 * {@link ConstraintModelSynthesizer}. Simple numeric equalities additionally drive candidate
 * derivation backwards through ADD/SUB/MUL/DIV and COLLECTION_SUM instead of relying on unrelated
 * literal/pivot guesses. It is deliberately not a complete SMT solver. Search exhaustion is
 * reported explicitly so a later solver strategy (for example Z3) can replace or complement it
 * without changing the IR or object-graph layers.</p>
 */
public final class ConstraintGoalSolver {

  private static final int MAX_ATTEMPTS = 50_000;
  private static final int MAX_CANDIDATES_PER_REFERENCE = 18;
  private static final int MAX_COLLECTION_SIZE = 3;
  private static final int MAX_DIRECTED_ANCHORS = 8;

  public record Solution(
      ConstraintExpressionEngine.TestGoal goal,
      boolean solved,
      Map<String, Object> assignment,
      int attempts,
      String reasonCode,
      String reason) {

    public Solution {
      Objects.requireNonNull(goal, "goal");
      assignment = assignment == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(assignment));
      reasonCode = reasonCode == null ? "" : reasonCode;
      reason = reason == null ? "" : reason;
    }
  }

  private record SearchReference(String name, List<Object> candidates) {
  }

  private static final class SearchState {
    private int attempts;
    private Map<String, Object> solution;
    private String unsupportedSemanticId;
  }

  private ConstraintGoalSolver() {
  }

  public static Solution solve(
      ConstraintExpressionEngine.TestGoal goal,
      ConstraintModelSynthesizer.ModelBinding binding) {
    Objects.requireNonNull(goal, "goal");
    Objects.requireNonNull(binding, "binding");

    List<SearchReference> references;
    try {
      references = searchReferences(goal.expression(), binding);
    } catch (IllegalArgumentException ex) {
      return new Solution(goal, false, Map.of(), 0,
          "UNSUPPORTED_SOLVER_DOMAIN", ex.getMessage());
    }

    SearchState state = new SearchState();
    search(goal, binding, references, 0, new LinkedHashMap<>(), state);
    if (state.solution != null) {
      return new Solution(goal, true, state.solution, state.attempts, "", "");
    }
    if (state.unsupportedSemanticId != null) {
      return new Solution(
          goal,
          false,
          Map.of(),
          state.attempts,
          "UNSUPPORTED_FUNCTION_SEMANTICS",
          "No executable solver semantics are available for " + state.unsupportedSemanticId + ".");
    }
    String code = state.attempts >= MAX_ATTEMPTS ? "SOLVER_SEARCH_LIMIT" : "NO_SOLUTION_FOUND";
    String reason = state.attempts >= MAX_ATTEMPTS
        ? "The finite-domain solver reached its search limit before finding a model-valid assignment."
        : "No model-valid assignment in the derived finite candidate set satisfies the semantic test goal.";
    return new Solution(goal, false, Map.of(), state.attempts, code, reason);
  }

  public static List<Solution> solveAll(
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding) {
    Objects.requireNonNull(expression, "expression");
    return ConstraintExpressionEngine.testGoals(expression).stream()
        .map(goal -> solve(goal, binding))
        .toList();
  }

  private static void search(
      ConstraintExpressionEngine.TestGoal goal,
      ConstraintModelSynthesizer.ModelBinding binding,
      List<SearchReference> references,
      int index,
      Map<String, Object> assignment,
      SearchState state) {
    if (state.solution != null || state.attempts >= MAX_ATTEMPTS) {
      return;
    }
    if (index < references.size()) {
      SearchReference reference = references.get(index);
      for (Object candidate : reference.candidates()) {
        assignment.put(reference.name(), candidate);
        search(goal, binding, references, index + 1, assignment, state);
        if (state.solution != null || state.attempts >= MAX_ATTEMPTS) {
          return;
        }
      }
      assignment.remove(reference.name());
      return;
    }

    state.attempts++;
    try {
      ConstraintModelSynthesizer.synthesize(binding, assignment, "solver_probe");
    } catch (IllegalArgumentException ex) {
      return;
    }

    try {
      if (goalSatisfied(goal, assignment)) {
        state.solution = new LinkedHashMap<>(assignment);
      }
    } catch (ConstraintExpressionEngine.UnsupportedFunctionSemanticsException ex) {
      state.unsupportedSemanticId = ex.semanticId();
    }
  }

  private static boolean goalSatisfied(
      ConstraintExpressionEngine.TestGoal goal,
      Map<String, Object> assignment) {
    Object value = ConstraintExpressionEngine.evaluate(
        goal.expression(),
        ConstraintExpressionEngine.EvaluationContext.of(assignment));
    return switch (goal.kind()) {
      case TRUE -> Boolean.TRUE.equals(value);
      case FALSE -> Boolean.FALSE.equals(value);
      case DEFINED -> value != ConstraintExpressionEngine.Undefined.INSTANCE;
      case UNDEFINED -> value == ConstraintExpressionEngine.Undefined.INSTANCE;
    };
  }

  private static List<SearchReference> searchReferences(
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding) {
    List<SearchReference> result = new ArrayList<>();
    for (Map.Entry<String, ConstraintModelSynthesizer.ReferenceBinding> entry
        : binding.references().entrySet()) {
      List<Object> candidates = candidates(entry.getValue(), expression, binding);
      if (candidates.isEmpty()) {
        throw new IllegalArgumentException(
            "No finite solver candidates can be derived for reference " + entry.getKey() + ".");
      }
      result.add(new SearchReference(entry.getKey(), candidates));
    }
    result.sort(Comparator.comparingInt(reference -> reference.candidates().size()));
    return result;
  }

  private static List<Object> candidates(
      ConstraintModelSynthesizer.ReferenceBinding reference,
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding) {
    if (reference.reference().type().collection()) {
      return collectionCandidates(reference, expression, binding);
    }
    return scalarCandidates(reference, expression, binding);
  }

  private static List<Object> scalarCandidates(
      ConstraintModelSynthesizer.ReferenceBinding reference,
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding) {
    CandidateSet candidates = new CandidateSet();
    ConstraintModelSynthesizer.ValueDomain domain = reference.domain();
    switch (domain.kind()) {
      case NUMERIC -> numericCandidates(candidates, domain.numeric(), expression, binding, reference);
      case BOOLEAN -> {
        candidates.add(false);
        candidates.add(true);
      }
      case ENUM -> domain.values().forEach(candidates::add);
      case TEXT, MTEXT -> {
        collectTextLiterals(expression).forEach(candidates::add);
        // Prefer a non-empty value for generic DEFINED witnesses: empty text is not materialized
        // as an attribute value by the XTF fixture writer and would therefore remain undefined.
        candidates.add("x");
        candidates.add("");
      }
      default -> throw new IllegalArgumentException(
          "Finite solver does not support scalar kind " + domain.kind() + ".");
    }
    if (!domain.mandatory() || optionalAssociation(reference)) {
      candidates.add(ConstraintExpressionEngine.Undefined.INSTANCE);
    }
    return candidates.limit(MAX_CANDIDATES_PER_REFERENCE);
  }

  private static void numericCandidates(
      CandidateSet candidates,
      ConstraintModelSynthesizer.NumericDomain domain,
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding,
      ConstraintModelSynthesizer.ReferenceBinding current) {
    for (BigDecimal directed : directedNumericTargets(expression, current, binding)) {
      addIfInDomain(candidates, domain, directed);
    }
    baseNumericCandidates(candidates, domain, expression);
  }

  private static void baseNumericCandidates(
      CandidateSet candidates,
      ConstraintModelSynthesizer.NumericDomain domain,
      ConstraintExpression expression) {
    Set<BigDecimal> literals = collectNumericLiterals(expression);
    for (BigDecimal literal : literals) {
      addIfInDomain(candidates, domain, literal);
      addIfInDomain(candidates, domain, literal.subtract(domain.step()));
      addIfInDomain(candidates, domain, literal.add(domain.step()));
    }
    numericDomainAnchors(domain).forEach(candidates::add);
  }

  private static List<Object> collectionCandidates(
      ConstraintModelSynthesizer.ReferenceBinding reference,
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding) {
    if (reference.association() == null) {
      throw new IllegalArgumentException(
          "Collection solver candidates require an association path: " + reference.reference().name());
    }
    CandidateSet result = new CandidateSet();
    ConstraintModelSynthesizer.AssociationBinding association = reference.association();
    int maximum = Math.min(MAX_COLLECTION_SIZE, association.effectiveMaximum(MAX_COLLECTION_SIZE));
    int minimum = Math.toIntExact(Math.min(association.minimum(), maximum));
    if (association.minimum() == 0) {
      result.add(List.of());
    }

    ConstraintModelSynthesizer.ValueDomain domain = reference.domain();
    if (domain.kind() == ConstraintExpression.ScalarKind.NUMERIC) {
      CandidateSet elementSet = new CandidateSet();
      baseNumericCandidates(elementSet, domain.numeric(), expression);
      List<Object> elements = elementSet.limit(10).stream()
          .filter(value -> value instanceof BigDecimal)
          .toList();
      int firstCount = Math.max(1, minimum);
      for (int count = firstCount; count <= maximum; count++) {
        for (Object element : elements) {
          result.add(repeated(element, count));
        }
      }

      LinkedHashSet<BigDecimal> totals = new LinkedHashSet<>(
          directedNumericTargets(expression, reference, binding));
      for (BigDecimal literal : collectNumericLiterals(expression)) {
        totals.add(literal);
        totals.add(literal.subtract(domain.numeric().step()));
        totals.add(literal.add(domain.numeric().step()));
      }
      for (BigDecimal total : totals) {
        for (int count = firstCount; count <= maximum; count++) {
          List<BigDecimal> distributed = distribute(total, domain.numeric(), count);
          if (!distributed.isEmpty()) {
            result.add(distributed);
          }
        }
      }
    } else if (domain.kind() == ConstraintExpression.ScalarKind.BOOLEAN) {
      int count = Math.max(1, minimum);
      if (count <= maximum) {
        result.add(repeated(false, count));
        result.add(repeated(true, count));
      }
    } else if (domain.kind() == ConstraintExpression.ScalarKind.ENUM) {
      int count = Math.max(1, minimum);
      if (count <= maximum) {
        for (String value : domain.values()) {
          result.add(repeated(value, count));
        }
      }
    } else {
      throw new IllegalArgumentException(
          "Finite collection solver does not support endpoint kind " + domain.kind() + ".");
    }
    return result.limit(MAX_CANDIDATES_PER_REFERENCE);
  }

  private static Set<BigDecimal> directedNumericTargets(
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ReferenceBinding current,
      ConstraintModelSynthesizer.ModelBinding binding) {
    LinkedHashSet<BigDecimal> result = new LinkedHashSet<>();
    collectDirectedTargets(expression, current, binding, result);
    return result;
  }

  private static void collectDirectedTargets(
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ReferenceBinding current,
      ConstraintModelSynthesizer.ModelBinding binding,
      Set<BigDecimal> result) {
    switch (expression) {
      case Comparison comparison -> {
        if (comparison.operator() == ConstraintExpression.ComparisonOperator.EQ) {
          if (comparison.right() instanceof NumericLiteral literal) {
            solveNumericExpression(comparison.left(), literal.value(), current, binding, result);
          } else if (comparison.left() instanceof NumericLiteral literal) {
            solveNumericExpression(comparison.right(), literal.value(), current, binding, result);
          }
        }
        collectDirectedTargets(comparison.left(), current, binding, result);
        collectDirectedTargets(comparison.right(), current, binding, result);
      }
      case FunctionCall call -> call.arguments().forEach(
          argument -> collectDirectedTargets(argument, current, binding, result));
      case Defined defined -> collectDirectedTargets(defined.operand(), current, binding, result);
      case Not not -> collectDirectedTargets(not.operand(), current, binding, result);
      case And and -> and.operands().forEach(
          operand -> collectDirectedTargets(operand, current, binding, result));
      case Or or -> or.operands().forEach(
          operand -> collectDirectedTargets(operand, current, binding, result));
      case Implies implies -> {
        collectDirectedTargets(implies.antecedent(), current, binding, result);
        collectDirectedTargets(implies.consequent(), current, binding, result);
      }
      default -> {
      }
    }
  }

  private static void solveNumericExpression(
      ConstraintExpression expression,
      BigDecimal target,
      ConstraintModelSynthesizer.ReferenceBinding current,
      ConstraintModelSynthesizer.ModelBinding binding,
      Set<BigDecimal> result) {
    if (sameReference(expression, current) && !current.reference().type().collection()) {
      result.add(target);
      return;
    }
    if (!(expression instanceof FunctionCall call)) {
      return;
    }
    if ("COLLECTION_SUM".equals(call.semanticId())
        && current.reference().type().collection()
        && call.arguments().size() == 1
        && sameReference(call.arguments().getFirst(), current)) {
      result.add(target);
      return;
    }
    if (call.arguments().size() != 2 || !isDirectedArithmetic(call.semanticId())) {
      return;
    }

    ConstraintExpression left = call.arguments().get(0);
    ConstraintExpression right = call.arguments().get(1);
    boolean leftContains = containsReference(left, current.reference().name());
    boolean rightContains = containsReference(right, current.reference().name());
    if (leftContains == rightContains) {
      return;
    }

    ConstraintExpression variable = leftContains ? left : right;
    ConstraintExpression other = leftContains ? right : left;
    for (BigDecimal otherValue : numericAnchors(other, binding)) {
      BigDecimal nextTarget = switch (call.semanticId()) {
        case "NUMERIC_ADD" -> target.subtract(otherValue);
        case "NUMERIC_SUB" -> leftContains
            ? target.add(otherValue)
            : otherValue.subtract(target);
        case "NUMERIC_MUL" -> otherValue.compareTo(BigDecimal.ZERO) == 0
            ? null
            : divide(target, otherValue);
        case "NUMERIC_DIV" -> {
          if (leftContains) {
            yield otherValue.compareTo(BigDecimal.ZERO) == 0
                ? null
                : target.multiply(otherValue);
          }
          if (target.compareTo(BigDecimal.ZERO) == 0) {
            yield null;
          }
          BigDecimal denominator = divide(otherValue, target);
          yield denominator != null && denominator.compareTo(BigDecimal.ZERO) != 0
              ? denominator
              : null;
        }
        default -> null;
      };
      if (nextTarget != null) {
        solveNumericExpression(variable, nextTarget, current, binding, result);
      }
    }
  }

  private static boolean isDirectedArithmetic(String semanticId) {
    return "NUMERIC_ADD".equals(semanticId)
        || "NUMERIC_SUB".equals(semanticId)
        || "NUMERIC_MUL".equals(semanticId)
        || "NUMERIC_DIV".equals(semanticId);
  }

  private static boolean containsReference(ConstraintExpression expression, String name) {
    return expression.references().stream().anyMatch(reference -> name.equals(reference.name()));
  }

  private static boolean sameReference(
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ReferenceBinding current) {
    if (expression instanceof Attribute attribute) {
      return !current.reference().type().collection()
          && attribute.name().equals(current.reference().name());
    }
    if (expression instanceof Path path) {
      return path.path().equals(current.reference().name())
          && path.type().collection() == current.reference().type().collection();
    }
    return false;
  }

  private static List<BigDecimal> numericAnchors(
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding) {
    if (expression instanceof NumericLiteral literal) {
      return List.of(literal.value());
    }
    String referenceName = directReferenceName(expression);
    if (referenceName != null && !expression.type().collection()) {
      ConstraintModelSynthesizer.ReferenceBinding reference = binding.references().get(referenceName);
      if (reference != null
          && reference.domain().kind() == ConstraintExpression.ScalarKind.NUMERIC) {
        return numericDomainAnchors(reference.domain().numeric());
      }
      return List.of();
    }
    if (!(expression instanceof FunctionCall call)) {
      return List.of();
    }
    if ("COLLECTION_SUM".equals(call.semanticId()) && call.arguments().size() == 1) {
      String pathName = directReferenceName(call.arguments().getFirst());
      ConstraintModelSynthesizer.ReferenceBinding reference = pathName == null
          ? null
          : binding.references().get(pathName);
      return reference == null ? List.of() : aggregateAnchors(reference);
    }
    if (call.arguments().size() != 2 || !isDirectedArithmetic(call.semanticId())) {
      return List.of();
    }

    LinkedHashSet<BigDecimal> result = new LinkedHashSet<>();
    List<BigDecimal> left = numericAnchors(call.arguments().get(0), binding);
    List<BigDecimal> right = numericAnchors(call.arguments().get(1), binding);
    for (BigDecimal leftValue : left) {
      for (BigDecimal rightValue : right) {
        BigDecimal value = switch (call.semanticId()) {
          case "NUMERIC_ADD" -> leftValue.add(rightValue);
          case "NUMERIC_SUB" -> leftValue.subtract(rightValue);
          case "NUMERIC_MUL" -> leftValue.multiply(rightValue);
          case "NUMERIC_DIV" -> rightValue.compareTo(BigDecimal.ZERO) == 0
              ? null
              : divide(leftValue, rightValue);
          default -> null;
        };
        if (value != null) {
          result.add(value);
          if (result.size() >= MAX_DIRECTED_ANCHORS) {
            return List.copyOf(result);
          }
        }
      }
    }
    return List.copyOf(result);
  }

  private static String directReferenceName(ConstraintExpression expression) {
    if (expression instanceof Attribute attribute) {
      return attribute.name();
    }
    if (expression instanceof Path path) {
      return path.path();
    }
    return null;
  }

  private static List<BigDecimal> numericDomainAnchors(
      ConstraintModelSynthesizer.NumericDomain domain) {
    LinkedHashSet<BigDecimal> result = new LinkedHashSet<>();
    if (domain.minimum() != null) {
      result.add(domain.minimum());
    }
    if (domain.maximum() != null) {
      result.add(domain.maximum());
    }
    BigDecimal zero = BigDecimal.ZERO.setScale(domain.step().scale());
    if (domain.contains(zero)) {
      result.add(zero);
    }
    BigDecimal one = BigDecimal.ONE.setScale(domain.step().scale());
    if (domain.contains(one)) {
      result.add(one);
    }
    if (domain.minimum() != null && domain.maximum() != null) {
      BigDecimal midpoint = domain.minimum().add(domain.maximum())
          .divide(BigDecimal.valueOf(2), MathContext.DECIMAL128);
      if (domain.contains(midpoint)) {
        result.add(midpoint);
      }
    }
    return List.copyOf(result);
  }

  private static List<BigDecimal> aggregateAnchors(
      ConstraintModelSynthesizer.ReferenceBinding reference) {
    if (!reference.reference().type().collection()
        || reference.association() == null
        || reference.domain().kind() != ConstraintExpression.ScalarKind.NUMERIC) {
      return List.of();
    }
    int maximum = Math.min(
        MAX_COLLECTION_SIZE,
        reference.association().effectiveMaximum(MAX_COLLECTION_SIZE));
    int minimum = Math.toIntExact(Math.min(reference.association().minimum(), maximum));
    int firstCount = Math.max(1, minimum);
    if (firstCount > maximum) {
      return List.of();
    }

    LinkedHashSet<BigDecimal> result = new LinkedHashSet<>();
    List<BigDecimal> elementAnchors = numericDomainAnchors(reference.domain().numeric());
    LinkedHashSet<Integer> counts = new LinkedHashSet<>();
    counts.add(firstCount);
    counts.add(maximum);
    for (int count : counts) {
      for (BigDecimal element : elementAnchors) {
        result.add(element.multiply(BigDecimal.valueOf(count)));
        if (result.size() >= MAX_DIRECTED_ANCHORS) {
          return List.copyOf(result);
        }
      }
    }
    return List.copyOf(result);
  }

  private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
    if (denominator.compareTo(BigDecimal.ZERO) == 0) {
      return null;
    }
    return numerator.divide(denominator, MathContext.DECIMAL128);
  }

  private static boolean optionalAssociation(ConstraintModelSynthesizer.ReferenceBinding reference) {
    return reference.association() != null && reference.association().minimum() == 0;
  }

  private static List<Object> repeated(Object value, int count) {
    List<Object> result = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      result.add(value);
    }
    return List.copyOf(result);
  }

  private static List<BigDecimal> distribute(
      BigDecimal total,
      ConstraintModelSynthesizer.NumericDomain domain,
      int count) {
    if (count <= 0) {
      return List.of();
    }
    BigDecimal base = domain.minimum() != null
        ? domain.minimum()
        : BigDecimal.ZERO.setScale(domain.step().scale());
    BigDecimal maximum = domain.maximum();
    if (maximum == null) {
      try {
        BigDecimal equal = total.divide(BigDecimal.valueOf(count));
        if (!domain.contains(equal)) {
          return List.of();
        }
        return repeatedDecimal(equal, count);
      } catch (ArithmeticException ex) {
        return List.of();
      }
    }

    BigDecimal remaining = total.subtract(base.multiply(BigDecimal.valueOf(count)));
    if (remaining.compareTo(BigDecimal.ZERO) < 0) {
      return List.of();
    }
    List<BigDecimal> result = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      BigDecimal capacity = maximum.subtract(base);
      BigDecimal addition = remaining.min(capacity);
      BigDecimal value = base.add(addition);
      if (!domain.contains(value)) {
        return List.of();
      }
      result.add(value);
      remaining = remaining.subtract(addition);
    }
    return remaining.compareTo(BigDecimal.ZERO) == 0 ? List.copyOf(result) : List.of();
  }

  private static List<BigDecimal> repeatedDecimal(BigDecimal value, int count) {
    List<BigDecimal> result = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      result.add(value);
    }
    return List.copyOf(result);
  }

  private static void addIfInDomain(
      CandidateSet candidates,
      ConstraintModelSynthesizer.NumericDomain domain,
      BigDecimal value) {
    if (domain.contains(value)) {
      candidates.add(value);
    }
  }

  private static Set<BigDecimal> collectNumericLiterals(ConstraintExpression expression) {
    LinkedHashSet<BigDecimal> result = new LinkedHashSet<>();
    collectLiterals(expression, result, new LinkedHashSet<>());
    return result;
  }

  private static Set<String> collectTextLiterals(ConstraintExpression expression) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    collectLiterals(expression, new LinkedHashSet<>(), result);
    return result;
  }

  private static void collectLiterals(
      ConstraintExpression expression,
      Set<BigDecimal> numeric,
      Set<String> text) {
    switch (expression) {
      case NumericLiteral literal -> numeric.add(literal.value());
      case TextLiteral literal -> text.add(literal.value());
      case FunctionCall call -> call.arguments().forEach(argument -> collectLiterals(argument, numeric, text));
      case Defined defined -> collectLiterals(defined.operand(), numeric, text);
      case Not not -> collectLiterals(not.operand(), numeric, text);
      case And and -> and.operands().forEach(child -> collectLiterals(child, numeric, text));
      case Or or -> or.operands().forEach(child -> collectLiterals(child, numeric, text));
      case Implies implies -> {
        collectLiterals(implies.antecedent(), numeric, text);
        collectLiterals(implies.consequent(), numeric, text);
      }
      case Comparison comparison -> {
        collectLiterals(comparison.left(), numeric, text);
        collectLiterals(comparison.right(), numeric, text);
      }
      case BooleanLiteral ignored -> {
      }
      case EnumLiteral ignored -> {
      }
      case Attribute ignored -> {
      }
      case Path ignored -> {
      }
    }
  }

  private static final class CandidateSet {
    private final Map<String, Object> values = new LinkedHashMap<>();

    void add(Object value) {
      if (value != null) {
        values.putIfAbsent(key(value), value);
      }
    }

    List<Object> limit(int maximum) {
      return values.values().stream().limit(maximum).toList();
    }

    private String key(Object value) {
      if (value == ConstraintExpressionEngine.Undefined.INSTANCE) {
        return "UNDEFINED";
      }
      if (value instanceof BigDecimal decimal) {
        return "N:" + decimal.stripTrailingZeros().toPlainString();
      }
      if (value instanceof Collection<?> collection) {
        return "C:[" + collection.stream().map(this::key).reduce((a, b) -> a + "," + b).orElse("") + "]";
      }
      return value.getClass().getName() + ":" + value;
    }
  }
}
