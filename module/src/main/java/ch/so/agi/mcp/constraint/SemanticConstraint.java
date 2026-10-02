package ch.so.agi.mcp.constraint;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Typed semantic representation of an INTERLIS constraint at constraint level.
 *
 * <p>{@link ConstraintExpression} remains the value/expression IR used by Mandatory constraints and
 * reusable boolean subexpressions. This layer captures the semantics that live above an expression:
 * uniqueness scope, existence targets, plausibility percentage/direction and set scope.</p>
 */
public sealed interface SemanticConstraint
    permits SemanticConstraint.Mandatory,
        SemanticConstraint.Unique,
        SemanticConstraint.Existence,
        SemanticConstraint.Plausibility,
        SemanticConstraint.Set {

  Kind kind();

  String constraintName();

  String constraintScopedName();

  String contextFqn();

  ConstraintExpression.IliVersion version();

  enum Kind {
    MANDATORY,
    UNIQUE,
    EXISTENCE,
    PLAUSIBILITY,
    SET
  }

  enum PlausibilityDirection {
    AT_LEAST,
    AT_MOST
  }

  /**
   * Semantic object/attribute path with the ili2c root preserved separately from surface path text.
   *
   * <p>This distinction is essential for EXISTENCE: two paths can both be spelled {@code code} but
   * belong to different viewables.</p>
   */
  record ConstraintPath(
      String rootFqn,
      String path,
      boolean attributePath,
      @Nullable String targetViewableFqn,
      ConstraintExpression.Type endpointType) {

    public ConstraintPath {
      rootFqn = requireText(rootFqn, "rootFqn");
      path = requireText(path, "path");
      Objects.requireNonNull(endpointType, "endpointType");
      if (targetViewableFqn != null && targetViewableFqn.isBlank()) {
        targetViewableFqn = null;
      }
    }
  }

  record Mandatory(
      String constraintName,
      String constraintScopedName,
      String contextFqn,
      ConstraintExpression.IliVersion version,
      ConstraintExpression condition) implements SemanticConstraint {

    public Mandatory {
      constraintName = requireText(constraintName, "constraintName");
      constraintScopedName = requireText(constraintScopedName, "constraintScopedName");
      contextFqn = requireText(contextFqn, "contextFqn");
      Objects.requireNonNull(version, "version");
      requireBoolean(condition, "Mandatory condition");
    }

    @Override
    public Kind kind() {
      return Kind.MANDATORY;
    }
  }

  record Unique(
      String constraintName,
      String constraintScopedName,
      String contextFqn,
      ConstraintExpression.IliVersion version,
      boolean local,
      boolean perBasket,
      @Nullable ConstraintExpression preCondition,
      @Nullable ConstraintPath prefix,
      List<ConstraintPath> elements) implements SemanticConstraint {

    public Unique {
      constraintName = requireText(constraintName, "constraintName");
      constraintScopedName = requireText(constraintScopedName, "constraintScopedName");
      contextFqn = requireText(contextFqn, "contextFqn");
      Objects.requireNonNull(version, "version");
      if (preCondition != null) {
        requireBoolean(preCondition, "Unique preCondition");
      }
      elements = elements == null ? List.of() : List.copyOf(elements);
      if (elements.isEmpty()) {
        throw new IllegalArgumentException("Unique semantics requires at least one unique element.");
      }
      if (local && prefix == null) {
        throw new IllegalArgumentException("LOCAL unique semantics requires a prefix path.");
      }
      if (!local && prefix != null) {
        throw new IllegalArgumentException("Non-local unique semantics must not have a prefix path.");
      }
    }

    @Override
    public Kind kind() {
      return Kind.UNIQUE;
    }
  }

  record Existence(
      String constraintName,
      String constraintScopedName,
      String contextFqn,
      ConstraintExpression.IliVersion version,
      ConstraintPath restrictedAttribute,
      List<ConstraintPath> requiredIn) implements SemanticConstraint {

    public Existence {
      constraintName = requireText(constraintName, "constraintName");
      constraintScopedName = requireText(constraintScopedName, "constraintScopedName");
      contextFqn = requireText(contextFqn, "contextFqn");
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(restrictedAttribute, "restrictedAttribute");
      requiredIn = requiredIn == null ? List.of() : List.copyOf(requiredIn);
      if (!restrictedAttribute.attributePath()) {
        throw new IllegalArgumentException("Existence restricted path must end in an attribute.");
      }
      if (requiredIn.isEmpty()) {
        throw new IllegalArgumentException("Existence semantics requires at least one REQUIRED IN target.");
      }
      if (requiredIn.stream().anyMatch(path -> !path.attributePath())) {
        throw new IllegalArgumentException("Existence REQUIRED IN paths must end in attributes.");
      }
    }

    @Override
    public Kind kind() {
      return Kind.EXISTENCE;
    }
  }

  record Plausibility(
      String constraintName,
      String constraintScopedName,
      String contextFqn,
      ConstraintExpression.IliVersion version,
      PlausibilityDirection direction,
      BigDecimal percentage,
      ConstraintExpression condition) implements SemanticConstraint {

    public Plausibility {
      constraintName = requireText(constraintName, "constraintName");
      constraintScopedName = requireText(constraintScopedName, "constraintScopedName");
      contextFqn = requireText(contextFqn, "contextFqn");
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(direction, "direction");
      Objects.requireNonNull(percentage, "percentage");
      if (percentage.compareTo(BigDecimal.ZERO) < 0
          || percentage.compareTo(BigDecimal.valueOf(100)) > 0) {
        throw new IllegalArgumentException("Plausibility percentage must be between 0 and 100.");
      }
      requireBoolean(condition, "Plausibility condition");
    }

    @Override
    public Kind kind() {
      return Kind.PLAUSIBILITY;
    }
  }

  /** Object-set expression used as an actual argument to an OBJECTS OF function parameter. */
  sealed interface ObjectSetExpression permits AllObjects, NavigatedObjects {
  }

  /**
   * Typed form of ili2c's {@code Objects} AST node, rendered by INTERLIS as {@code ALL}.
   *
   * <p>The base and RESTRICTION metadata are preserved even though the first automatic proof slice
   * intentionally supports only plain ALL. This avoids losing semantic information while keeping
   * proof support conservative.</p>
   */
  record AllObjects(
      String contextFqn,
      @Nullable String baseFqn,
      List<String> restrictedToFqns) implements ObjectSetExpression {

    public AllObjects {
      contextFqn = requireText(contextFqn, "ALL contextFqn");
      if (baseFqn != null && baseFqn.isBlank()) {
        baseFqn = null;
      }
      restrictedToFqns = restrictedToFqns == null ? List.of() : List.copyOf(restrictedToFqns);
      if (restrictedToFqns.stream().anyMatch(value -> value == null || value.isBlank())) {
        throw new IllegalArgumentException("ALL restrictedToFqns must contain only non-empty names.");
      }
    }

    public boolean plain() {
      return baseFqn == null && restrictedToFqns.isEmpty();
    }
  }

  /** A compiled, typed object path used as the source of an OBJECTS OF argument. */
  record NavigatedObjects(ConstraintPath path) implements ObjectSetExpression {
    public NavigatedObjects {
      Objects.requireNonNull(path, "path");
      if (path.attributePath()) {
        throw new IllegalArgumentException("Navigated object sets must end in objects, not attributes.");
      }
    }
  }

  /** Base for SET-condition semantics. */
  sealed interface SetCondition
      permits ValueSetCondition, ObjectCountSetCondition, UntranslatedSetCondition {
  }

  /** A SET condition that already fits the existing scalar/value expression IR. */
  record ValueSetCondition(ConstraintExpression expression) implements SetCondition {
    public ValueSetCondition {
      requireBoolean(expression, "Set condition");
    }
  }

  /**
   * Proof-capable SET subset: comparison of {@code INTERLIS.objectCount(ALL)} with a numeric value.
   */
  record ObjectCountSetCondition(
      ObjectSetExpression objects,
      ConstraintExpression.ComparisonOperator operator,
      BigDecimal threshold) implements SetCondition {

    public ObjectCountSetCondition {
      Objects.requireNonNull(objects, "objects");
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(threshold, "threshold");
      threshold = threshold.stripTrailingZeros();
    }
  }

  /**
   * Explicit placeholder for SET-only AST nodes outside the currently executable SET IR.
   *
   * <p>This remains intentionally non-proof-capable. Unknown functions and geometry-aware SET
   * semantics are surfaced explicitly instead of being approximated.</p>
   */
  record UntranslatedSetCondition(
      String reasonCode,
      String metamodelType,
      String text) implements SetCondition {

    public UntranslatedSetCondition {
      reasonCode = requireText(reasonCode, "reasonCode");
      metamodelType = requireText(metamodelType, "metamodelType");
      text = text == null ? "" : text;
    }
  }

  record Set(
      String constraintName,
      String constraintScopedName,
      String contextFqn,
      ConstraintExpression.IliVersion version,
      boolean perBasket,
      @Nullable ConstraintExpression preCondition,
      SetCondition condition) implements SemanticConstraint {

    public Set {
      constraintName = requireText(constraintName, "constraintName");
      constraintScopedName = requireText(constraintScopedName, "constraintScopedName");
      contextFqn = requireText(contextFqn, "contextFqn");
      Objects.requireNonNull(version, "version");
      if (preCondition != null) {
        requireBoolean(preCondition, "Set preCondition");
      }
      Objects.requireNonNull(condition, "condition");
    }

    @Override
    public Kind kind() {
      return Kind.SET;
    }
  }

  private static ConstraintExpression requireBoolean(
      ConstraintExpression expression,
      String label) {
    Objects.requireNonNull(expression, label);
    if (!expression.type().isScalar(ConstraintExpression.ScalarKind.BOOLEAN)) {
      throw new IllegalArgumentException(label + " must have BOOLEAN type, got " + expression.type() + ".");
    }
    return expression;
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " is required.");
    }
    return value.trim();
  }
}
