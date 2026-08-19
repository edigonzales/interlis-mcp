package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.ConstraintExpression;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine;
import ch.so.agi.mcp.constraint.ConstraintGoalSolver;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer;
import ch.so.agi.mcp.constraint.SemanticConstraint;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Deterministic validator-backed proof-case planner for scalar EXISTENCE constraints. */
final class ExistenceConstraintCasePlanner {

  private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

  record Plan(
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries,
      List<Map<String, Object>> unsolved) {

    Plan {
      cases = cases == null ? List.of() : List.copyOf(cases);
      summaries = summaries == null ? List.of() : List.copyOf(summaries);
      unsolved = unsolved == null ? List.of() : List.copyOf(unsolved);
      if (cases.size() != summaries.size()) {
        throw new IllegalArgumentException("EXISTENCE proof cases and summaries must have equal size.");
      }
    }

    int goalCount() {
      return cases.size() + unsolved.size();
    }

    boolean complete() {
      return unsolved.isEmpty();
    }
  }

  private record BoundPath(
      SemanticConstraint.ConstraintPath path,
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding,
      String referenceName) {
  }

  private record SharedValue(
      Object value,
      ConstraintModelSynthesizer.ObjectGraph sourceGraph,
      ConstraintModelSynthesizer.ObjectGraph targetGraph) {
  }

  private ExistenceConstraintCasePlanner() {
  }

  static Plan plan(
      CompiledConstraintContext context,
      SemanticConstraint.Existence existence) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(existence, "existence");

    List<ConstraintTestTools.TestCase> cases = new ArrayList<>();
    List<Map<String, Object>> summaries = new ArrayList<>();
    List<Map<String, Object>> unsolved = new ArrayList<>();

    BoundPath source;
    try {
      source = bind(context, existence.restrictedAttribute());
    } catch (IllegalArgumentException ex) {
      unsolved.add(unsolved("EXISTENCE_SOURCE_BINDING_UNAVAILABLE", ex.getMessage(), "bind restricted attribute"));
      return new Plan(cases, summaries, unsolved);
    }

    ConstraintGoalSolver.Solution sourceDefined = solveDefined(source, true);
    if (!sourceDefined.solved()) {
      unsolved.add(unsolved(
          nonBlank(sourceDefined.reasonCode(), "EXISTENCE_SOURCE_VALUE_UNSOLVED"),
          nonBlank(sourceDefined.reason(), "No model-valid defined source value could be synthesized."),
          "defined restricted attribute"));
      return new Plan(cases, summaries, unsolved);
    }

    Object sourceValue = sourceDefined.assignment().get(source.referenceName());
    ConstraintModelSynthesizer.ObjectGraph sourceOnly = synthesize(
        source.binding(), sourceDefined.assignment(), "existence_missing_source");
    addCase(
        cases,
        summaries,
        "defined value missing from all REQUIRED IN targets",
        false,
        "COUNTEREXAMPLE",
        "A defined restricted value with no equal target value violates EXISTENCE.",
        Map.of("sourceValue", summaryValue(sourceValue)),
        List.of(sourceOnly));

