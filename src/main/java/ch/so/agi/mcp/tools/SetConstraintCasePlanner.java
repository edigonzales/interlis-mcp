package ch.so.agi.mcp.tools;

import static ch.so.agi.mcp.tools.ConstraintCaseGenerationTools.summaryAssignment;

import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.ConstraintExpression;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine;
import ch.so.agi.mcp.constraint.ConstraintCoveragePlanner;
import ch.so.agi.mcp.constraint.ConstraintGoalSolver;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer;
import ch.so.agi.mcp.constraint.ObjectPathRoutes;
import ch.so.agi.mcp.constraint.SemanticConstraint;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validator-backed proof planning for SET object counts and boolean expressions. */
final class SetConstraintCasePlanner {

  private static final int MAX_COUNT = 20;

  record Plan(
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries,
      List<Map<String, Object>> unsolved,
      int solvedGoalCount,
      List<Map<String, Object>> excluded) {

    Plan(List<ConstraintTestTools.TestCase> cases, List<Map<String, Object>> summaries,
        List<Map<String, Object>> unsolved) {
      this(cases, summaries, unsolved, cases.size(), List.of());
    }

    Plan {
      cases = cases == null ? List.of() : List.copyOf(cases);
      summaries = summaries == null ? List.of() : List.copyOf(summaries);
      unsolved = unsolved == null ? List.of() : List.copyOf(unsolved);
      excluded = List.copyOf(excluded);
      if (cases.size() != summaries.size()) {
        throw new IllegalArgumentException("SET proof cases and summaries must have equal size.");
      }
    }
  }

  private record WhereTemplates(
      ConstraintModelSynthesizer.ModelBinding binding,
      ConstraintGoalSolver.Solution included,
      ConstraintGoalSolver.Solution excluded) {
  }

  private record CountCandidate(int count, boolean expectedValid, String boundary) {
  }

  private SetConstraintCasePlanner() {
  }

  static Plan plan(
      CompiledConstraintContext context,
      SemanticConstraint.Set set) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(set, "set");

    List<ConstraintTestTools.TestCase> cases = new ArrayList<>();
    List<Map<String, Object>> summaries = new ArrayList<>();
    List<Map<String, Object>> unsolved = new ArrayList<>();

    if (set.condition() instanceof SemanticConstraint.ValueSetCondition valueCondition) {
      return planBooleanExpression(context, set, valueCondition.expression());
    }
    if (!(set.condition() instanceof SemanticConstraint.ObjectCountSetCondition objectCount)) {
      String reasonCode = set.condition() instanceof SemanticConstraint.UntranslatedSetCondition untranslated
          ? untranslated.reasonCode()
          : "SET_CONDITION_PROOF_UNSUPPORTED";
      unsolved.add(Map.of(
          "reasonCode", reasonCode,
          "reason", "Automatic SET proof requires a translated objectCount comparison or boolean expression.",
          "conditionKind", set.condition().getClass().getSimpleName()));
      return new Plan(cases, summaries, unsolved);
    }

    if (!(objectCount.objects() instanceof SemanticConstraint.AllObjects all)) {
      if (objectCount.objects() instanceof SemanticConstraint.NavigatedObjects navigated) {
        return planNavigatedObjectCount(context, set, objectCount, navigated);
      }
      unsolved.add(Map.of(
          "reasonCode", "SET_OBJECT_SET_EXPRESSION_UNSUPPORTED",
          "reason", "The compiled SET object-set expression is unsupported."));
      return new Plan(cases, summaries, unsolved);
    }
    if (!all.plain()) {
      unsolved.add(Map.of(
          "reasonCode", "SET_ALL_RESTRICTION_PROOF_UNSUPPORTED",
          "reason", "ALL(base/restriction) metadata is preserved in the semantic IR but automatic proof currently supports plain ALL only.",
          "baseFqn", all.baseFqn() == null ? "" : all.baseFqn(),
          "restrictedToFqns", all.restrictedToFqns()));
      return new Plan(cases, summaries, unsolved);
    }
    if (!set.contextFqn().equals(all.contextFqn())) {
      unsolved.add(Map.of(
          "reasonCode", "SET_ALL_CONTEXT_MISMATCH",
          "reason", "ALL semantic context differs from the SET constraint context.",
          "constraintContext", set.contextFqn(),
          "allContext", all.contextFqn()));
      return new Plan(cases, summaries, unsolved);
    }

