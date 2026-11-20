package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.ExistenceConstraint;
import ch.interlis.ili2c.metamodel.PathElRefAttr;
import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.SemanticConstraint;
import ch.so.agi.mcp.model.IliAuthoringResult.CoverageGap;
import ch.so.agi.mcp.model.IliAuthoringResult.ProofCase;
import java.util.List;
import java.util.Objects;

/** Dispatches EXISTENCE proof planning between scalar B7 and special-type B8 semantics. */
final class ExistenceConstraintCasePlanner {

  record Plan(
      List<ConstraintTestTools.TestCase> cases,
      List<ProofCase> summaries,
      List<CoverageGap> unsolved) {

    Plan {
      cases = cases == null ? List.of() : List.copyOf(cases);
      summaries = summaries == null ? List.of() : List.copyOf(summaries);
      unsolved = unsolved == null ? List.of() : List.copyOf(unsolved);
      if (cases.size() != summaries.size()) {
        throw new IllegalArgumentException("EXISTENCE proof cases and summaries must have equal size.");
      }
    }
  }

  private ExistenceConstraintCasePlanner() {
  }

  static Plan plan(
      CompiledConstraintContext context,
      SemanticConstraint.Existence existence) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(existence, "existence");

    Plan reference = ExistenceConstraintReferenceCasePlanner.planIfReference(context, existence);
    if (reference != null) {
      return reference;
    }

    Plan special = ExistenceConstraintSpecialCasePlanner.planIfSpecial(context, existence);
    if (special != null) {
      return special;
    }
    return ExistenceConstraintScalarCasePlanner.plan(context, existence);
  }

}