    int targetIndex = 1;
    SharedValue firstShared = null;
    BoundPath firstTarget = null;
    for (SemanticConstraint.ConstraintPath targetPath : existence.requiredIn()) {
      BoundPath target;
      try {
        target = bind(context, targetPath);
      } catch (IllegalArgumentException ex) {
        unsolved.add(unsolved(
            "EXISTENCE_TARGET_BINDING_UNAVAILABLE",
            ex.getMessage(),
            "bind REQUIRED IN target " + targetPath.rootFqn() + ":" + targetPath.path()));
        targetIndex++;
        continue;
      }

      SharedValue shared = sharedValue(source, target, sourceDefined, targetIndex);
      if (shared == null) {
        unsolved.add(unsolved(
            "EXISTENCE_NO_SHARED_SCALAR_VALUE",
            "The finite solver could not find a scalar value valid in both the restricted path and REQUIRED IN target.",
            targetPath.rootFqn() + ":" + targetPath.path()));
        targetIndex++;
        continue;
      }
      if (firstShared == null) {
        firstShared = shared;
        firstTarget = target;
      }

      String targetLabel = targetPath.rootFqn() + ":" + targetPath.path();
      addCase(
          cases,
          summaries,
          "value exists in REQUIRED IN target " + targetIndex,
          true,
          "WITNESS",
          "The restricted value occurs in REQUIRED IN target " + targetLabel + ".",
          Map.of(
              "sourceValue", summaryValue(shared.value()),
              "requiredIn", targetLabel),
          List.of(shared.sourceGraph(), shared.targetGraph()));
      targetIndex++;
    }

    if (firstShared != null && firstTarget != null) {
      addDifferentTargetCounterexample(
          cases, summaries, unsolved, source, firstTarget, firstShared);
    }

    ConstraintModelSynthesizer.ReferenceBinding sourceReference =
        source.binding().references().get(source.referenceName());
    if (sourceReference != null && !sourceReference.domain().mandatory()) {
      ConstraintGoalSolver.Solution undefined = solveDefined(source, false);
      if (undefined.solved()) {
        ConstraintModelSynthesizer.ObjectGraph undefinedGraph = synthesize(
            source.binding(), undefined.assignment(), "existence_undefined_source");
        addCase(
            cases,
            summaries,
            "undefined restricted value",
            true,
            "WITNESS",
            "An undefined optional restricted value does not require a matching target value.",
            Map.of("sourceValue", "UNDEFINED"),
            List.of(undefinedGraph));
      } else {
        unsolved.add(unsolved(
            nonBlank(undefined.reasonCode(), "EXISTENCE_UNDEFINED_SOURCE_UNSOLVED"),
            nonBlank(undefined.reason(), "The optional restricted path could not be synthesized as undefined."),
            "undefined restricted attribute"));
      }
    }

