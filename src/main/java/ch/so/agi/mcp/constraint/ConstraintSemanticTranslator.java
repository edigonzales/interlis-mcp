package ch.so.agi.mcp.constraint;

import ch.interlis.ili2c.metamodel.AbstractCoordType;
import ch.interlis.ili2c.metamodel.AttributeRef;
import ch.interlis.ili2c.metamodel.CompositionType;
import ch.interlis.ili2c.metamodel.Constant;
import ch.interlis.ili2c.metamodel.Constraint;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.EnumTreeValueType;
import ch.interlis.ili2c.metamodel.EnumerationType;
import ch.interlis.ili2c.metamodel.Evaluable;
import ch.interlis.ili2c.metamodel.ExistenceConstraint;
import ch.interlis.ili2c.metamodel.Expression;
import ch.interlis.ili2c.metamodel.FunctionCall;
import ch.interlis.ili2c.metamodel.LineType;
import ch.interlis.ili2c.metamodel.MandatoryConstraint;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.ObjectPath;
import ch.interlis.ili2c.metamodel.PathElRefAttr;
import ch.interlis.ili2c.metamodel.PlausibilityConstraint;
import ch.interlis.ili2c.metamodel.SetConstraint;
import ch.interlis.ili2c.metamodel.TextType;
import ch.interlis.ili2c.metamodel.Type;
import ch.interlis.ili2c.metamodel.UniquenessConstraint;
import ch.interlis.ili2c.metamodel.Viewable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Translates compiled ili2c constraint nodes into the constraint-level semantic IR. */
public final class ConstraintSemanticTranslator {

  public static final class TranslationException extends IllegalArgumentException {
    private final String reasonCode;

    TranslationException(String reasonCode, String message) {
      super(message);
      this.reasonCode = reasonCode;
    }

    public String reasonCode() {
      return reasonCode;
    }
  }

  private ConstraintSemanticTranslator() {
  }

  public static SemanticConstraint translate(Constraint constraint) {
    Objects.requireNonNull(constraint, "constraint");
    ConstraintExpression.IliVersion version = iliVersion(constraint);
    Metadata metadata = metadata(constraint, version);

    if (constraint instanceof MandatoryConstraint mandatory) {
      return translateMandatory(mandatory, metadata);
    }
    if (constraint instanceof UniquenessConstraint unique) {
      return translateUnique(unique, metadata);
    }
    if (constraint instanceof ExistenceConstraint existence) {
      return translateExistence(existence, metadata);
    }
    if (constraint instanceof PlausibilityConstraint plausibility) {
      return translatePlausibility(plausibility, metadata);
    }
    if (constraint instanceof SetConstraint setConstraint) {
      return translateSet(setConstraint, metadata);
    }
    throw new TranslationException(
        "UNSUPPORTED_CONSTRAINT_KIND",
        "Constraint-level semantic translation does not support "
            + constraint.getClass().getSimpleName() + ".");
  }

  private static SemanticConstraint.Mandatory translateMandatory(
      MandatoryConstraint constraint,
      Metadata metadata) {
    ConstraintExpression condition = translateRequiredCondition(
        constraint.getCondition(), metadata.version(), "MANDATORY");
    return new SemanticConstraint.Mandatory(
        metadata.name(),
        metadata.scopedName(),
        metadata.contextFqn(),
        metadata.version(),
        condition);
  }

  private static SemanticConstraint.Unique translateUnique(
      UniquenessConstraint constraint,
      Metadata metadata) {
    ConstraintExpression preCondition = translateOptional(
        constraint.getPreCondition(), metadata.version());
    SemanticConstraint.ConstraintPath prefix = constraint.getPrefix() != null
        ? path(constraint.getPrefix())
        : null;

    if (constraint.getElements() == null || constraint.getElements().getAttributes() == null) {
      throw new TranslationException(
          "MISSING_UNIQUE_ELEMENTS",
          "Uniqueness constraint has no unique elements: " + metadata.scopedName());
    }
    List<SemanticConstraint.ConstraintPath> elements = new ArrayList<>();
    for (ObjectPath element : constraint.getElements().getAttributes()) {
      elements.add(path(element));
    }

    return new SemanticConstraint.Unique(
        metadata.name(),
        metadata.scopedName(),
        metadata.contextFqn(),
        metadata.version(),
        constraint.getLocal(),
        constraint.perBasket(),
        preCondition,
        prefix,
        elements);
  }

