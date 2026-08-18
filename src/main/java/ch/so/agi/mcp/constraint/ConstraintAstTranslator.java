package ch.so.agi.mcp.constraint;

import ch.interlis.ili2c.metamodel.AttributeRef;
import ch.interlis.ili2c.metamodel.Cardinality;
import ch.interlis.ili2c.metamodel.Constant;
import ch.interlis.ili2c.metamodel.Constraint;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.EnumerationType;
import ch.interlis.ili2c.metamodel.Evaluable;
import ch.interlis.ili2c.metamodel.Expression;
import ch.interlis.ili2c.metamodel.FormalArgument;
import ch.interlis.ili2c.metamodel.Function;
import ch.interlis.ili2c.metamodel.FunctionCall;
import ch.interlis.ili2c.metamodel.MandatoryConstraint;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.ObjectPath;
import ch.interlis.ili2c.metamodel.PathEl;
import ch.interlis.ili2c.metamodel.PathElAbstractClassRole;
import ch.interlis.ili2c.metamodel.PathElAssocRole;
import ch.interlis.ili2c.metamodel.RoleDef;
import ch.interlis.ili2c.metamodel.TextType;
import ch.interlis.ili2c.metamodel.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Translates compiled ili2c mandatory-constraint AST nodes into the shared semantic constraint IR.
 *
 * <p>The translator reads ili2c metamodel objects directly. It deliberately does not depend on the
 * map-shaped AST returned by {@code reviewIliConstraint}; that map remains an API representation,
 * while this class is the typed semantic adapter used by evaluator, solver and fixture synthesis.
 */
public final class ConstraintAstTranslator {

  public record Translation(
      String constraintName,
      String contextFqn,
      ConstraintExpression.IliVersion version,
      ConstraintExpression expression) {

    public Translation {
      requireText(constraintName, "constraintName");
      requireText(contextFqn, "contextFqn");
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(expression, "expression");
    }
  }

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

  private final ConstraintExpression.IliVersion version;

  private ConstraintAstTranslator(ConstraintExpression.IliVersion version) {
    this.version = Objects.requireNonNull(version, "version");
  }

  public static Translation translate(Constraint constraint) {
    Objects.requireNonNull(constraint, "constraint");
    if (!(constraint instanceof MandatoryConstraint)) {
      throw new TranslationException(
          "UNSUPPORTED_CONSTRAINT_KIND",
          "Semantic AST translation currently supports MANDATORY CONSTRAINT only; got "
              + constraint.getClass().getSimpleName() + ".");
    }
    if (constraint.getCondition() == null) {
      throw new TranslationException(
          "MISSING_CONSTRAINT_CONDITION",
          "Mandatory constraint has no condition: " + constraint.getScopedName());
    }

    ConstraintExpression.IliVersion version = iliVersion(constraint);
    ConstraintExpression expression = new ConstraintAstTranslator(version)
        .translateEvaluable(constraint.getCondition());
    if (!expression.type().isScalar(ConstraintExpression.ScalarKind.BOOLEAN)) {
      throw new TranslationException(
          "NON_BOOLEAN_CONSTRAINT_CONDITION",
          "Mandatory constraint condition did not translate to BOOLEAN: " + constraint.getScopedName());
    }

    Element container = constraint.getContainer();
    if (container == null || container.getScopedName() == null || container.getScopedName().isBlank()) {
      throw new TranslationException(
          "MISSING_CONSTRAINT_CONTEXT",
          "Mandatory constraint has no viewable context: " + constraint.getScopedName());
    }
    return new Translation(
        constraint.getName(),
        container.getScopedName(),
        version,
        expression);
  }