    return new Plan(cases, summaries, unsolved);
  }

  private static void addDifferentTargetCounterexample(
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries,
      List<Map<String, Object>> unsolved,
      BoundPath source,
      BoundPath target,
      SharedValue shared) {
    ConstraintExpression literal;
    try {
      literal = literal(shared.value(), target.path().endpointType().scalarKind());
    } catch (IllegalArgumentException ex) {
      unsolved.add(unsolved(
          "EXISTENCE_DIFFERENT_TARGET_VALUE_UNSUPPORTED",
          ex.getMessage(),
          "different target value"));
      return;
    }

    ConstraintExpression different = new ConstraintExpression.Comparison(
        ConstraintExpression.ComparisonOperator.NE,
        target.expression(),
        literal);
    ConstraintGoalSolver.Solution solution = ConstraintGoalSolver.solve(
        new ConstraintExpressionEngine.TestGoal(
            ConstraintExpressionEngine.GoalKind.TRUE,
            different,
            "REQUIRED IN target has a different value"),
        target.binding());
    if (!solution.solved()) {
      unsolved.add(unsolved(
          nonBlank(solution.reasonCode(), "EXISTENCE_DIFFERENT_TARGET_VALUE_UNSOLVED"),
          nonBlank(solution.reason(), "No alternative target value could be synthesized."),
          "different target value"));
      return;
    }

    ConstraintModelSynthesizer.ObjectGraph sourceGraph = synthesize(
        source.binding(),
        Map.of(source.referenceName(), shared.value()),
        "existence_wrong_target_source");
    ConstraintModelSynthesizer.ObjectGraph targetGraph = synthesize(
        target.binding(), solution.assignment(), "existence_wrong_target");
    addCase(
        cases,
        summaries,
        "REQUIRED IN target contains only a different value",
        false,
        "COUNTEREXAMPLE",
        "A target object is not sufficient unless its compared attribute equals the restricted value.",
        Map.of(
            "sourceValue", summaryValue(shared.value()),
            "targetValue", summaryValue(solution.assignment().get(target.referenceName()))),
        List.of(sourceGraph, targetGraph));
  }

  private static @Nullable SharedValue sharedValue(
      BoundPath source,
      BoundPath target,
      ConstraintGoalSolver.Solution sourceDefined,
      int targetIndex) {
    Object sourceValue = sourceDefined.assignment().get(source.referenceName());
    try {
      ConstraintModelSynthesizer.ObjectGraph sourceGraph = synthesize(
          source.binding(), sourceDefined.assignment(), "existence_source_" + targetIndex);
      ConstraintModelSynthesizer.ObjectGraph targetGraph = synthesize(
          target.binding(),
          Map.of(target.referenceName(), sourceValue),
          "existence_target_" + targetIndex);
      return new SharedValue(sourceValue, sourceGraph, targetGraph);
    } catch (IllegalArgumentException ex) {
      // Try a target-derived value as a second deterministic intersection candidate.
    }

    ConstraintGoalSolver.Solution targetDefined = solveDefined(target, true);
    if (!targetDefined.solved()) {
      return null;
    }
    Object targetValue = targetDefined.assignment().get(target.referenceName());
    try {
      ConstraintModelSynthesizer.ObjectGraph sourceGraph = synthesize(
          source.binding(),
          Map.of(source.referenceName(), targetValue),
          "existence_source_" + targetIndex);
      ConstraintModelSynthesizer.ObjectGraph targetGraph = synthesize(
          target.binding(), targetDefined.assignment(), "existence_target_" + targetIndex);
      return new SharedValue(targetValue, sourceGraph, targetGraph);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static BoundPath bind(
      CompiledConstraintContext context,
      SemanticConstraint.ConstraintPath path) {
    if (path.endpointType().collection()) {
      throw new IllegalArgumentException("B7 scalar EXISTENCE proof does not support collection-valued paths: " + path.path());
    }
    ConstraintExpression.ScalarKind kind = path.endpointType().scalarKind();
    if (kind != ConstraintExpression.ScalarKind.NUMERIC
        && kind != ConstraintExpression.ScalarKind.BOOLEAN
        && kind != ConstraintExpression.ScalarKind.ENUM
        && kind != ConstraintExpression.ScalarKind.TEXT
        && kind != ConstraintExpression.ScalarKind.MTEXT) {
      throw new IllegalArgumentException("B7 scalar EXISTENCE proof does not support endpoint type " + kind + ".");
    }
    ConstraintExpression expression = expression(path);
    ConstraintModelSynthesizer.ModelBinding binding = ConstraintModelSynthesizer.bind(
        context.transferDescription(), path.rootFqn(), new ConstraintExpression.Defined(expression));
    String referenceName = expression instanceof ConstraintExpression.Attribute attribute
        ? attribute.name()
        : ((ConstraintExpression.Path) expression).path();
    return new BoundPath(path, expression, binding, referenceName);
  }

  private static ConstraintExpression expression(SemanticConstraint.ConstraintPath path) {
    return SIMPLE_IDENTIFIER.matcher(path.path()).matches()
        ? new ConstraintExpression.Attribute(path.path(), path.endpointType())
        : new ConstraintExpression.Path(path.path(), path.endpointType());
  }

  private static ConstraintGoalSolver.Solution solveDefined(BoundPath path, boolean defined) {
    ConstraintExpression expression = defined
        ? new ConstraintExpression.Defined(path.expression())
        : new ConstraintExpression.Not(new ConstraintExpression.Defined(path.expression()));
    return ConstraintGoalSolver.solve(
        new ConstraintExpressionEngine.TestGoal(
            ConstraintExpressionEngine.GoalKind.TRUE,
            expression,
            defined ? "defined EXISTENCE value" : "undefined EXISTENCE value"),
        path.binding());
  }

  private static ConstraintModelSynthesizer.ObjectGraph synthesize(
      ConstraintModelSynthesizer.ModelBinding binding,
      Map<String, Object> assignment,
      String oidPrefix) {
    return ConstraintModelSynthesizer.synthesize(binding, assignment, oidPrefix);
  }

  private static void addCase(
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries,
      String name,
      boolean expected,
      String purpose,
      String reason,
      Object values,
      List<ConstraintModelSynthesizer.ObjectGraph> graphs) {
    ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
    testCase.name = name;
    testCase.expectedConstraintValid = expected;

    List<ConstraintTestTools.TestObject> objects = new ArrayList<>();
    List<ConstraintTestTools.TestLink> links = new ArrayList<>();
    for (ConstraintModelSynthesizer.ObjectGraph graph : graphs) {
      for (ConstraintModelSynthesizer.GraphObject graphObject : graph.objects()) {
        ConstraintTestTools.TestObject object = new ConstraintTestTools.TestObject();
        object.classFqn = graphObject.classFqn();
        object.oid = graphObject.oid();
        object.values = graphObject.values();
        object.references = graphObject.references();
        objects.add(object);
      }
      for (ConstraintModelSynthesizer.GraphLink graphLink : graph.links()) {
        ConstraintTestTools.TestLink link = new ConstraintTestTools.TestLink();
        link.associationFqn = graphLink.associationFqn();
        link.roles = graphLink.roles();
        links.add(link);
      }
    }
    testCase.objects = List.copyOf(objects);
    testCase.links = List.copyOf(links);
    cases.add(testCase);

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("purpose", purpose);
    summary.put("name", name);
    summary.put("reason", reason);
    summary.put("expectedConstraintValid", expected);
    summary.put("values", values);
    summary.put("objectCount", objects.size());
    summaries.add(Map.copyOf(summary));
  }

  private static ConstraintExpression literal(
      Object value,
      ConstraintExpression.ScalarKind kind) {
    if (value == null || value == ConstraintExpressionEngine.Undefined.INSTANCE) {
      throw new IllegalArgumentException("UNDEFINED cannot be rendered as a scalar comparison literal.");
    }
    return switch (kind) {
      case NUMERIC -> {
        if (!(value instanceof BigDecimal number)) {
          throw new IllegalArgumentException("Expected numeric EXISTENCE value, got " + value.getClass().getSimpleName() + ".");
        }
        yield new ConstraintExpression.NumericLiteral(number);
      }
      case BOOLEAN -> {
        if (!(value instanceof Boolean bool)) {
          throw new IllegalArgumentException("Expected boolean EXISTENCE value.");
        }
        yield new ConstraintExpression.BooleanLiteral(bool);
      }
      case ENUM -> new ConstraintExpression.EnumLiteral(String.valueOf(value));
      case TEXT -> new ConstraintExpression.TextLiteral(String.valueOf(value), ConstraintExpression.ScalarKind.TEXT);
      case MTEXT -> new ConstraintExpression.TextLiteral(String.valueOf(value), ConstraintExpression.ScalarKind.MTEXT);
      default -> throw new IllegalArgumentException("Unsupported scalar EXISTENCE literal type " + kind + ".");
    };
  }

  private static Map<String, Object> unsolved(String reasonCode, String reason, String goal) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("reasonCode", reasonCode);
    result.put("reason", reason == null ? "" : reason);
    result.put("goal", goal);
    return Map.copyOf(result);
  }

  private static String nonBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Object summaryValue(@Nullable Object value) {
    if (value == null || value == ConstraintExpressionEngine.Undefined.INSTANCE) {
      return "UNDEFINED";
    }
    if (value instanceof BigDecimal number) {
      return number.stripTrailingZeros().toPlainString();
    }
    return value;
  }
}