  private static SemanticConstraint.Existence translateExistence(
      ExistenceConstraint constraint,
      Metadata metadata) {
    if (constraint.getRestrictedAttribute() == null) {
      throw new TranslationException(
          "MISSING_EXISTENCE_RESTRICTED_ATTRIBUTE",
          "Existence constraint has no restricted attribute: " + metadata.scopedName());
    }
    List<SemanticConstraint.ConstraintPath> requiredIn = new ArrayList<>();
    Iterator<ObjectPath> iterator = constraint.iteratorRequiredIn();
    while (iterator.hasNext()) {
      requiredIn.add(path(iterator.next()));
    }
    return new SemanticConstraint.Existence(
        metadata.name(),
        metadata.scopedName(),
        metadata.contextFqn(),
        metadata.version(),
        path(constraint.getRestrictedAttribute()),
        requiredIn);
  }

  private static SemanticConstraint.Plausibility translatePlausibility(
      PlausibilityConstraint constraint,
      Metadata metadata) {
    ConstraintExpression condition = translateRequiredCondition(
        constraint.getCondition(), metadata.version(), "PLAUSIBILITY");
    SemanticConstraint.PlausibilityDirection direction = switch (constraint.getDirection()) {
      case PlausibilityConstraint.DIRECTION_AT_LEAST ->
          SemanticConstraint.PlausibilityDirection.AT_LEAST;
      case PlausibilityConstraint.DIRECTION_AT_MOST ->
          SemanticConstraint.PlausibilityDirection.AT_MOST;
      default -> throw new TranslationException(
          "INVALID_PLAUSIBILITY_DIRECTION",
          "Unknown ili2c plausibility direction: " + constraint.getDirection());
    };
    return new SemanticConstraint.Plausibility(
        metadata.name(),
        metadata.scopedName(),
        metadata.contextFqn(),
        metadata.version(),
        direction,
        BigDecimal.valueOf(constraint.getPercentage()).stripTrailingZeros(),
        condition);
  }

  private static SemanticConstraint.Set translateSet(
      SetConstraint constraint,
      Metadata metadata) {
    ConstraintExpression preCondition = translateOptional(
        constraint.getPreCondition(), metadata.version());
    SemanticConstraint.SetCondition condition = translateSetCondition(
        constraint.getCondition(), metadata.version(), metadata.scopedName());
    return new SemanticConstraint.Set(
        metadata.name(),
        metadata.scopedName(),
        metadata.contextFqn(),
        metadata.version(),
        constraint.perBasket(),
        preCondition,
        condition);
  }

  private static SemanticConstraint.SetCondition translateSetCondition(
      @Nullable Evaluable condition,
      ConstraintExpression.IliVersion version,
      String scopedName) {
    if (condition == null) {
      throw new TranslationException(
          "MISSING_SET_CONDITION",
          "Set constraint has no condition: " + scopedName);
    }

    SemanticConstraint.ObjectCountSetCondition objectCount = translateObjectCountCondition(condition);
    if (objectCount != null) {
      return objectCount;
    }

    try {
      return new SemanticConstraint.ValueSetCondition(
          ConstraintAstTranslator.translate(condition, version));
    } catch (ConstraintAstTranslator.TranslationException ex) {
      return new SemanticConstraint.UntranslatedSetCondition(
          ex.reasonCode(),
          condition.getClass().getName(),
          condition.toString());
    }
  }

  private static SemanticConstraint.ObjectCountSetCondition translateObjectCountCondition(
      Evaluable condition) {
    ComparisonParts comparison = comparisonParts(condition);
    if (comparison == null) {
      return null;
    }

    ObjectCountCall left = objectCountCall(comparison.left());
    BigDecimal right = numericConstant(comparison.right());
    if (left != null && right != null) {
      return new SemanticConstraint.ObjectCountSetCondition(
          objectSet(left.objects()), comparison.operator(), right);
    }

    ObjectCountCall rightCall = objectCountCall(comparison.right());
    BigDecimal leftConstant = numericConstant(comparison.left());
    if (rightCall != null && leftConstant != null) {
      return new SemanticConstraint.ObjectCountSetCondition(
          objectSet(rightCall.objects()), reverse(comparison.operator()), leftConstant);
    }
    return null;
  }

  private static @Nullable ObjectCountCall objectCountCall(Evaluable expression) {
    if (!(expression instanceof FunctionCall call)
        || call.getFunction() == null
        || !"INTERLIS.objectCount".equals(call.getFunction().getScopedName())) {
      return null;
    }
    Evaluable[] arguments = call.getArguments();
    if (arguments == null || arguments.length != 1
        || (!(arguments[0] instanceof ch.interlis.ili2c.metamodel.Objects)
            && !(arguments[0] instanceof ObjectPath))) {
      return null;
    }
    return new ObjectCountCall(arguments[0]);
  }

