package ch.so.agi.mcp.constraint;

import ch.interlis.ili2c.metamodel.*;
import ch.so.agi.mcp.constraint.ConstraintExpression.*;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine.EvaluationContext;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine.Undefined;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer.ReferenceBinding;
import ch.so.agi.mcp.model.EvidenceSummary;
import java.math.BigDecimal;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Advisory analysis only. Never changes authoring/review gates or claims whole-model consistency. */
public final class ConstraintInteractionAnalysis {
  public static final int MAX_STATES = 50_000;
  public enum Status { CONTRADICTION_PROVEN, SCALAR_ASSIGNMENT_FOUND, UNKNOWN, NOT_APPLICABLE }
  public record Gap(String constraintFqn, String reasonCode) {}
  public record Domain(String attribute, String kind, boolean mandatory, @Nullable BigDecimal minimum,
      @Nullable BigDecimal maximum, @Nullable BigDecimal step, List<String> enumValues) {}
  public record ContextResult(String contextFqn, Status status, List<String> constraintFqns,
      List<Gap> unsupported, List<Domain> domains, int examinedStates,
      Map<String,Object> scalarAssignment, List<String> undefinedAttributes, String reasonCode) {}
  public record Result(boolean analyzed, int stateBudget, int examinedStates, List<ContextResult> contexts,
      String scope, String limitation) {
    public EvidenceSummary.Check evidence() {
      long contradictions = contexts.stream().filter(c -> c.status == Status.CONTRADICTION_PROVEN).count();
      boolean incomplete = contexts.stream().anyMatch(c -> c.status == Status.UNKNOWN || !c.unsupported.isEmpty());
      boolean applicable = contexts.stream().anyMatch(c -> c.status != Status.NOT_APPLICABLE);
      return EvidenceSummary.check(!analyzed ? EvidenceSummary.Status.NOT_RUN
          : contradictions > 0 ? EvidenceSummary.Status.FAILED : incomplete ? EvidenceSummary.Status.INCOMPLETE
          : applicable ? EvidenceSummary.Status.PASSED : EvidenceSummary.Status.NOT_APPLICABLE,
          scope, "EXHAUSTIVE_SCALAR_PARTITIONS", (int) contexts.stream().filter(c -> c.examinedStates > 0).count(), (int) contradictions, 0,
          "NOT_A_VALIDATED_OBJECT_GRAPH_OR_WHOLE_MODEL_PROOF");
    }
  }
  private record Rule(String fqn, ConstraintExpression expression) {}
  private ConstraintInteractionAnalysis() {}