  private ConstraintExpression translateEvaluable(Evaluable evaluable) {
    Objects.requireNonNull(evaluable, "evaluable");
    if (evaluable instanceof Expression.Disjunction expression) {
      return new ConstraintExpression.Or(translateMany(expression.getDisjoined()));
    }
    if (evaluable instanceof Expression.Conjunction expression) {
      return new ConstraintExpression.And(translateMany(expression.getConjoined()));
    }
    if (evaluable instanceof Expression.Negation expression) {
      return new ConstraintExpression.Not(translateEvaluable(expression.getNegated()));
    }
    if (evaluable instanceof Expression.Subexpression expression) {
      return translateEvaluable(expression.getSubexpression());
    }
    if (evaluable instanceof Expression.DefinedCheck expression) {
      return new ConstraintExpression.Defined(translateEvaluable(expression.getArgument()));
    }
    if (evaluable instanceof Expression.Equality expression) {
      return comparison(ConstraintExpression.ComparisonOperator.EQ, expression.getLeft(), expression.getRight());
    }
    if (evaluable instanceof Expression.Inequality expression) {
      return comparison(ConstraintExpression.ComparisonOperator.NE, expression.getLeft(), expression.getRight());
    }
    if (evaluable instanceof Expression.GreaterThan expression) {
      return comparison(ConstraintExpression.ComparisonOperator.GT, expression.getLeft(), expression.getRight());
    }
    if (evaluable instanceof Expression.GreaterThanOrEqual expression) {
      return comparison(ConstraintExpression.ComparisonOperator.GE, expression.getLeft(), expression.getRight());
    }
    if (evaluable instanceof Expression.LessThan expression) {
      return comparison(ConstraintExpression.ComparisonOperator.LT, expression.getLeft(), expression.getRight());
    }
    if (evaluable instanceof Expression.LessThanOrEqual expression) {
      return comparison(ConstraintExpression.ComparisonOperator.LE, expression.getLeft(), expression.getRight());
    }
    if (evaluable instanceof Expression.Implication expression) {
      return new ConstraintExpression.Implies(
          translateEvaluable(expression.getLeft()),
          translateEvaluable(expression.getRight()));
    }
    if (evaluable instanceof Expression.Addition expression) {
      return standardCall("NUMERIC_ADD", expression.getLeft(), expression.getRight());
    }
    if (evaluable instanceof Expression.Subtraction expression) {
      return standardCall("NUMERIC_SUB", expression.getLeft(), expression.getRight());
    }
    if (evaluable instanceof Expression.Multiplication expression) {
      return standardCall("NUMERIC_MUL", expression.getLeft(), expression.getRight());
    }
    if (evaluable instanceof Expression.Division expression) {
      return standardCall("NUMERIC_DIV", expression.getLeft(), expression.getRight());
    }
    if (evaluable instanceof FunctionCall call) {
      return translateFunctionCall(call);
    }
    if (evaluable instanceof ObjectPath path) {
      return translateObjectPath(path);
    }
    if (evaluable instanceof Constant constant) {
      return translateConstant(constant);
    }
    throw unsupportedNode(evaluable);
  }

  private List<ConstraintExpression> translateMany(Evaluable[] evaluables) {
    List<ConstraintExpression> result = new ArrayList<>();
    for (Evaluable evaluable : evaluables) {
      result.add(translateEvaluable(evaluable));
    }
    return List.copyOf(result);
  }

  private ConstraintExpression comparison(
      ConstraintExpression.ComparisonOperator operator,
      Evaluable left,
      Evaluable right) {
    return new ConstraintExpression.Comparison(
        operator,
        translateEvaluable(left),
        translateEvaluable(right));
  }

  private ConstraintExpression standardCall(
      String semanticId,
      Evaluable left,
      Evaluable right) {
    ConstraintExpression.FunctionDefinition definition = StandardFunctionRegistry
        .findBySemanticId(semanticId)
        .orElseThrow(() -> new TranslationException(
            "MISSING_STANDARD_FUNCTION_SEMANTICS",
            "Standard function registry has no semantic definition for " + semanticId + "."))
        .definition();
    return new ConstraintExpression.FunctionCall(
        definition,
        List.of(translateEvaluable(left), translateEvaluable(right)));
  }