  private static SemanticConstraint.ObjectSetExpression objectSet(Evaluable expression) {
    if (expression instanceof ch.interlis.ili2c.metamodel.Objects objects) {
      return allObjects(objects);
    }
    if (expression instanceof ObjectPath path) {
      return new SemanticConstraint.NavigatedObjects(path(path));
    }
    throw new TranslationException(
        "SET_OBJECT_SET_EXPRESSION_UNSUPPORTED",
        "Unsupported object-set AST node: " + expression.getClass().getName());
  }

  private static SemanticConstraint.AllObjects allObjects(
      ch.interlis.ili2c.metamodel.Objects objects) {
    Viewable<?> context = objects.getContext();
    if (context == null || context.getScopedName() == null || context.getScopedName().isBlank()) {
      throw new TranslationException(
          "SET_ALL_CONTEXT_MISSING",
          "ili2c Objects/ALL node has no semantic context.");
    }
    String baseFqn = objects.getBase() != null ? objects.getBase().getScopedName() : null;
    List<String> restrictedTo = new ArrayList<>();
    Iterator<?> iterator = objects.iteratorRestrictedTo();
    while (iterator.hasNext()) {
      Object candidate = iterator.next();
      if (!(candidate instanceof Viewable<?> viewable)
          || viewable.getScopedName() == null
          || viewable.getScopedName().isBlank()) {
        throw new TranslationException(
            "SET_ALL_RESTRICTION_INVALID",
            "ili2c Objects/ALL node contains an invalid RESTRICTION target.");
      }
      restrictedTo.add(viewable.getScopedName());
    }
    return new SemanticConstraint.AllObjects(
        context.getScopedName(),
        baseFqn,
        restrictedTo);
  }

  private static @Nullable BigDecimal numericConstant(Evaluable expression) {
    if (!(expression instanceof Constant.Numeric numeric)) {
      return null;
    }
    return new BigDecimal(numeric.getValue().toString()).stripTrailingZeros();
  }

  private static @Nullable ComparisonParts comparisonParts(Evaluable condition) {
    if (condition instanceof Expression.Equality expression) {
      return new ComparisonParts(
          ConstraintExpression.ComparisonOperator.EQ,
          expression.getLeft(),
          expression.getRight());
    }
    if (condition instanceof Expression.Inequality expression) {
      return new ComparisonParts(
          ConstraintExpression.ComparisonOperator.NE,
          expression.getLeft(),
          expression.getRight());
    }
    if (condition instanceof Expression.GreaterThan expression) {
      return new ComparisonParts(
          ConstraintExpression.ComparisonOperator.GT,
          expression.getLeft(),
          expression.getRight());
    }
    if (condition instanceof Expression.GreaterThanOrEqual expression) {
      return new ComparisonParts(
          ConstraintExpression.ComparisonOperator.GE,
          expression.getLeft(),
          expression.getRight());
    }
    if (condition instanceof Expression.LessThan expression) {
      return new ComparisonParts(
          ConstraintExpression.ComparisonOperator.LT,
          expression.getLeft(),
          expression.getRight());
    }
    if (condition instanceof Expression.LessThanOrEqual expression) {
      return new ComparisonParts(
          ConstraintExpression.ComparisonOperator.LE,
          expression.getLeft(),
          expression.getRight());
    }
    return null;
  }

  private static ConstraintExpression.ComparisonOperator reverse(
      ConstraintExpression.ComparisonOperator operator) {
    return switch (operator) {
      case EQ -> ConstraintExpression.ComparisonOperator.EQ;
      case NE -> ConstraintExpression.ComparisonOperator.NE;
      case LT -> ConstraintExpression.ComparisonOperator.GT;
      case LE -> ConstraintExpression.ComparisonOperator.GE;
      case GT -> ConstraintExpression.ComparisonOperator.LT;
      case GE -> ConstraintExpression.ComparisonOperator.LE;
    };
  }

  private static @Nullable ConstraintExpression translateOptional(
      @Nullable Evaluable expression,
      ConstraintExpression.IliVersion version) {
    return expression == null ? null : ConstraintAstTranslator.translate(expression, version);
  }