    WhereTemplates where = null;
    if (set.preCondition() != null) {
      try {
        where = whereTemplates(context, set);
      } catch (IllegalArgumentException ex) {
        unsolved.add(Map.of(
            "reasonCode", "SET_WHERE_BINDING_UNAVAILABLE",
            "reason", ex.getMessage()));
        return new Plan(cases, summaries, unsolved);
      }
      if (!where.included().solved()) {
        unsolved.add(Map.of(
            "reasonCode", "SET_WHERE_TRUE_UNSOLVED",
            "reason", solverReason(where.included()),
            "goal", "WHERE_INCLUDED_MEMBER"));
      }
      if (!where.excluded().solved()) {
        unsolved.add(Map.of(
            "reasonCode", "SET_WHERE_FALSE_UNSOLVED",
            "reason", solverReason(where.excluded()),
            "goal", "WHERE_EXCLUDED_MEMBER"));
      }
      if (!where.included().solved() || !where.excluded().solved()) {
        return new Plan(cases, summaries, unsolved);
      }
      try {
        requireSimpleMemberGraph(set, where.binding(), where.included().assignment(), "WHERE true");
        requireSimpleMemberGraph(set, where.binding(), where.excluded().assignment(), "WHERE false");
      } catch (IllegalArgumentException ex) {
        unsolved.add(Map.of(
            "reasonCode", "SET_WHERE_GRAPH_UNSAFE",
            "reason", ex.getMessage()));
        return new Plan(cases, summaries, unsolved);
      }
    }

    int caseIndex = 1;
    for (CountCandidate candidate : branchCandidates(objectCount)) {
      if (candidate.count() == 0 && where == null && !(context.constraint().getContainer() instanceof ch.interlis.ili2c.metamodel.Projection)) {
        unsolved.add(Map.of(
            "reasonCode", "SET_ZERO_COUNT_FIXTURE_UNAVAILABLE",
            "reason", "A plain-ALL zero-count SET proof has no constraint-context object, so the explicit fixture harness cannot mark the constraint as exercised.",
            "goal", candidate.boundary(),
            "selectedCount", 0));
        continue;
      }
      var objects = population(
          set,
          where,
          candidate.count(),
          null,
          "set_" + caseIndex);
      ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
      testCase.name = "set objectCount " + candidate.boundary().toLowerCase()
          + " selected=" + candidate.count();
      testCase.expectedConstraintValid = candidate.expectedValid();
      testCase.objects = objects;
      testCase.links = List.of();
      cases.add(testCase);

      Map<String, Object> summary = baseSummary(set, objectCount, testCase);
      summary.put("purpose", candidate.expectedValid() ? "WITNESS" : "COUNTEREXAMPLE");
      summary.put("boundary", candidate.boundary());
      summary.put("selectedCount", candidate.count());
      summary.put("excludedByWhereCount", where == null ? 0 : 1);
      summary.put("expectedConstraintValid", candidate.expectedValid());
      if (where != null) {
        summary.put("whereIncludedValues", summaryAssignment(where.included().assignment()));
        summary.put("whereExcludedValues", summaryAssignment(where.excluded().assignment()));
      }
      summaries.add(Map.copyOf(summary));
      caseIndex++;
    }

    BasketScope scope = basketScope(objectCount, where != null);
    if (scope == null && context.constraint().getContainer() instanceof ch.interlis.ili2c.metamodel.Projection) {
      // GLOBAL is the only supported View scope. Exercise the actual combined population
      // even when GLOBAL and BASKET are algebraically indistinguishable (e.g. count == 0).
      boolean valid = valid(objectCount, 2);
      scope = new BasketScope(1, 1, valid, valid);
    }
    if (scope == null) {
      unsolved.add(Map.of(
          "reasonCode", "SET_BASKET_SCOPE_NOT_DISTINGUISHABLE_WITHIN_LIMIT",
          "reason", "No two-basket population within the automatic fixture limit distinguishes global from (BASKET) SET semantics.",
          "goal", "BASKET_SCOPE"));
    } else {
      var first = population(set, where, scope.first(), "set_scope_a", "set_scope_a");
      var second = population(set, where, scope.second(), "set_scope_b", "set_scope_b");
      var objects = new ArrayList<>(first);
      objects.addAll(second);

      boolean expected = set.perBasket() ? scope.perBasketValid() : scope.globalValid();
      ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
      testCase.name = "set basket scope global=" + scope.globalValid()
          + " perBasket=" + scope.perBasketValid();
      testCase.expectedConstraintValid = expected;
      testCase.objects = List.copyOf(objects);
      testCase.links = List.of();
      cases.add(testCase);

      Map<String, Object> summary = baseSummary(set, objectCount, testCase);
      summary.put("purpose", "SCOPE_PROOF");
      summary.put("boundary", "BASKET_SCOPE");
      summary.put("basketASelectedCount", scope.first());
      summary.put("basketBSelectedCount", scope.second());
      summary.put("globalSelectedCount", scope.first() + scope.second());
      summary.put("globalConstraintValid", scope.globalValid());
      summary.put("perBasketConstraintValid", scope.perBasketValid());
      summary.put("declaredPerBasket", set.perBasket());
      summary.put("expectedConstraintValid", expected);
      summaries.add(Map.copyOf(summary));
    }