  public static Result analyze(@Nullable TransferDescription td) { return analyze(td, MAX_STATES); }
  static Result analyze(@Nullable TransferDescription td, int budget) {
    if (budget < 0 || budget > MAX_STATES) throw new IllegalArgumentException("Invalid state budget");
    List<ContextResult> results = new ArrayList<>();
    int examined = 0;
    if (td != null) {
      List<Viewable<?>> contexts = new ArrayList<>();
      for (Model model : td.getModelsFromLastFile()) collect(model, contexts);
      contexts.sort(Comparator.comparing(Element::getScopedName));
      for (Viewable<?> context : contexts) {
        ContextResult result = analyzeContext(td, context, budget - examined);
        results.add(result);
        examined += result.examinedStates;
      }
    }
    return new Result(td != null, budget, examined, List.copyOf(results), "SUPPORTED_SCALAR_MANDATORY_RULES",
        "Separate Mandatory rules are checked for one object's scalar values. A scalar assignment is not a validated object graph. "
        + "A contradiction prevents objects in the checked context, not necessarily an empty transfer.");
  }
  private static void collect(Container<?> container, List<Viewable<?>> result) {
    for (var it = container.iterator(); it.hasNext();) {
      Object item = it.next();
      if (item instanceof Viewable<?> viewable) result.add(viewable);
      if (item instanceof Container<?> child) collect(child, result);
    }
  }
  private static ContextResult analyzeContext(TransferDescription td, Viewable<?> context, int budget) {
    List<Constraint> constraints = new ArrayList<>();
    for (var it = context.iterator(); it.hasNext();) {
      if (it.next() instanceof Constraint constraint) constraints.add(constraint);
    }
    constraints.sort(Comparator.comparing(Element::getScopedName));
    String unsupportedContext = !(context instanceof Table table) ? "CONTEXT_KIND_UNSUPPORTED"
        : !table.isIdentifiable() ? "STRUCTURE_CONTEXT_UNSUPPORTED"
        : table.isAbstract() ? "ABSTRACT_CONTEXT_UNSUPPORTED"
        : table.getExtending() != null ? "INHERITANCE_UNSUPPORTED" : null;
    if (unsupportedContext != null) {
      return new ContextResult(context.getScopedName(), Status.UNKNOWN, List.of(),
          constraints.isEmpty() ? List.of(new Gap(context.getScopedName(), unsupportedContext))
              : constraints.stream().map(c -> new Gap(c.getScopedName(), unsupportedContext)).toList(),
          List.of(), 0, Map.of(), List.of(), unsupportedContext);
    }
    List<Rule> rules = new ArrayList<>();
    List<Gap> gaps = new ArrayList<>();
    SortedMap<String, ReferenceBinding> references = new TreeMap<>();
    Set<BigDecimal> pivots = new LinkedHashSet<>();
    for (Constraint constraint : constraints) {
      if (!(constraint instanceof MandatoryConstraint)) {
        gaps.add(new Gap(constraint.getScopedName(), "CONSTRAINT_KIND_UNSUPPORTED"));
        continue;
      }
      try {
        var expression = ((SemanticConstraint.Mandatory) ConstraintSemanticTranslator.translate(constraint)).condition();
        if (!directScalar(expression) || !ConstraintGoalReachability.supported(expression)) {
          gaps.add(new Gap(constraint.getScopedName(), "EXPRESSION_UNSUPPORTED"));
          continue;
        }
        var bound = ConstraintModelSynthesizer.bind(td, context.getScopedName(), expression);
        // Binding is compiler-derived; no inferred navigation or type coercion is allowed.
        references.putAll(bound.references());
        ScalarTruthPartitions.collectPivots(expression, pivots);
        rules.add(new Rule(constraint.getScopedName(), expression));
      } catch (IllegalArgumentException ex) {
        gaps.add(new Gap(constraint.getScopedName(), "SCALAR_BINDING_UNAVAILABLE"));
      }
    }
    List<String> fqns = rules.stream().map(Rule::fqn).toList();
    if (rules.isEmpty()) return new ContextResult(context.getScopedName(), gaps.isEmpty() ? Status.NOT_APPLICABLE : Status.UNKNOWN,
        fqns, List.copyOf(gaps), List.of(), 0, Map.of(), List.of(), gaps.isEmpty() ? "NO_MANDATORY_RULES" : "NO_SUPPORTED_RULES");
    List<Domain> domains = new ArrayList<>();
    List<List<Object>> representatives = new ArrayList<>();
    for (var entry : references.entrySet()) {
      var domain = entry.getValue().domain();
      var numeric = domain.numeric();
      domains.add(new Domain(entry.getKey(), domain.kind().name(), domain.mandatory(),
          numeric == null ? null : numeric.minimum(), numeric == null ? null : numeric.maximum(),
          numeric == null ? null : numeric.step(), domain.values()));
      List<Object> values = ScalarTruthPartitions.representatives(entry.getValue(), pivots);
      if (values == null || values.isEmpty()) return new ContextResult(context.getScopedName(), Status.UNKNOWN,
          fqns, List.copyOf(gaps), List.copyOf(domains), 0, Map.of(), List.of(), "PARTITION_UNAVAILABLE");
      representatives.add(values);
    }
    // Iterative Cartesian enumeration avoids recursion proportional to the attribute count.
    int[] indexes = new int[domains.size()];
    int examined = 0;
    boolean exhausted = false;
    while (!exhausted && examined < budget) {
      Map<String,Object> assignment = new LinkedHashMap<>();
      for (int i = 0; i < indexes.length; i++) assignment.put(domains.get(i).attribute, representatives.get(i).get(indexes[i]));
      examined++;
      var evaluation = EvaluationContext.of(assignment);
      // Crucial: evaluate each constraint separately. UNDEFINED in one cannot hide FALSE in another.
      boolean accepts = rules.stream().allMatch(r -> ConstraintExpressionEngine.evaluateConstraint(r.expression, evaluation));
      if (accepts) {
        List<String> undefined = assignment.entrySet().stream().filter(e -> e.getValue() == Undefined.INSTANCE).map(Map.Entry::getKey).toList();
        undefined.forEach(assignment::remove);
        return new ContextResult(context.getScopedName(), Status.SCALAR_ASSIGNMENT_FOUND, fqns, List.copyOf(gaps),
            List.copyOf(domains), examined, Collections.unmodifiableMap(assignment), undefined,
            gaps.isEmpty() ? "SCALAR_ASSIGNMENT_ONLY" : "SUPPORTED_SUBSET_ONLY");
      }
      exhausted = true;
      for (int i = indexes.length - 1; i >= 0; i--) {
        if (++indexes[i] < representatives.get(i).size()) { exhausted = false; break; }
        indexes[i] = 0;
      }
    }
    return new ContextResult(context.getScopedName(), exhausted ? Status.CONTRADICTION_PROVEN : Status.UNKNOWN,
        fqns, List.copyOf(gaps), List.copyOf(domains), examined, Map.of(), List.of(),
        exhausted ? "EXHAUSTIVE_SCALAR_CONTRADICTION" : "STATE_BUDGET_EXCEEDED");
  }
  private static boolean directScalar(ConstraintExpression expression) {
    return switch (expression) {
      case Attribute ignored -> true;
      case NumericLiteral ignored -> true;
      case BooleanLiteral ignored -> true;
      case EnumLiteral ignored -> true;
      case Defined d -> directScalar(d.operand());
      case Not n -> directScalar(n.operand());
      case And a -> a.operands().stream().allMatch(ConstraintInteractionAnalysis::directScalar);
      case Or o -> o.operands().stream().allMatch(ConstraintInteractionAnalysis::directScalar);
      case Comparison c -> directScalar(c.left()) && directScalar(c.right());
      default -> false;
    };
  }
}