  private ConstraintExpression translateFunctionCall(FunctionCall call) {
    Function function = call.getFunction();
    String functionName = function.getScopedName();
    StandardFunctionRegistry.StandardFunction standard = StandardFunctionRegistry
        .findByQualifiedName(version, functionName)
        .orElse(null);
    ConstraintExpression.FunctionDefinition definition = standard != null
        ? standard.definition()
        : modelFunctionDefinition(function);

    Evaluable[] actualArguments = call.getArguments();
    if (actualArguments.length != definition.arguments().size()) {
      throw new TranslationException(
          "FUNCTION_ARGUMENT_COUNT_MISMATCH",
          "Function " + functionName + " has " + actualArguments.length
              + " AST arguments but semantic definition expects " + definition.arguments().size() + ".");
    }

    List<ConstraintExpression> arguments = new ArrayList<>();
    for (int i = 0; i < actualArguments.length; i++) {
      arguments.add(translateFunctionArgument(
          functionName,
          actualArguments[i],
          definition.arguments().get(i)));
    }
    return new ConstraintExpression.FunctionCall(definition, arguments);
  }

  private ConstraintExpression translateFunctionArgument(
      String functionName,
      Evaluable argument,
      ConstraintExpression.ArgumentSpec spec) {
    if (spec.semantics() == ConstraintExpression.ArgumentSemantics.ATTRIBUTE_PATH) {
      if (argument instanceof Constant.Text text) {
        return new ConstraintExpression.Path(text.getValue(), spec.type());
      }
      if (argument instanceof ObjectPath path) {
        return new ConstraintExpression.Path(path.toString(), spec.type());
      }
      throw new TranslationException(
          "UNSUPPORTED_ATTRIBUTE_PATH_ARGUMENT",
          "Function " + functionName + " expects an ATTRIBUTE_PATH but ili2c provided "
              + argument.getClass().getSimpleName() + ".");
    }
    if (argument instanceof Constant.Text text
        && (spec.type().scalarKind() == ConstraintExpression.ScalarKind.TEXT
            || spec.type().scalarKind() == ConstraintExpression.ScalarKind.MTEXT)) {
      return new ConstraintExpression.TextLiteral(text.getValue(), spec.type().scalarKind());
    }
    return translateEvaluable(argument);
  }

  private ConstraintExpression.FunctionDefinition modelFunctionDefinition(Function function) {
    List<ConstraintExpression.ArgumentSpec> arguments = new ArrayList<>();
    for (FormalArgument formal : function.getArguments()) {
      arguments.add(new ConstraintExpression.ArgumentSpec(
          scalarType(formal.getType()),
          ConstraintExpression.ArgumentSemantics.VALUE));
    }
    ConstraintExpression.Type resultType = scalarType(function.getDomain());
    return new ConstraintExpression.FunctionDefinition(
        "MODEL_FUNCTION:" + function.getScopedName(),
        arguments,
        resultType,
        ConstraintExpression.ResultTypeRule.PROPAGATE_NULLABILITY,
        Map.of(version, new ConstraintExpression.FunctionSyntax(function.getScopedName())));
  }

  private ConstraintExpression translateObjectPath(ObjectPath objectPath) {
    PathEl[] elements = objectPath.getPathElements();
    if (elements == null || elements.length == 0 || !objectPath.isAttributePath()) {
      throw new TranslationException(
          "UNSUPPORTED_OBJECT_PATH",
          "Semantic IR currently requires an attribute-valued object path: " + objectPath);
    }

    ConstraintExpression.Type endpointType = scalarType(objectPath.getType());
    if (elements.length == 1 && elements[0] instanceof AttributeRef) {
      return new ConstraintExpression.Attribute(elements[0].getName(), endpointType);
    }

    boolean collection = false;
    boolean nullable = endpointType.nullable();
    for (PathEl element : elements) {
      RoleDef role = role(element);
      if (role == null) {
        continue;
      }
      Cardinality cardinality = role.getCardinality();
      long minimum = cardinality != null ? cardinality.getMinimum() : 1;
      long maximum = cardinality != null ? cardinality.getMaximum() : 1;
      boolean unbounded = cardinality != null && maximum == Cardinality.UNBOUND;
      collection |= unbounded || maximum > 1;
      nullable |= minimum == 0;
    }

    ConstraintExpression.Type pathType = collection
        ? ConstraintExpression.Type.collection(endpointType.scalarKind())
        : new ConstraintExpression.Type(endpointType.scalarKind(), false, nullable);
    return new ConstraintExpression.Path(objectPath.toString(), pathType);
  }

