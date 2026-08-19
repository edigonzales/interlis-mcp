package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.AssociationDef;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.Topic;
import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.ConstraintExpression;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine;
import ch.so.agi.mcp.constraint.ConstraintGoalSolver;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer;
import ch.so.agi.mcp.constraint.SemanticConstraint;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic semantic proof-case planner for compiled INTERLIS UNIQUE constraints.
 *
 * <p>The planner never decides whether a case really satisfies the constraint. It only constructs
 * witnesses/counterexamples from the B1 UNIQUE IR. The existing explicit-case validator remains the
 * oracle and verifies every generated fixture with ilivalidator.</p>
 */
final class UniqueConstraintCasePlanner {

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
        throw new IllegalArgumentException("UNIQUE proof cases and summaries must have equal size.");
      }
    }

    int goalCount() {
      return cases.size() + unsolved.size();
    }

    boolean complete() {
      return unsolved.isEmpty();
    }
  }

  private record ScopedGraph(
      ConstraintModelSynthesizer.ObjectGraph graph,
      @Nullable String basketScope) {
  }

  private record LocalKeyFixture(
      Map<String, Object> values,
      Map<String, String> references) {
  }

  private UniqueConstraintCasePlanner() {
  }

  static Plan plan(
      CompiledConstraintContext context,
      SemanticConstraint.Unique unique) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(unique, "unique");
    return unique.local()
        ? planLocal(context, unique)
        : planGlobal(context, unique);
  }

  private static Plan planGlobal(
      CompiledConstraintContext context,
      SemanticConstraint.Unique unique) {
    List<ConstraintTestTools.TestCase> cases = new ArrayList<>();
    List<Map<String, Object>> summaries = new ArrayList<>();
    List<Map<String, Object>> unsolved = new ArrayList<>();

    List<ConstraintExpression> keyExpressions;
    ConstraintExpression keyDefined;
    ConstraintExpression eligible;
    ConstraintModelSynthesizer.ModelBinding binding;
    try {
      keyExpressions = unique.elements().stream().map(UniqueConstraintCasePlanner::pathExpression).toList();
      keyDefined = allDefined(keyExpressions);
      eligible = unique.preCondition() == null
          ? keyDefined
          : and(List.of(unique.preCondition(), keyDefined));
      binding = ConstraintModelSynthesizer.bind(
          context.transferDescription(), unique.contextFqn(), eligible);
    } catch (IllegalArgumentException ex) {
      unsolved.add(unsolved("UNIQUE_MODEL_BINDING_UNAVAILABLE", ex.getMessage(), "bind UNIQUE key paths"));
      return new Plan(cases, summaries, unsolved);
    }

    ConstraintGoalSolver.Solution included = solveTrue(
        eligible,
        binding,
        "UNIQUE object participates and all key components are defined");
    if (!included.solved()) {
      unsolved.add(unsolved(
          nonBlank(included.reasonCode(), "UNIQUE_INCLUDED_ASSIGNMENT_UNSOLVED"),
          nonBlank(included.reason(), "No model-valid assignment could be generated for a participating UNIQUE object."),
          "participating UNIQUE object"));
      return new Plan(cases, summaries, unsolved);
    }

    Map<String, Object> includedAssignment = included.assignment();
    ConstraintModelSynthesizer.ObjectGraph first = synthesize(binding, includedAssignment, "unique_witness_a");
    addCase(
        context,
        cases,
        summaries,
        "single participating object",
        true,
        "WITNESS",
        "A single participating object cannot violate UNIQUE.",
        includedAssignment,
        List.of(new ScopedGraph(first, null)));

    ConstraintModelSynthesizer.ObjectGraph duplicate = synthesize(
        binding, includedAssignment, "unique_duplicate_b");
    addCase(
        context,
        cases,
        summaries,
        "duplicate UNIQUE key in one basket",
        false,
        "COUNTEREXAMPLE",
        "Two participating objects with the same fully defined key violate UNIQUE in one basket.",
        includedAssignment,
        List.of(new ScopedGraph(first, null), new ScopedGraph(duplicate, null)));

    ConstraintModelSynthesizer.ObjectGraph crossA = synthesize(
        binding, includedAssignment, "unique_cross_a");
    ConstraintModelSynthesizer.ObjectGraph crossB = synthesize(
        binding, includedAssignment, "unique_cross_b");
    boolean crossBasketValid = unique.perBasket();
    addCase(
        context,
        cases,
        summaries,
        crossBasketValid
            ? "same UNIQUE key in different baskets"
            : "global duplicate UNIQUE key across baskets",
        crossBasketValid,
        crossBasketValid ? "WITNESS" : "COUNTEREXAMPLE",
        crossBasketValid
            ? "UNIQUE (BASKET) scopes equality to each basket."
            : "Global UNIQUE compares participating objects across basket boundaries.",
        includedAssignment,
        List.of(new ScopedGraph(crossA, "basketA"), new ScopedGraph(crossB, "basketB")));

    if (unique.preCondition() != null) {
      ConstraintExpression excludedExpression = and(List.of(
          new ConstraintExpression.Not(unique.preCondition()),
          keyDefined));
      ConstraintGoalSolver.Solution excluded = solveTrue(
          excludedExpression,
          binding,
          "UNIQUE WHERE excluded object with a defined key");
      if (excluded.solved()) {
        ConstraintModelSynthesizer.ObjectGraph includedGraph = synthesize(
            binding, includedAssignment, "unique_where_included");
        ConstraintModelSynthesizer.ObjectGraph excludedGraph = synthesize(
            binding, excluded.assignment(), "unique_where_excluded");
        boolean sameKey = sameKeyAssignments(includedAssignment, excluded.assignment(), keyExpressions);
        addCase(
            context,
            cases,
            summaries,
            sameKey
                ? "duplicate key excluded by UNIQUE WHERE"
                : "UNIQUE WHERE excluded branch",
            true,
            "WITNESS",
            sameKey
                ? "An object outside the WHERE predicate does not participate even with the same key."
                : "The generated case exercises the WHERE-excluded branch; the finite solver could not keep the key identical while flipping the predicate.",
            Map.of("included", includedAssignment, "excluded", excluded.assignment()),
            List.of(new ScopedGraph(includedGraph, null), new ScopedGraph(excludedGraph, null)));
        if (!sameKey) {
          unsolved.add(unsolved(
              "WHERE_DUPLICATE_EXCLUSION_NOT_ISOLATED",
              "The WHERE false branch was generated, but its UNIQUE key differs from the participating object's key.",
              "same-key WHERE exclusion"));
        }
      } else {
        unsolved.add(unsolved(
            nonBlank(excluded.reasonCode(), "UNIQUE_WHERE_FALSE_ASSIGNMENT_UNSOLVED"),
            nonBlank(excluded.reason(), "No model-valid assignment could make the UNIQUE WHERE predicate false while keeping the key defined."),
            "WHERE excluded branch"));
      }
    }

    addUndefinedKeyCases(
        context,
        unique,
        keyExpressions,
        keyDefined,
        binding,
        cases,
        summaries,
        unsolved,
        false,
        null);

    return new Plan(cases, summaries, unsolved);
  }

  private static Plan planLocal(
      CompiledConstraintContext context,
      SemanticConstraint.Unique unique) {
    List<ConstraintTestTools.TestCase> cases = new ArrayList<>();
    List<Map<String, Object>> summaries = new ArrayList<>();
    List<Map<String, Object>> unsolved = new ArrayList<>();

    SemanticConstraint.ConstraintPath prefix = unique.prefix();
    if (prefix == null
        || !SIMPLE_IDENTIFIER.matcher(prefix.path()).matches()
        || prefix.targetViewableFqn() == null) {
      unsolved.add(unsolved(
          "LOCAL_UNIQUE_PREFIX_UNSUPPORTED",
          "LOCAL UNIQUE proof currently requires a direct STRUCTURE/composition attribute prefix.",
          "resolve LOCAL prefix"));
      return new Plan(cases, summaries, unsolved);
    }

    String componentFqn = prefix.targetViewableFqn();
    List<ConstraintExpression> keyExpressions;
    ConstraintExpression keyDefined;
    ConstraintModelSynthesizer.ModelBinding componentBinding;
    try {
      keyExpressions = unique.elements().stream().map(UniqueConstraintCasePlanner::pathExpression).toList();
      keyDefined = allDefined(keyExpressions);
      componentBinding = ConstraintModelSynthesizer.bind(
          context.transferDescription(), componentFqn, keyDefined);
    } catch (IllegalArgumentException ex) {
      unsolved.add(unsolved("LOCAL_UNIQUE_KEY_BINDING_UNAVAILABLE", ex.getMessage(), "bind LOCAL unique key"));
      return new Plan(cases, summaries, unsolved);
    }

    ConstraintGoalSolver.Solution localKey = solveTrue(
        keyDefined,
        componentBinding,
        "LOCAL UNIQUE structure element with defined key");
    if (!localKey.solved()) {
      unsolved.add(unsolved(
          nonBlank(localKey.reasonCode(), "LOCAL_UNIQUE_KEY_UNSOLVED"),
          nonBlank(localKey.reason(), "No structure value with a fully defined LOCAL UNIQUE key could be synthesized."),
          "LOCAL unique key"));
      return new Plan(cases, summaries, unsolved);
    }

    LocalKeyFixture child;
    try {
      child = localKeyFixture(componentBinding, localKey.assignment(), "local_unique_child");
    } catch (IllegalArgumentException ex) {
      unsolved.add(unsolved("LOCAL_UNIQUE_NAVIGATION_UNSUPPORTED", ex.getMessage(), "materialize LOCAL unique key"));
      return new Plan(cases, summaries, unsolved);
    }

    ParentState participating = parentState(context, unique, true, unsolved);
    if (participating == null) {
      return new Plan(cases, summaries, unsolved);
    }

    addLocalCase(
        context,
        prefix.path(),
        cases,
        summaries,
        "single LOCAL member",
        true,
        "WITNESS",
        "One member in the local collection cannot violate LOCAL UNIQUE.",
        participating.graph(),
        List.of(child));

    addLocalCase(
        context,
        prefix.path(),
        cases,
        summaries,
        "duplicate LOCAL key in one parent",
        false,
        "COUNTEREXAMPLE",
        "Two members of the same parent's collection have the same defined LOCAL UNIQUE key.",
        cloneGraph(participating.graph(), "local_duplicate_parent"),
        List.of(child, child));

    ConstraintModelSynthesizer.ObjectGraph parentA = cloneGraph(
        participating.graph(), "local_scope_parent_a");
    ConstraintModelSynthesizer.ObjectGraph parentB = cloneGraph(
        participating.graph(), "local_scope_parent_b");
    ConstraintTestTools.TestCase separateParents = localCase(
        context,
        prefix.path(),
        "same LOCAL key in different parents",
        true,
        List.of(
            new ParentWithChildren(parentA, List.of(child)),
            new ParentWithChildren(parentB, List.of(child))));
    cases.add(separateParents);
    summaries.add(summary(
        "WITNESS",
        separateParents.name,
        "LOCAL UNIQUE is scoped to each parent object's structure collection.",
        true,
        Map.of("localKey", localKey.assignment()),
        2,
        1));

    if (unique.preCondition() != null) {
      ParentState excluded = parentState(context, unique, false, unsolved);
      if (excluded != null) {
        addLocalCase(
            context,
            prefix.path(),
            cases,
            summaries,
            "LOCAL duplicate excluded by WHERE",
            true,
            "WITNESS",
            "A parent outside the UNIQUE WHERE predicate does not apply its LOCAL uniqueness check.",
            excluded.graph(),
            List.of(child, child));
      }
    }

    addUndefinedKeyCases(
        context,
        unique,
        keyExpressions,
        keyDefined,
        componentBinding,
        cases,
        summaries,
        unsolved,
        true,
        new LocalUndefinedContext(prefix.path(), participating.graph()));

    return new Plan(cases, summaries, unsolved);
  }

  private record ParentState(ConstraintModelSynthesizer.ObjectGraph graph) {
  }

  private record ParentWithChildren(
      ConstraintModelSynthesizer.ObjectGraph graph,
      List<LocalKeyFixture> children) {
  }

  private record LocalUndefinedContext(
      String prefixName,
      ConstraintModelSynthesizer.ObjectGraph parentGraph) {
  }

  private static @Nullable ParentState parentState(
      CompiledConstraintContext context,
      SemanticConstraint.Unique unique,
      boolean participating,
      List<Map<String, Object>> unsolved) {
    if (unique.preCondition() == null) {
      if (!participating) {
        return null;
      }
      ConstraintModelSynthesizer.ObjectGraph graph = new ConstraintModelSynthesizer.ObjectGraph(
          List.of(new ConstraintModelSynthesizer.GraphObject(
              unique.contextFqn(), "local_parent_root", Map.of(), Map.of())),
          List.of());
      return new ParentState(graph);
    }

    ConstraintModelSynthesizer.ModelBinding binding;
    try {
      binding = ConstraintModelSynthesizer.bind(
          context.transferDescription(), unique.contextFqn(), unique.preCondition());
    } catch (IllegalArgumentException ex) {
      unsolved.add(unsolved(
          "LOCAL_UNIQUE_WHERE_BINDING_UNAVAILABLE",
          ex.getMessage(),
          participating ? "LOCAL WHERE included parent" : "LOCAL WHERE excluded parent"));
      return null;
    }

    ConstraintExpression goalExpression = participating
        ? unique.preCondition()
        : new ConstraintExpression.Not(unique.preCondition());
    ConstraintGoalSolver.Solution solution = solveTrue(
        goalExpression,
        binding,
        participating ? "LOCAL UNIQUE WHERE participating parent" : "LOCAL UNIQUE WHERE excluded parent");
    if (!solution.solved()) {
      unsolved.add(unsolved(
          nonBlank(solution.reasonCode(), participating
              ? "LOCAL_UNIQUE_WHERE_TRUE_UNSOLVED"
              : "LOCAL_UNIQUE_WHERE_FALSE_UNSOLVED"),
          solution.reason(),
          participating ? "LOCAL WHERE included parent" : "LOCAL WHERE excluded parent"));
      return null;
    }
    return new ParentState(synthesize(
        binding,
        solution.assignment(),
        participating ? "local_where_true" : "local_where_false"));
  }

  private static void addUndefinedKeyCases(
      CompiledConstraintContext context,
      SemanticConstraint.Unique unique,
      List<ConstraintExpression> keyExpressions,
      ConstraintExpression keyDefined,
      ConstraintModelSynthesizer.ModelBinding binding,
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries,
      List<Map<String, Object>> unsolved,
      boolean local,
      @Nullable LocalUndefinedContext localContext) {
    for (int i = 0; i < keyExpressions.size(); i++) {
      ConstraintExpression key = keyExpressions.get(i);
      ConstraintModelSynthesizer.ReferenceBinding keyBinding = binding.references().get(referenceName(key));
      if (keyBinding == null || keyBinding.domain().mandatory()) {
        continue;
      }

      List<ConstraintExpression> terms = new ArrayList<>();
      if (!local && unique.preCondition() != null) {
        terms.add(unique.preCondition());
      }
      for (int j = 0; j < keyExpressions.size(); j++) {
        ConstraintExpression candidate = keyExpressions.get(j);
        terms.add(j == i
            ? new ConstraintExpression.Not(new ConstraintExpression.Defined(candidate))
            : new ConstraintExpression.Defined(candidate));
      }
      ConstraintExpression undefinedGoal = and(terms);
      ConstraintGoalSolver.Solution solution = solveTrue(
          undefinedGoal,
          binding,
          "UNIQUE key component undefined");
      if (!solution.solved()) {
        unsolved.add(unsolved(
            nonBlank(solution.reasonCode(), "UNIQUE_UNDEFINED_KEY_UNSOLVED"),
            solution.reason(),
            "undefined UNIQUE key component " + referenceName(key)));
        continue;
      }

      if (local) {
        if (localContext == null) {
          continue;
        }
        LocalKeyFixture child;
        try {
          child = localKeyFixture(binding, solution.assignment(), "local_unique_undefined");
        } catch (IllegalArgumentException ex) {
          unsolved.add(unsolved(
              "LOCAL_UNIQUE_UNDEFINED_NAVIGATION_UNSUPPORTED",
              ex.getMessage(),
              "undefined LOCAL UNIQUE key component"));
          continue;
        }
        addLocalCase(
            context,
            localContext.prefixName(),
            cases,
            summaries,
            "undefined LOCAL UNIQUE key component",
            true,
            "WITNESS",
            "A LOCAL UNIQUE member with an undefined key component does not create a duplicate-key violation.",
            cloneGraph(localContext.parentGraph(), "local_undefined_parent"),
            List.of(child, child));
      } else {
        ConstraintModelSynthesizer.ObjectGraph first = synthesize(
            binding, solution.assignment(), "unique_undefined_a");
        ConstraintModelSynthesizer.ObjectGraph second = synthesize(
            binding, solution.assignment(), "unique_undefined_b");
        addCase(
            context,
            cases,
            summaries,
            "undefined UNIQUE key component " + referenceName(key),
            true,
            "WITNESS",
            "Objects with an undefined UNIQUE key component do not create a duplicate-key violation.",
            solution.assignment(),
            List.of(new ScopedGraph(first, null), new ScopedGraph(second, null)));
      }
    }
  }

  private static LocalKeyFixture localKeyFixture(
      ConstraintModelSynthesizer.ModelBinding binding,
      Map<String, Object> assignment,
      String oidPrefix) {
    ConstraintModelSynthesizer.ObjectGraph graph = synthesize(binding, assignment, oidPrefix);
    if (graph.objects().size() != 1 || !graph.links().isEmpty()) {
      throw new IllegalArgumentException(
          "LOCAL UNIQUE proof currently supports direct scalar structure-member keys only; navigated LOCAL keys require auxiliary objects/links.");
    }
    ConstraintModelSynthesizer.GraphObject root = graph.objects().getFirst();
    if (!root.references().isEmpty()) {
      throw new IllegalArgumentException(
          "LOCAL UNIQUE proof currently does not support reference-valued navigation inside the structure member.");
    }
    return new LocalKeyFixture(root.values(), root.references());
  }

  private static void addLocalCase(
      CompiledConstraintContext context,
      String prefixName,
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries,
      String name,
      boolean expected,
      String purpose,
      String reason,
      ConstraintModelSynthesizer.ObjectGraph parent,
      List<LocalKeyFixture> children) {
    ConstraintTestTools.TestCase testCase = localCase(
        context,
        prefixName,
        name,
        expected,
        List.of(new ParentWithChildren(parent, children)));
    cases.add(testCase);
    summaries.add(summary(
        purpose,
        name,
        reason,
        expected,
        Map.of("localMemberCount", children.size()),
        testCase.objects.size(),
        1));
  }

  private static ConstraintTestTools.TestCase localCase(
      CompiledConstraintContext context,
      String prefixName,
      String name,
      boolean expected,
      List<ParentWithChildren> parents) {
    List<ConstraintTestTools.TestObject> objects = new ArrayList<>();
    List<ConstraintTestTools.TestLink> links = new ArrayList<>();
    for (ParentWithChildren parent : parents) {
      ConstraintModelSynthesizer.ObjectGraph graph = parent.graph();
      if (graph.objects().isEmpty()) {
        throw new IllegalArgumentException("LOCAL UNIQUE parent graph has no root object.");
      }
      String rootOid = graph.objects().getFirst().oid();
      for (ConstraintModelSynthesizer.GraphObject graphObject : graph.objects()) {
        ConstraintTestTools.TestObject object = new ConstraintTestTools.TestObject();
        object.classFqn = graphObject.classFqn();
        object.oid = graphObject.oid();
        Map<String, Object> values = new LinkedHashMap<>(graphObject.values());
        if (graphObject.oid().equals(rootOid)) {
          List<Map<String, Object>> localValues = parent.children().stream()
              .map(child -> new LinkedHashMap<>(child.values()))
              .map(Map::<String, Object>copyOf)
              .toList();
          values.put(prefixName, localValues);
        }
        object.values = Map.copyOf(values);
        object.references = graphObject.references();
        objects.add(object);
      }
      for (ConstraintModelSynthesizer.GraphLink graphLink : graph.links()) {
        ConstraintTestTools.TestLink link = new ConstraintTestTools.TestLink();
        link.associationFqn = graphLink.associationFqn();
        link.roles = graphLink.roles();
        objects.size();
        links.add(link);
      }
    }
    ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
    testCase.name = name;
    testCase.expectedConstraintValid = expected;
    testCase.objects = List.copyOf(objects);
    testCase.links = List.copyOf(links);
    return testCase;
  }

  private static void addCase(
      CompiledConstraintContext context,
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries,
      String name,
      boolean expected,
      String purpose,
      String reason,
      Object values,
      List<ScopedGraph> graphs) {
    ConstraintTestTools.TestCase testCase = testCase(context, name, expected, graphs);
    cases.add(testCase);
    summaries.add(summary(
        purpose,
        name,
        reason,
        expected,
        values,
        testCase.objects.size(),
        distinctBasketCount(testCase)));
  }

  private static ConstraintTestTools.TestCase testCase(
      CompiledConstraintContext context,
      String name,
      boolean expected,
      List<ScopedGraph> graphs) {
    BasketAllocator baskets = new BasketAllocator(context);
    List<ConstraintTestTools.TestObject> objects = new ArrayList<>();
    List<ConstraintTestTools.TestLink> links = new ArrayList<>();
    for (ScopedGraph scoped : graphs) {
      for (ConstraintModelSynthesizer.GraphObject graphObject : scoped.graph().objects()) {
        ConstraintTestTools.TestObject object = new ConstraintTestTools.TestObject();
        object.classFqn = graphObject.classFqn();
        object.oid = graphObject.oid();
        object.values = graphObject.values();
        object.references = graphObject.references();
        if (scoped.basketScope() != null) {
          object.basketId = baskets.forElement(graphObject.classFqn(), scoped.basketScope());
        }
        objects.add(object);
      }
      for (ConstraintModelSynthesizer.GraphLink graphLink : scoped.graph().links()) {
        ConstraintTestTools.TestLink link = new ConstraintTestTools.TestLink();
        link.associationFqn = graphLink.associationFqn();
        link.roles = graphLink.roles();
        Element element = context.transferDescription().getElement(graphLink.associationFqn());
        if (scoped.basketScope() != null
            && element instanceof AssociationDef association
            && !association.isLightweight()) {
          link.basketId = baskets.forElement(graphLink.associationFqn(), scoped.basketScope());
        }
        links.add(link);
      }
    }
    ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
    testCase.name = name;
    testCase.expectedConstraintValid = expected;
    testCase.objects = List.copyOf(objects);
    testCase.links = List.copyOf(links);
    return testCase;
  }

  private static int distinctBasketCount(ConstraintTestTools.TestCase testCase) {
    Set<String> explicit = new LinkedHashSet<>();
    for (ConstraintTestTools.TestObject object : testCase.objects) {
      if (object.basketId != null) {
        explicit.add(object.basketId);
      }
    }
    return explicit.isEmpty() ? 1 : explicit.size();
  }

  private static Map<String, Object> summary(
      String purpose,
      String name,
      String reason,
      boolean expected,
      Object values,
      int objectCount,
      int basketCount) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("purpose", purpose);
    result.put("name", name);
    result.put("reason", reason);
    result.put("expectedConstraintValid", expected);
    result.put("values", summarize(values));
    result.put("objectCount", objectCount);
    result.put("basketCount", basketCount);
    return result;
  }

  private static Map<String, Object> unsolved(
      String reasonCode,
      String reason,
      String goal) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("goal", goal);
    result.put("reasonCode", reasonCode);
    result.put("reason", reason == null ? "" : reason);
    return result;
  }

  private static ConstraintGoalSolver.Solution solveTrue(
      ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding,
      String reason) {
    return ConstraintGoalSolver.solve(
        new ConstraintExpressionEngine.TestGoal(
            ConstraintExpressionEngine.GoalKind.TRUE,
            expression,
            reason),
        binding);
  }

  private static ConstraintModelSynthesizer.ObjectGraph synthesize(
      ConstraintModelSynthesizer.ModelBinding binding,
      Map<String, Object> assignment,
      String oidPrefix) {
    return ConstraintModelSynthesizer.synthesize(binding, assignment, oidPrefix);
  }

  private static ConstraintExpression pathExpression(SemanticConstraint.ConstraintPath path) {
    if (path.endpointType().collection()
        || path.endpointType().scalarKind() == ConstraintExpression.ScalarKind.GEOMETRY
        || path.endpointType().scalarKind() == ConstraintExpression.ScalarKind.UNKNOWN) {
      throw new IllegalArgumentException(
          "UNIQUE proof requires scalar NUMERIC/BOOLEAN/ENUM/TEXT/MTEXT key endpoints; got "
              + path.endpointType() + " for " + path.path() + ".");
    }
    if (SIMPLE_IDENTIFIER.matcher(path.path()).matches()) {
      return new ConstraintExpression.Attribute(path.path(), path.endpointType());
    }
    return new ConstraintExpression.Path(path.path(), path.endpointType());
  }

  private static ConstraintExpression allDefined(List<ConstraintExpression> keys) {
    if (keys.isEmpty()) {
      throw new IllegalArgumentException("UNIQUE requires at least one key expression.");
    }
    return and(keys.stream().map(ConstraintExpression.Defined::new).map(ConstraintExpression.class::cast).toList());
  }

  private static ConstraintExpression and(Collection<ConstraintExpression> terms) {
    List<ConstraintExpression> normalized = terms.stream().filter(Objects::nonNull).toList();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("Boolean conjunction requires at least one term.");
    }
    return normalized.size() == 1 ? normalized.getFirst() : new ConstraintExpression.And(normalized);
  }

  private static String referenceName(ConstraintExpression expression) {
    return switch (expression) {
      case ConstraintExpression.Attribute attribute -> attribute.name();
      case ConstraintExpression.Path path -> path.path();
      default -> throw new IllegalArgumentException("UNIQUE key is not an attribute/path reference: " + expression);
    };
  }

  private static boolean sameKeyAssignments(
      Map<String, Object> left,
      Map<String, Object> right,
      List<ConstraintExpression> keys) {
    for (ConstraintExpression key : keys) {
      String name = referenceName(key);
      if (!Objects.equals(left.get(name), right.get(name))) {
        return false;
      }
    }
    return true;
  }

  private static ConstraintModelSynthesizer.ObjectGraph cloneGraph(
      ConstraintModelSynthesizer.ObjectGraph original,
      String oidPrefix) {
    Map<String, String> oidMap = new LinkedHashMap<>();
    int index = 0;
    for (ConstraintModelSynthesizer.GraphObject object : original.objects()) {
      oidMap.put(object.oid(), oidPrefix + "_" + (++index));
    }
    List<ConstraintModelSynthesizer.GraphObject> objects = original.objects().stream().map(object -> {
      Map<String, String> references = new LinkedHashMap<>();
      object.references().forEach((name, oid) -> references.put(name, oidMap.getOrDefault(oid, oid)));
      return new ConstraintModelSynthesizer.GraphObject(
          object.classFqn(),
          oidMap.get(object.oid()),
          object.values(),
          references);
    }).toList();
    List<ConstraintModelSynthesizer.GraphLink> links = original.links().stream().map(link -> {
      Map<String, String> roles = new LinkedHashMap<>();
      link.roles().forEach((role, oid) -> roles.put(role, oidMap.getOrDefault(oid, oid)));
      return new ConstraintModelSynthesizer.GraphLink(link.associationFqn(), roles);
    }).toList();
    return new ConstraintModelSynthesizer.ObjectGraph(objects, links);
  }

  private static Object summarize(Object value) {
    if (value == null || value == ConstraintExpressionEngine.Undefined.INSTANCE) {
      return "UNDEFINED";
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      map.forEach((key, item) -> result.put(String.valueOf(key), summarize(item)));
      return result;
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(UniqueConstraintCasePlanner::summarize).toList();
    }
    return value;
  }

  private static String nonBlank(@Nullable String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static final class BasketAllocator {
    private final CompiledConstraintContext context;
    private final Topic contextTopic;
    private final Map<String, Integer> otherTopicIndexes = new LinkedHashMap<>();

    private BasketAllocator(CompiledConstraintContext context) {
      this.context = context;
      Element contextElement = context.transferDescription().getElement(context.contextFqn());
      this.contextTopic = topicOf(contextElement);
    }

    private String forElement(String elementFqn, String scope) {
      Element element = context.transferDescription().getElement(elementFqn);
      Topic topic = topicOf(element);
      if (topic == contextTopic) {
        return scope;
      }
      int index = otherTopicIndexes.computeIfAbsent(topic.getScopedName(), key -> otherTopicIndexes.size() + 1);
      return scope + "_t" + index;
    }

    private static Topic topicOf(@Nullable Element element) {
      Element current = element;
      while (current != null) {
        if (current instanceof Topic topic) {
          return topic;
        }
        current = current.getContainer();
      }
      throw new IllegalArgumentException("UNIQUE fixture element is not contained in a TOPIC.");
    }
  }
}