  private static ConstraintExpression translateRequiredCondition(
      @Nullable Evaluable condition,
      ConstraintExpression.IliVersion version,
      String kind) {
    if (condition == null) {
      throw new TranslationException(
          "MISSING_CONSTRAINT_CONDITION",
          kind + " constraint has no condition.");
    }
    ConstraintExpression translated = ConstraintAstTranslator.translate(condition, version);
    if (!translated.type().isScalar(ConstraintExpression.ScalarKind.BOOLEAN)) {
      throw new TranslationException(
          "NON_BOOLEAN_CONSTRAINT_CONDITION",
          kind + " condition did not translate to BOOLEAN: " + translated.type());
    }
    return translated;
  }

  private static SemanticConstraint.ConstraintPath path(ObjectPath path) {
    Objects.requireNonNull(path, "path");
    String rootFqn = path.getRoot() != null ? path.getRoot().getScopedName() : null;
    if (rootFqn == null || rootFqn.isBlank()) {
      throw new TranslationException(
          "MISSING_OBJECT_PATH_ROOT",
          "Object path has no semantic root: " + path);
    }
    return new SemanticConstraint.ConstraintPath(
        rootFqn,
        path.toString(),
        attributeEndpoint(path),
        targetViewableFqn(path),
        endpointType(path));
  }

  private static boolean attributeEndpoint(ObjectPath path) {
    return path.isAttributePath() || path.getLastPathEl() instanceof PathElRefAttr;
  }

  private static @Nullable String targetViewableFqn(ObjectPath path) {
    if (!path.isAttributePath()) {
      return path.getViewable() != null ? path.getViewable().getScopedName() : null;
    }
    if (path.getLastPathEl() instanceof AttributeRef attributeRef) {
      Type real = Type.findReal(attributeRef.getAttr().getDomainOrDerivedDomain());
      if (real instanceof CompositionType composition) {
        return composition.getComponentType().getScopedName();
      }
    }
    return null;
  }

  private static ConstraintExpression.Type endpointType(ObjectPath path) {
    Type declared = path.getType();
    if (declared == null) {
      return ConstraintExpression.Type.optionalScalar(ConstraintExpression.ScalarKind.UNKNOWN);
    }
    Type real = Type.findReal(declared);
    ConstraintExpression.ScalarKind kind;
    if (real instanceof NumericType) {
      kind = ConstraintExpression.ScalarKind.NUMERIC;
    } else if (declared.isBoolean() || real.isBoolean()) {
      kind = ConstraintExpression.ScalarKind.BOOLEAN;
    } else if (real instanceof EnumerationType || real instanceof EnumTreeValueType) {
      kind = ConstraintExpression.ScalarKind.ENUM;
    } else if (real instanceof TextType text) {
      kind = text.isNormalized()
          ? ConstraintExpression.ScalarKind.TEXT
          : ConstraintExpression.ScalarKind.MTEXT;
    } else if (real instanceof AbstractCoordType || real instanceof LineType) {
      kind = ConstraintExpression.ScalarKind.GEOMETRY;
    } else {
      kind = ConstraintExpression.ScalarKind.UNKNOWN;
    }
    boolean nullable = attributeEndpoint(path) && !declared.isMandatoryConsideringAliases();
    return new ConstraintExpression.Type(kind, false, nullable);
  }

  private static Metadata metadata(
      Constraint constraint,
      ConstraintExpression.IliVersion version) {
    Element container = constraint.getContainer();
    if (container == null || container.getScopedName() == null || container.getScopedName().isBlank()) {
      throw new TranslationException(
          "MISSING_CONSTRAINT_CONTEXT",
          "Constraint has no semantic context: " + constraint.getName());
    }
    String scopedName = constraint.getScopedName();
    if (scopedName == null || scopedName.isBlank()) {
      scopedName = container.getScopedName() + "." + constraint.getName();
    }
    return new Metadata(
        constraint.getName(),
        scopedName,
        container.getScopedName(),
        version);
  }

  private static ConstraintExpression.IliVersion iliVersion(Constraint constraint) {
    Element current = constraint;
    while (current != null) {
      if (current instanceof Model model) {
        return "2.3".equals(model.getIliVersion())
            ? ConstraintExpression.IliVersion.ILI_23
            : ConstraintExpression.IliVersion.ILI_24;
      }
      current = current.getContainer();
    }
    throw new TranslationException(
        "MISSING_MODEL_CONTEXT",
        "Constraint is not contained in an INTERLIS model: " + constraint.getName());
  }

  private record ComparisonParts(
      ConstraintExpression.ComparisonOperator operator,
      Evaluable left,
      Evaluable right) {
  }

  private record ObjectCountCall(Evaluable objects) {
  }

  private record Metadata(
      String name,
      String scopedName,
      String contextFqn,
      ConstraintExpression.IliVersion version) {
  }
}
