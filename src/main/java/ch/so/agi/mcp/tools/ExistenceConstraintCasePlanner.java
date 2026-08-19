package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.ExistenceConstraint;
import ch.interlis.ili2c.metamodel.PathElRefAttr;
import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.SemanticConstraint;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Dispatches EXISTENCE proof planning between scalar B7 and special-type B8 semantics. */
final class ExistenceConstraintCasePlanner {

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

  private ExistenceConstraintCasePlanner() {
  }

  static Plan plan(
      CompiledConstraintContext context,
      SemanticConstraint.Existence existence) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(existence, "existence");

    Plan referenceSafety = referenceSafetyGate(context, existence);
    if (referenceSafety != null) {
      return referenceSafety;
    }

    Plan special = ExistenceConstraintSpecialCasePlanner.planIfSpecial(context, existence);
    if (special != null) {
      return special;
    }
    return ExistenceConstraintScalarCasePlanner.plan(context, existence);
  }

  /**
   * ili2c represents a direct REFERENCE TO attribute endpoint as PathElRefAttr rather than
   * AttributeRef. Route that AST shape to the existing B8 no-fake-proof policy before the
   * structure/scalar dispatchers inspect direct AttributeRef endpoints.
   */
  private static Plan referenceSafetyGate(
      CompiledConstraintContext context,
      SemanticConstraint.Existence existence) {
    if (!(context.constraint() instanceof ExistenceConstraint raw)
        || raw.getRestrictedAttribute() == null
        || !(raw.getRestrictedAttribute().getLastPathEl() instanceof PathElRefAttr)) {
      return null;
    }
    return new Plan(
        List.of(),
        List.of(),
        List.of(Map.of(
            "reasonCode", "EXISTENCE_REFERENCE_VALUE_PROOF_UNSAFE",
            "reason", "REFERENCE-valued EXISTENCE is not automatically claimed as proven because the active validator comparison path is not value-discriminating enough for a safe equality counterexample.",
            "goal", existence.restrictedAttribute().rootFqn() + ":" + existence.restrictedAttribute().path())));
  }
}