    return new Plan(cases, summaries, unsolved);
  }

  private static Plan planNavigatedObjectCount(
      CompiledConstraintContext context,
      SemanticConstraint.Set set,
      SemanticConstraint.ObjectCountSetCondition condition,
      SemanticConstraint.NavigatedObjects objects) {
    List<ConstraintTestTools.TestCase> cases = new ArrayList<>();
    List<Map<String, Object>> summaries = new ArrayList<>();
    List<Map<String, Object>> unsolved = new ArrayList<>();
    if (set.preCondition() != null) {
      unsolved.add(Map.of(
          "reasonCode", "SET_NAVIGATED_OBJECTS_WHERE_PROOF_UNAVAILABLE",
          "reason", "Navigated objectCount with WHERE requires a combined root/selection graph solver.",
          "goal", "NAVIGATED_OBJECT_SET_WHERE"));
      return new Plan(cases, summaries, unsolved);
    }
    ConstraintExpression.ObjectCount count;
    List<Map<String, String>> routes;
    List<ConstraintModelSynthesizer.ModelBinding> bindings;
    try {
      count = new ConstraintExpression.ObjectCount(objects.path().path());
      var td = context.transferDescription();
      routes = ObjectPathRoutes.resolve(td, set.contextFqn(), count);
      bindings = routes.stream()
          .map(route -> ConstraintModelSynthesizer.bind(td, set.contextFqn(), count, route)).toList();
    } catch (IllegalArgumentException ex) {
      unsolved.add(Map.of(
          "reasonCode", "SET_NAVIGATED_OBJECTS_BINDING_UNAVAILABLE",
          "reason", ex.getMessage(),
          "goal", objects.path().path()));
      return new Plan(cases, summaries, unsolved);
    }

    var excluded = new ArrayList<Map<String,Object>>();
    int index = 1;
    var candidates = branchCandidates(condition);
    var expression = new ConstraintExpression.Comparison(condition.operator(), count,
        new ConstraintExpression.NumericLiteral(condition.threshold()));
    for (var binding : bindings) {
      var reference = binding.reference(count.key());
      var domain = reference.domain().numeric();
      String targetFqn = reference.navigation().getLast().targetClassFqn();
      for (CountCandidate candidate : candidates) {
        if (!domain.contains(BigDecimal.valueOf(candidate.count()))) {
          excluded.add(Map.of("reasonCode","PROVEN_UNREACHABLE","reason",candidate.boundary(),
              "justification","Compiled path cardinalities exclude count " + candidate.count(), "routeTargetFqn",targetFqn));
          continue;
        }
        try {
          var graph = ConstraintModelSynthesizer.synthesize(
              binding, Map.of(count.key(), BigDecimal.valueOf(candidate.count())), "set_path_" + index);
          ConstraintTestTools.TestCase testCase = ConstraintTestTools.TestCase.fromGraph(
              "set navigated objectCount " + candidate.boundary().toLowerCase()
                  + " selected=" + candidate.count()
                  + " target=" + targetFqn,
              candidate.expectedValid(),
              graph);
          testCase.plannedObjectCounts = Map.of(graph.objects().getFirst().oid(), Map.of(objects.path().path(), candidate.count()));
          cases.add(testCase);
          Map<String, Object> summary = new LinkedHashMap<>();
          summary.put("name", testCase.name);
          summary.put("purpose", candidate.expectedValid() ? "WITNESS" : "COUNTEREXAMPLE");
          summary.put("boundary", candidate.boundary());
          summary.put("objectSet", "PATH");
          summary.put("objectPath", objects.path().path());
          summary.put("routeTargetFqn", targetFqn);
          summary.put("selectedCount", candidate.count());
          summary.put("expectedConstraintValid", candidate.expectedValid());
          summary.put("objectCount", graph.objects().size());
          summary.put("associationLinkCount", graph.links().size());
          summaries.add(Map.copyOf(summary));
        } catch (IllegalArgumentException ex) {
          unsolved.add(Map.of(
              "reasonCode", "SET_NAVIGATED_OBJECTS_CARDINALITY_UNAVAILABLE",
              "reason", ex.getMessage(),
              "goal", candidate.boundary(),
              "routeTargetFqn", targetFqn,
              "selectedCount", candidate.count()));
        }
        index++;
      }
      var topology = ObjectCountTopologyPlanner.plan(context.transferDescription(), expression, binding, routes);
      cases.addAll(topology.cases()); summaries.addAll(topology.summaries()); unsolved.addAll(topology.gaps()); excluded.addAll(topology.excluded());
    }

    // A navigated objectCount is evaluated for each context object. Moving independent roots
    // between baskets therefore cannot turn their per-object path counts into one global count;
    // only objectCount(ALL) has a distinct cross-basket population goal.
    if (cases.stream().noneMatch(c -> Boolean.TRUE.equals(c.expectedConstraintValid))
        || cases.stream().noneMatch(c -> Boolean.FALSE.equals(c.expectedConstraintValid))) unsolved.add(Map.of("reasonCode","OBJECT_PATH_REQUIRED_OUTCOME_UNAVAILABLE","reason","Witness and counterexample remain obligatory."));
    return new Plan(cases, summaries, unsolved, cases.size(), excluded);
  }

  private static Plan planBooleanExpression(
      CompiledConstraintContext context,
      SemanticConstraint.Set set,
      ConstraintExpression expression) {
    List<ConstraintTestTools.TestCase> cases = new ArrayList<>();
    List<Map<String, Object>> summaries = new ArrayList<>();
    List<Map<String, Object>> unsolved = new ArrayList<>();
    if (set.preCondition() != null) {
      unsolved.add(Map.of(
          "reasonCode", "SET_BOOLEAN_WHERE_COMBINED_PROOF_UNAVAILABLE",
          "reason", "Boolean SET expressions with WHERE require a combined population solver; the candidate is retained instead of approximated.",
          "goal", "WHERE_AND_BOOLEAN_CONDITION"));
      return new Plan(cases, summaries, unsolved);
    }
    ConstraintModelSynthesizer.ModelBinding binding;
    try {
      binding = ch.so.agi.mcp.constraint.ViewProofScope.bind(
          context, set.contextFqn(), expression);
    } catch (IllegalArgumentException ex) {
      unsolved.add(Map.of(
          "reasonCode", "SET_BOOLEAN_BINDING_UNAVAILABLE",
          "reason", ex.getMessage(),
          "goal", "BOOLEAN_EXPRESSION"));
      return new Plan(cases, summaries, unsolved);
    }
    ConstraintCoveragePlanner.CoveragePlan coverage = ConstraintCoveragePlanner.solve(
        expression, binding);
    int index = 1;
    for (ConstraintCoveragePlanner.CoverageCase coverageCase : coverage.cases()) {
      Map<String, Object> assignment = coverageCase.solution().assignment();
      boolean expected = ConstraintExpressionEngine.evaluateConstraint(
          expression, ConstraintExpressionEngine.EvaluationContext.of(assignment));
      ConstraintModelSynthesizer.ObjectGraph graph = ConstraintModelSynthesizer.synthesize(
          binding, assignment, "set_boolean_" + index);
      ConstraintTestTools.TestCase testCase = ConstraintTestTools.TestCase.fromGraph(
          "set boolean case " + index + " - " + coverageCase.goal().reason(),
          expected,
          graph);
      cases.add(testCase);
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("name", testCase.name);
      summary.put("purpose", expected ? "WITNESS" : "COUNTEREXAMPLE");
      summary.put("reason", coverageCase.goal().reason());
      summary.put("source", coverageCase.goal().expression().toInterlis(set.version()));
      summary.put("coveredGoals", coverageCase.coveredGoals().stream()
          .map(goal -> ConstraintCoveragePlanner.describeGoal(goal, set.version())).toList());
      summary.put("expectedConstraintValid", expected);
      summary.put("values", summaryAssignment(assignment));
      summary.put("objectCount", graph.objects().size());
      summary.put("associationLinkCount", graph.links().size());
      summaries.add(Map.copyOf(summary));
      index++;
    }
    for (ConstraintGoalSolver.Solution solution : coverage.unsolved()) {
      unsolved.add(Map.of(
          "reasonCode", solution.reasonCode(),
          "reason", solution.reason(),
          "goal", solution.goal().reason(),
          "expression", solution.goal().expression().toInterlis(set.version())));
    }
    return new Plan(cases, summaries, unsolved, ConstraintCoveragePlanner.solvedGoalCount(coverage),
        ConstraintCoveragePlanner.excludedGoals(coverage, set.version()));
  }

  private static WhereTemplates whereTemplates(
      CompiledConstraintContext context,
      SemanticConstraint.Set set) {
    ConstraintExpression preCondition = Objects.requireNonNull(set.preCondition());
    ConstraintModelSynthesizer.ModelBinding binding = ch.so.agi.mcp.constraint.ViewProofScope.bind(
        context,
        set.contextFqn(),
        preCondition);
    ConstraintGoalSolver.Solution included = solve(
        preCondition,
        binding,
        ConstraintExpressionEngine.GoalKind.TRUE,
        "SET WHERE included object");
    ConstraintGoalSolver.Solution excluded = solve(
        preCondition,
        binding,
        ConstraintExpressionEngine.GoalKind.FALSE,
        "SET WHERE excluded object");
    return new WhereTemplates(binding, included, excluded);
  }

  private static ConstraintGoalSolver.Solution solve(
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding,
      ConstraintExpressionEngine.GoalKind goal,
      String reason) {
    return ConstraintGoalSolver.solve(
        new ConstraintExpressionEngine.TestGoal(goal, expression, reason),
        binding);
  }

  private static void requireSimpleMemberGraph(
      SemanticConstraint.Set set,
      ConstraintModelSynthesizer.ModelBinding binding,
      Map<String, Object> assignment,
      String label) {
    ConstraintModelSynthesizer.ObjectGraph graph = ConstraintModelSynthesizer.synthesize(
        binding, assignment, "set_safety");
    long contextObjects = graph.objects().stream()
        .filter(object -> set.contextFqn().equals(object.classFqn()))
        .count();
    if (contextObjects != 1 || graph.objects().size() != 1 || !graph.links().isEmpty()) {
      throw new IllegalArgumentException(
          label + " SET member must synthesize exactly one context object and no auxiliary objects/links; got "
              + graph.objects().size() + " objects, " + contextObjects + " context objects and "
              + graph.links().size() + " links.");
    }
  }

  private static List<CountCandidate> branchCandidates(
      SemanticConstraint.ObjectCountSetCondition condition) {
    List<Integer> counts = candidateCounts(condition.threshold());
    CountCandidate witness = counts.stream()
        .filter(count -> valid(condition, count))
        .map(count -> new CountCandidate(count, true, "VALID_BOUNDARY"))
        .min(Comparator.comparing(count -> distance(condition.threshold(), count.count())))
        .orElse(null);
    CountCandidate counterexample = counts.stream()
        .filter(count -> !valid(condition, count))
        .map(count -> new CountCandidate(count, false, "INVALID_BOUNDARY"))
        .min(Comparator.comparing(count -> distance(condition.threshold(), count.count())))
        .orElse(null);

    List<CountCandidate> result = new ArrayList<>();
    if (counterexample != null) {
      result.add(counterexample);
    }
    if (witness != null) {
      result.add(witness);
    }
    return List.copyOf(result);
  }

  private static List<Integer> candidateCounts(BigDecimal threshold) {
    Set<Integer> counts = new LinkedHashSet<>();
    counts.add(0);
    counts.add(1);
    int floor;
    try {
      floor = threshold.setScale(0, java.math.RoundingMode.FLOOR).intValueExact();
    } catch (ArithmeticException ex) {
      floor = threshold.signum() < 0 ? 0 : MAX_COUNT;
    }
    for (int delta = -2; delta <= 2; delta++) {
      int candidate = floor + delta;
      if (candidate >= 0 && candidate <= MAX_COUNT) {
        counts.add(candidate);
      }
    }
    counts.add(MAX_COUNT);
    return List.copyOf(counts);
  }

  private static BigDecimal distance(BigDecimal threshold, int count) {
    return threshold.subtract(BigDecimal.valueOf(count)).abs();
  }

  private static boolean valid(
      SemanticConstraint.ObjectCountSetCondition condition,
      int count) {
    int comparison = BigDecimal.valueOf(count).compareTo(condition.threshold());
    return switch (condition.operator()) {
      case EQ -> comparison == 0;
      case NE -> comparison != 0;
      case LT -> comparison < 0;
      case LE -> comparison <= 0;
      case GT -> comparison > 0;
      case GE -> comparison >= 0;
    };
  }

  private static List<ConstraintTestTools.TestObject> population(
      SemanticConstraint.Set set,
      WhereTemplates where,
      int selectedCount,
      String basketId,
      String prefix) {
    List<ConstraintTestTools.TestObject> objects = new ArrayList<>();

    if (where == null) {
      for (int i = 0; i < selectedCount; i++) {
        ConstraintTestTools.TestObject object = new ConstraintTestTools.TestObject();
        object.classFqn = set.contextFqn();
        object.oid = prefix + "_selected_" + (i + 1);
        object.basketId = basketId;
        object.values = Map.of();
        object.references = Map.of();
        objects.add(object);
      }
      return List.copyOf(objects);
    }

    for (int i = 0; i < selectedCount; i++) {
      addGraph(
          set,
          ConstraintModelSynthesizer.synthesize(
              where.binding(),
              where.included().assignment(),
              prefix + "_selected_" + (i + 1)),
          basketId,
          objects);
    }
    addGraph(
        set,
        ConstraintModelSynthesizer.synthesize(
            where.binding(),
            where.excluded().assignment(),
            prefix + "_excluded"),
        basketId,
        objects);
    return List.copyOf(objects);
  }

  private static void addGraph(
      SemanticConstraint.Set set,
      ConstraintModelSynthesizer.ObjectGraph graph,
      String basketId,
      List<ConstraintTestTools.TestObject> objects) {
    requireSimpleGraph(set, graph);
    var result = ConstraintTestTools.TestObject.fromGraph(graph.objects().getFirst());
    result.basketId = basketId;
    objects.add(result);
  }

  private static void requireSimpleGraph(
      SemanticConstraint.Set set,
      ConstraintModelSynthesizer.ObjectGraph graph) {
    if (graph.objects().size() != 1
        || !set.contextFqn().equals(graph.objects().getFirst().classFqn())
        || !graph.links().isEmpty()) {
      throw new IllegalArgumentException(
          "SET WHERE proof currently requires one direct context object without auxiliary graph objects or links.");
    }
  }

  private static BasketScope basketScope(
      SemanticConstraint.ObjectCountSetCondition condition,
      boolean whereAvailable) {
    int minimum = whereAvailable ? 0 : 1;
    for (int first = minimum; first <= MAX_COUNT / 2; first++) {
      for (int second = minimum; second <= MAX_COUNT / 2; second++) {
        boolean global = valid(condition, first + second);
        boolean perBasket = valid(condition, first) && valid(condition, second);
        if (global != perBasket) {
          return new BasketScope(first, second, global, perBasket);
        }
      }
    }
    return null;
  }

  private static Map<String, Object> baseSummary(
      SemanticConstraint.Set set,
      SemanticConstraint.ObjectCountSetCondition objectCount,
      ConstraintTestTools.TestCase testCase) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("name", testCase.name);
    summary.put("operator", objectCount.operator().interlis());
    summary.put("threshold", objectCount.threshold().stripTrailingZeros().toPlainString());
    summary.put("objectSet", "ALL");
    summary.put("perBasket", set.perBasket());
    summary.put("wherePresent", set.preCondition() != null);
    return summary;
  }

  private static String solverReason(ConstraintGoalSolver.Solution solution) {
    if (solution.solved()) {
      return "SOLVED";
    }
    String code = solution.reasonCode() == null || solution.reasonCode().isBlank()
        ? "UNSOLVED"
        : solution.reasonCode();
    return code + ": " + solution.reason();
  }

  private record BasketScope(
      int first,
      int second,
      boolean globalValid,
      boolean perBasketValid) {
  }
}
