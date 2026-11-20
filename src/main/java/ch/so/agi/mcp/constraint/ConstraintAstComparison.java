package ch.so.agi.mcp.constraint;

import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.model.IliAuthoringResult.Status;
import ch.so.agi.mcp.model.IliConstraintSpec;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Compares typed constraint requests with semantics from the already compiled candidate. */
public final class ConstraintAstComparison {
  private ConstraintAstComparison() {}

  public static boolean verify(
      Map<String, IliConstraintSpec> requestedConstraints,
      Function<String, ConstraintContextService.Resolution> resolve,
      IliAuthoringResult result,
      String candidate) {
    if (requestedConstraints.isEmpty()) return true;

    boolean fullyVerified = true;
    for (var entry : requestedConstraints.entrySet()) {
      String fqn = entry.getKey();
      IliConstraintSpec requested = entry.getValue();
      var resolution = resolve.apply(fqn);
      if (requested.hasExternalFunctionSemantics() && !resolution.lookupFailed()) {
        // The existing proof stage reports external function semantics as required.
        fullyVerified = false;
        continue;
      }

      String mismatch;
      if (resolution.context() == null) {
        mismatch = Objects.requireNonNullElse(resolution.reason(), "Compiled constraint could not be resolved.");
        result.reasonCode = resolution.reasonCode();
      } else {
        mismatch = mismatch(requested, fqn.substring(0, fqn.lastIndexOf('.')),
            requested.name.trim(), resolution.context().semantics());
      }
      if (mismatch != null) {
        result.semanticDiff.astRoundTripVerified = false;
        result.reject(Status.AST_ROUND_TRIP_FAILED, fqn + ": " + mismatch, candidate);
        return false;
      }
    }
    result.semanticDiff.astRoundTripVerified = fullyVerified;
    return true;
  }

  private static @Nullable String mismatch(
      IliConstraintSpec requested,
      String context,
      String name,
      SemanticConstraint actual) {
    if (!context.equals(actual.contextFqn())) return "Compiled constraint context differs.";
    if (!name.equals(actual.constraintName())) return "Compiled constraint name differs.";
    if (!requested.kind().name().equals(actual.kind().name())) {
      return "Compiled constraint kind differs: " + actual.kind();
    }
    return switch (requested) {
      case IliConstraintSpec.Mandatory mandatory -> ConstraintExpressionComparison.matches(
          mandatory.condition, ((SemanticConstraint.Mandatory) actual).condition())
              ? null : "Compiled MANDATORY condition differs.";
      case IliConstraintSpec.Unique unique -> uniqueMismatch(
          unique, (SemanticConstraint.Unique) actual);
      case IliConstraintSpec.Existence existence ->
          existenceMismatch(existence, (SemanticConstraint.Existence) actual);
      case IliConstraintSpec.Plausibility plausibility ->
          plausibilityMismatch(
              plausibility, (SemanticConstraint.Plausibility) actual);
      case IliConstraintSpec.Set set -> setMismatch(
          set, (SemanticConstraint.Set) actual);
    };
  }

  private static @Nullable String uniqueMismatch(
      IliConstraintSpec.Unique spec,
      SemanticConstraint.Unique actual) {
    if ((spec.scope == IliConstraintSpec.UniqueScope.LOCAL) != actual.local()) {
      return "Compiled UNIQUE LOCAL scope differs.";
    }
    if ((spec.scope == IliConstraintSpec.UniqueScope.BASKET) != actual.perBasket()) {
      return "Compiled UNIQUE BASKET scope differs.";
    }
    if ((spec.where != null) != (actual.preCondition() != null)) {
      return "Compiled UNIQUE WHERE presence differs.";
    }
    if (spec.where != null && !ConstraintExpressionComparison.matches(
        spec.where, actual.preCondition())) {
      return "Compiled UNIQUE WHERE condition differs.";
    }
    if (actual.local() && (actual.prefix() == null
        || !spec.localPrefix.trim().equals(actual.prefix().path()))) {
      return "Compiled UNIQUE LOCAL prefix differs.";
    }
    List<String> keys = spec.keyPaths.stream().map(String::trim).toList();
    List<String> actualKeys = actual.elements().stream()
        .map(SemanticConstraint.ConstraintPath::path).toList();
    return keys.equals(actualKeys) ? null : "Compiled UNIQUE keys differ: " + actualKeys;
  }

  private static @Nullable String existenceMismatch(
      IliConstraintSpec.Existence spec, SemanticConstraint.Existence actual) {
    if (!spec.restrictedPath.trim().equals(actual.restrictedAttribute().path())) {
      return "Compiled EXISTENCE restricted path differs.";
    }
    List<String> requested = spec.requiredIn.stream()
        .map(target -> target.viewableFqn.trim() + ":" + target.attributePath.trim()).toList();
    List<String> compiled = actual.requiredIn().stream()
        .map(target -> target.rootFqn() + ":" + target.path()).toList();
    return requested.equals(compiled) ? null : "Compiled EXISTENCE targets differ: " + compiled;
  }

  private static @Nullable String plausibilityMismatch(
      IliConstraintSpec.Plausibility spec,
      SemanticConstraint.Plausibility actual) {
    if (!spec.direction.name().equals(actual.direction().name())) {
      return "Compiled PLAUSIBILITY direction differs.";
    }
    if (spec.percentage.compareTo(actual.percentage()) != 0) {
      return "Compiled PLAUSIBILITY percentage differs.";
    }
    return ConstraintExpressionComparison.matches(spec.condition, actual.condition())
        ? null : "Compiled PLAUSIBILITY condition differs.";
  }

  private static @Nullable String setMismatch(
      IliConstraintSpec.Set spec,
      SemanticConstraint.Set actual) {
    if ((spec.scope == IliConstraintSpec.SetScope.BASKET) != actual.perBasket()) {
      return "Compiled SET scope differs.";
    }
    if ((spec.where != null) != (actual.preCondition() != null)) {
      return "Compiled SET WHERE presence differs.";
    }
    if (spec.where != null && !ConstraintExpressionComparison.matches(
        spec.where, actual.preCondition())) {
      return "Compiled SET WHERE condition differs.";
    }
    if (spec.condition instanceof IliConstraintSpec.ObjectCountSetConditionSpec count) {
      if (!(actual.condition() instanceof SemanticConstraint.ObjectCountSetCondition compiled)) {
        return "Compiled SET condition is not objectCount.";
      }
      if (!count.operator.trim().equals(compiled.operator().interlis())) {
        return "Compiled SET objectCount operator differs.";
      }
      if (count.threshold.compareTo(compiled.threshold()) != 0) {
        return "Compiled SET objectCount threshold differs.";
      }
      if (count.objects instanceof IliConstraintSpec.AllObjectsSpec
          && !(compiled.objects() instanceof SemanticConstraint.AllObjects)) {
        return "Compiled SET object set differs from ALL.";
      }
      if (count.objects instanceof IliConstraintSpec.PathObjectsSpec path) {
        if (!(compiled.objects() instanceof SemanticConstraint.NavigatedObjects navigated)
            || !path.path.trim().equals(navigated.path().path())) {
          return "Compiled SET navigated object path differs.";
        }
      }
    } else if (spec.condition instanceof IliConstraintSpec.BooleanSetConditionSpec bool) {
      if (!(actual.condition() instanceof SemanticConstraint.ValueSetCondition value)) {
        return "Compiled SET condition is not a typed boolean expression.";
      }
      if (!ConstraintExpressionComparison.matches(bool.expression, value.expression())) {
        return "Compiled SET boolean condition differs.";
      }
    }
    return null;
  }
}