  private ConstraintExpression translateConstant(Constant constant) {
    if (constant instanceof Constant.Numeric numeric) {
      return new ConstraintExpression.NumericLiteral(new BigDecimal(numeric.getValue().toString()));
    }
    if (constant instanceof Constant.Text text) {
      ConstraintExpression.ScalarKind kind = scalarType(constant.getType()).scalarKind();
      if (kind != ConstraintExpression.ScalarKind.MTEXT) {
        kind = ConstraintExpression.ScalarKind.TEXT;
      }
      return new ConstraintExpression.TextLiteral(text.getValue(), kind);
    }
    if (constant instanceof Constant.Enumeration enumeration) {
      String value = String.join(".", enumeration.getValue());
      if (constant.getType() != null && constant.getType().isBoolean()) {
        if ("true".equals(value)) {
          return new ConstraintExpression.BooleanLiteral(true);
        }
        if ("false".equals(value)) {
          return new ConstraintExpression.BooleanLiteral(false);
        }
      }
      return new ConstraintExpression.EnumLiteral(value);
    }
    if (constant instanceof Constant.Undefined) {
      throw new TranslationException(
          "UNSUPPORTED_UNDEFINED_LITERAL",
          "UNDEFINED literals are not represented in the semantic IR yet; use DEFINED/NOT DEFINED where possible.");
    }
    throw unsupportedNode(constant);
  }

  private ConstraintExpression.Type scalarType(Type declared) {
    if (declared == null) {
      return ConstraintExpression.Type.optionalScalar(ConstraintExpression.ScalarKind.UNKNOWN);
    }
    Type real = Type.findReal(declared);
    ConstraintExpression.ScalarKind kind;
    if (real instanceof NumericType) {
      kind = ConstraintExpression.ScalarKind.NUMERIC;
    } else if (declared.isBoolean() || real.isBoolean()) {
      kind = ConstraintExpression.ScalarKind.BOOLEAN;
    } else if (real instanceof EnumerationType) {
      kind = ConstraintExpression.ScalarKind.ENUM;
    } else if (real instanceof TextType text) {
      kind = text.isNormalized()
          ? ConstraintExpression.ScalarKind.TEXT
          : ConstraintExpression.ScalarKind.MTEXT;
    } else {
      kind = ConstraintExpression.ScalarKind.UNKNOWN;
    }
    boolean nullable = !declared.isMandatoryConsideringAliases();
    return new ConstraintExpression.Type(kind, false, nullable);
  }

  private RoleDef role(PathEl element) {
    if (element instanceof PathElAssocRole associationRole) {
      return associationRole.getRole();
    }
    if (element instanceof PathElAbstractClassRole classRole) {
      return classRole.getRole();
    }
    return null;
  }

  private TranslationException unsupportedNode(Evaluable evaluable) {
    return new TranslationException(
        "UNSUPPORTED_AST_NODE",
        "ili2c AST node is not supported by semantic IR translation: "
            + evaluable.getClass().getName() + ".");
  }

  private static ConstraintExpression.IliVersion iliVersion(Constraint constraint) {
    Element current = constraint;
    while (current != null) {
      if (current instanceof Model model) {
        String version = model.getIliVersion();
        if ("2.3".equals(version)) {
          return ConstraintExpression.IliVersion.ILI_23;
        }
        if ("2.4".equals(version)) {
          return ConstraintExpression.IliVersion.ILI_24;
        }
      }
      current = current.getContainer();
    }
    throw new TranslationException(
        "UNKNOWN_INTERLIS_VERSION",
        "Unable to determine INTERLIS 2.3/2.4 version for constraint " + constraint.getScopedName() + ".");
  }

  private static void requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank.");
    }
  }
}
