package ch.so.agi.mcp.constraint;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Small typed, semantic intermediate representation for constraint expressions.
 *
 * <p>The IR deliberately separates meaning from INTERLIS surface syntax. Frontends for INTERLIS 2.3,
 * INTERLIS 2.4, decision tables or other structured rule sources translate into the same semantic
 * tree. Renderers, case generators and solver adapters can then operate on that tree without
 * duplicating logic for Math.add versus the INTERLIS 2.4 {@code +} operator, Math versus Math_V2,
 * or similar syntax differences.</p>
 */
public sealed interface ConstraintExpression
    permits ConstraintExpression.NumericLiteral,
        ConstraintExpression.BooleanLiteral,
        ConstraintExpression.EnumLiteral,
        ConstraintExpression.TextLiteral,
        ConstraintExpression.Attribute,
        ConstraintExpression.Path,
        ConstraintExpression.FunctionCall,
        ConstraintExpression.Defined,
        ConstraintExpression.Not,
        ConstraintExpression.And,
        ConstraintExpression.Or,
        ConstraintExpression.Implies,
        ConstraintExpression.Comparison {

  Type type();

  default String toInterlis() {
    return toInterlis(IliVersion.ILI_23);
  }

  default String toInterlis(IliVersion version) {
    return Renderer.render(this, LanguageProfile.forVersion(version));
  }

  default String toInterlis(LanguageProfile profile) {
    return Renderer.render(this, profile);
  }

  default Set<Reference> references() {
    LinkedHashSet<Reference> result = new LinkedHashSet<>();
    collectReferences(this, result);
    return Set.copyOf(result);
  }

  enum IliVersion {
    ILI_23("2.3"),
    ILI_24("2.4");

    private final String text;

    IliVersion(String text) {
      this.text = text;
    }

    public String text() {
      return text;
    }
  }

  record LanguageProfile(
      IliVersion version,
      String mathModel,
      String textModel,
      boolean nativeArithmeticOperators) {

    public LanguageProfile {
      Objects.requireNonNull(version, "version");
      requireName(mathModel, "mathModel");
      requireName(textModel, "textModel");
    }

    public static LanguageProfile forVersion(IliVersion version) {
      Objects.requireNonNull(version, "version");
      return switch (version) {
        case ILI_23 -> new LanguageProfile(ILI_23, "Math", "Text", false);
        case ILI_24 -> new LanguageProfile(ILI_24, "Math_V2", "Text_V2", true);
      };
    }
  }

  enum ScalarKind {
    BOOLEAN,
    NUMERIC,
    ENUM,
    TEXT,
    MTEXT,
    GEOMETRY,
    UNKNOWN
  }

  enum ReferenceKind {
    ATTRIBUTE,
    PATH
  }

  enum ArgumentSemantics {
    VALUE,
    ATTRIBUTE_PATH
  }

  enum ResultTypeRule {
    DECLARED,
    PROPAGATE_NULLABILITY
  }

  enum ComparisonOperator {
    EQ("=="),
    NE("!="),
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">=");

    private final String interlis;

    ComparisonOperator(String interlis) {
      this.interlis = interlis;
    }

    public String interlis() {
      return interlis;
    }
  }

  record Type(ScalarKind scalarKind, boolean collection, boolean nullable) {
    public Type {
      Objects.requireNonNull(scalarKind, "scalarKind");
    }

    public static Type scalar(ScalarKind kind) {
      return new Type(kind, false, false);
    }

    public static Type optionalScalar(ScalarKind kind) {
      return new Type(kind, false, true);
    }

    public static Type collection(ScalarKind elementKind) {
      return new Type(elementKind, true, false);
    }

    public boolean isScalar(ScalarKind kind) {
      return !collection && scalarKind == kind;
    }

    public boolean isCollectionOf(ScalarKind kind) {
      return collection && scalarKind == kind;
    }
  }

  record Reference(String name, ReferenceKind kind, Type type) {
    public Reference {
      requireName(name, "reference name");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(type, "type");
    }
  }

  record ArgumentSpec(Type type, ArgumentSemantics semantics) {
    public ArgumentSpec {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(semantics, "semantics");
    }
  }

  sealed interface SurfaceSyntax permits FunctionSyntax, InfixSyntax {
  }

  record FunctionSyntax(String name) implements SurfaceSyntax {
    public FunctionSyntax {
      requireName(name, "function name");
    }
  }

  /**
   * Native binary operator spelling for a language version. Rendering is intentionally fully
   * parenthesized so solver/IR correctness never depends on a renderer reproducing precedence rules.
   */
  record InfixSyntax(String symbol) implements SurfaceSyntax {
    public InfixSyntax {
      requireName(symbol, "operator symbol");
    }
  }

  record FunctionDefinition(
      String semanticId,
      List<ArgumentSpec> arguments,
      Type declaredResultType,
      ResultTypeRule resultTypeRule,
      Map<IliVersion, SurfaceSyntax> surfaceSyntax) {

    public FunctionDefinition {
      requireName(semanticId, "semanticId");
      arguments = arguments == null ? List.of() : List.copyOf(arguments);
      Objects.requireNonNull(declaredResultType, "declaredResultType");
      requireScalar(declaredResultType, "Function result");
      Objects.requireNonNull(resultTypeRule, "resultTypeRule");
      if (surfaceSyntax == null || surfaceSyntax.isEmpty()) {
        throw new IllegalArgumentException("Function definition requires at least one surface syntax.");
      }
      surfaceSyntax = Map.copyOf(new LinkedHashMap<>(surfaceSyntax));
    }

    public SurfaceSyntax syntax(IliVersion version) {
      SurfaceSyntax syntax = surfaceSyntax.get(version);
      if (syntax == null) {
        throw new IllegalArgumentException(
            "Function semantics '" + semanticId + "' has no syntax for INTERLIS " + version.text() + ".");
      }
      return syntax;
    }

    private Type resultType(List<ConstraintExpression> actualArguments) {
      if (resultTypeRule == ResultTypeRule.DECLARED || declaredResultType.nullable()) {
        return declaredResultType;
      }
      boolean nullable = actualArguments.stream().anyMatch(argument -> argument.type().nullable());
      return nullable
          ? new Type(declaredResultType.scalarKind(), false, true)
          : declaredResultType;
    }
  }

  record NumericLiteral(BigDecimal value) implements ConstraintExpression {
    public NumericLiteral {
      Objects.requireNonNull(value, "value");
    }

    public NumericLiteral(long value) {
      this(BigDecimal.valueOf(value));
    }

    @Override
    public Type type() {
      return Type.scalar(ScalarKind.NUMERIC);
    }
  }

  record BooleanLiteral(boolean value) implements ConstraintExpression {
    @Override
    public Type type() {
      return Type.scalar(ScalarKind.BOOLEAN);
    }
  }

  record EnumLiteral(String value) implements ConstraintExpression {
    public EnumLiteral {
      requireName(value, "enum value");
    }

    @Override
    public Type type() {
      return Type.scalar(ScalarKind.ENUM);
    }
  }

  record TextLiteral(String value, ScalarKind kind) implements ConstraintExpression {
    public TextLiteral {
      Objects.requireNonNull(value, "value");
      if (kind != ScalarKind.TEXT && kind != ScalarKind.MTEXT) {
        throw new IllegalArgumentException("TextLiteral kind must be TEXT or MTEXT.");
      }
    }

    public TextLiteral(String value) {
      this(value, ScalarKind.TEXT);
    }

    @Override
    public Type type() {
      return Type.scalar(kind);
    }
  }

  record Attribute(String name, Type type) implements ConstraintExpression {
    public Attribute {
      requireName(name, "attribute name");
      Objects.requireNonNull(type, "type");
      requireScalar(type, "Attribute");
    }
  }

  record Path(String path, Type type) implements ConstraintExpression {
    public Path {
      requireName(path, "path");
      Objects.requireNonNull(type, "type");
    }
  }

  record FunctionCall(FunctionDefinition definition, List<ConstraintExpression> arguments)
      implements ConstraintExpression {
    public FunctionCall {
      Objects.requireNonNull(definition, "definition");
      arguments = arguments == null ? List.of() : List.copyOf(arguments);
      if (arguments.size() != definition.arguments().size()) {
        throw new IllegalArgumentException(
            "Function semantics '" + definition.semanticId() + "' expects "
                + definition.arguments().size() + " arguments, got " + arguments.size() + ".");
      }
      for (int i = 0; i < arguments.size(); i++) {
        validateArgument(definition, i, arguments.get(i), definition.arguments().get(i));
      }
    }

    public String semanticId() {
      return definition.semanticId();
    }

    @Override
    public Type type() {
      return definition.resultType(arguments);
    }
  }

  record Defined(ConstraintExpression operand) implements ConstraintExpression {
    public Defined {
      Objects.requireNonNull(operand, "operand");
      if (operand.type().collection()) {
        throw new IllegalArgumentException("DEFINED expects a scalar expression, not a collection.");
      }
    }

    @Override
    public Type type() {
      return Type.scalar(ScalarKind.BOOLEAN);
    }
  }

  record Not(ConstraintExpression operand) implements ConstraintExpression {
    public Not {
      requireBoolean(operand, "NOT operand");
    }

    @Override
    public Type type() {
      return Type.scalar(ScalarKind.BOOLEAN);
    }
  }

  record And(List<ConstraintExpression> operands) implements ConstraintExpression {
    public And {
      operands = validatedBooleanOperands(operands, "AND");
    }

    @Override
    public Type type() {
      return Type.scalar(ScalarKind.BOOLEAN);
    }
  }

  record Or(List<ConstraintExpression> operands) implements ConstraintExpression {
    public Or {
      operands = validatedBooleanOperands(operands, "OR");
    }

    @Override
    public Type type() {
      return Type.scalar(ScalarKind.BOOLEAN);
    }
  }

  record Implies(ConstraintExpression antecedent, ConstraintExpression consequent)
      implements ConstraintExpression {
    public Implies {
      requireBoolean(antecedent, "IMPLIES antecedent");
      requireBoolean(consequent, "IMPLIES consequent");
    }

    @Override
    public Type type() {
      return Type.scalar(ScalarKind.BOOLEAN);
    }
  }

  record Comparison(
      ComparisonOperator operator,
      ConstraintExpression left,
      ConstraintExpression right) implements ConstraintExpression {
    public Comparison {
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(left, "left");
      Objects.requireNonNull(right, "right");
      requireComparable(operator, left.type(), right.type());
    }

    @Override
    public Type type() {
      return Type.scalar(ScalarKind.BOOLEAN);
    }
  }

  private static void validateArgument(
      FunctionDefinition definition,
      int index,
      ConstraintExpression argument,
      ArgumentSpec spec) {
    Objects.requireNonNull(argument, "function argument");
    Type expected = spec.type();
    Type actual = argument.type();
    if (expected.collection() != actual.collection()
        || (expected.scalarKind() != actual.scalarKind()
            && expected.scalarKind() != ScalarKind.UNKNOWN
            && actual.scalarKind() != ScalarKind.UNKNOWN)) {
      throw new IllegalArgumentException(
          "Function semantics '" + definition.semanticId() + "' argument " + index
              + " expects " + expected + ", got " + actual + ".");
    }
    if (spec.semantics() == ArgumentSemantics.ATTRIBUTE_PATH && !(argument instanceof Path)) {
      throw new IllegalArgumentException(
          "Function semantics '" + definition.semanticId() + "' argument " + index
              + " requires an ATTRIBUTE_PATH.");
    }
  }

  private static List<ConstraintExpression> validatedBooleanOperands(
      List<ConstraintExpression> operands,
      String label) {
    if (operands == null || operands.isEmpty()) {
      throw new IllegalArgumentException(label + " requires at least one operand.");
    }
    List<ConstraintExpression> copy = new ArrayList<>(operands);
    copy.forEach(operand -> requireBoolean(operand, label + " operand"));
    return List.copyOf(copy);
  }

  private static void requireComparable(ComparisonOperator operator, Type left, Type right) {
    requireScalar(left, "Comparison left operand");
    requireScalar(right, "Comparison right operand");
    if (left.scalarKind() != right.scalarKind()
        && left.scalarKind() != ScalarKind.UNKNOWN
        && right.scalarKind() != ScalarKind.UNKNOWN) {
      throw new IllegalArgumentException(
          "Comparison operands must have the same scalar type; got "
              + left.scalarKind() + " and " + right.scalarKind() + ".");
    }
    ScalarKind kind = left.scalarKind() == ScalarKind.UNKNOWN ? right.scalarKind() : left.scalarKind();
    if (operator != ComparisonOperator.EQ
        && operator != ComparisonOperator.NE
        && kind != ScalarKind.NUMERIC
        && kind != ScalarKind.UNKNOWN) {
      throw new IllegalArgumentException(
          "Ordering comparisons are supported for NUMERIC expressions only; got " + kind + ".");
    }
  }

  private static void requireBoolean(ConstraintExpression expression, String label) {
    requireScalarKind(expression, ScalarKind.BOOLEAN, label);
  }

  private static void requireScalarKind(
      ConstraintExpression expression,
      ScalarKind kind,
      String label) {
    Objects.requireNonNull(expression, label);
    if (!expression.type().isScalar(kind)) {
      throw new IllegalArgumentException(
          label + " must be scalar " + kind + "; got " + expression.type() + ".");
    }
  }

  private static void requireScalar(Type type, String label) {
    if (type.collection()) {
      throw new IllegalArgumentException(label + " must be scalar; got " + type + ".");
    }
  }

  private static void requireName(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank.");
    }
  }

  private static void collectReferences(
      ConstraintExpression expression,
      LinkedHashSet<Reference> sink) {
    switch (expression) {
      case Attribute attribute -> sink.add(new Reference(
          attribute.name(), ReferenceKind.ATTRIBUTE, attribute.type()));
      case Path path -> sink.add(new Reference(path.path(), ReferenceKind.PATH, path.type()));
      case FunctionCall call -> call.arguments().forEach(child -> collectReferences(child, sink));
      case Defined defined -> collectReferences(defined.operand(), sink);
      case Not not -> collectReferences(not.operand(), sink);
      case And and -> and.operands().forEach(child -> collectReferences(child, sink));
      case Or or -> or.operands().forEach(child -> collectReferences(child, sink));
      case Implies implies -> {
        collectReferences(implies.antecedent(), sink);
        collectReferences(implies.consequent(), sink);
      }
      case Comparison comparison -> {
        collectReferences(comparison.left(), sink);
        collectReferences(comparison.right(), sink);
      }
      case NumericLiteral ignored -> {
      }
      case BooleanLiteral ignored -> {
      }
      case EnumLiteral ignored -> {
      }
      case TextLiteral ignored -> {
      }
    }
  }

  final class Renderer {
    private static final int IMPLIES_PRECEDENCE = 10;
    private static final int OR_PRECEDENCE = 20;
    private static final int AND_PRECEDENCE = 30;
    private static final int COMPARISON_PRECEDENCE = 40;
    private static final int UNARY_PRECEDENCE = 50;
    private static final int ATOMIC_PRECEDENCE = 60;

    private Renderer() {
    }

    static String render(ConstraintExpression expression, LanguageProfile profile) {
      Objects.requireNonNull(profile, "profile");
      return render(expression, profile, 0);
    }

    private static String render(
        ConstraintExpression expression,
        LanguageProfile profile,
        int parentPrecedence) {
      int precedence = precedence(expression);
      String rendered = switch (expression) {
        case NumericLiteral number -> number.value().stripTrailingZeros().toPlainString();
        case BooleanLiteral bool -> "#" + Boolean.toString(bool.value());
        case EnumLiteral enumeration -> "#" + enumeration.value();
        case TextLiteral text -> "\"" + escapeText(text.value()) + "\"";
        case Attribute attribute -> attribute.name();
        case Path path -> path.path();
        case FunctionCall call -> renderFunction(call, profile);
        case Defined defined -> "DEFINED(" + render(defined.operand(), profile, 0) + ")";
        case Not not -> "NOT(" + render(not.operand(), profile, 0) + ")";
        case Comparison comparison -> render(comparison.left(), profile, COMPARISON_PRECEDENCE)
            + " " + comparison.operator().interlis() + " "
            + render(comparison.right(), profile, COMPARISON_PRECEDENCE);
        case And and -> and.operands().stream()
            .map(child -> render(child, profile, AND_PRECEDENCE))
            .reduce((left, right) -> left + " AND " + right)
            .orElseThrow();
        case Or or -> or.operands().stream()
            .map(child -> render(child, profile, OR_PRECEDENCE))
            .reduce((left, right) -> left + " OR " + right)
            .orElseThrow();
        case Implies implies -> render(implies.antecedent(), profile, IMPLIES_PRECEDENCE)
            + " IMPLIES " + render(implies.consequent(), profile, IMPLIES_PRECEDENCE);
      };

      boolean parenthesize = precedence < parentPrecedence
          || expression instanceof And
          || expression instanceof Implies;
      return parenthesize ? "(" + rendered + ")" : rendered;
    }

    private static String renderFunction(FunctionCall call, LanguageProfile profile) {
      SurfaceSyntax syntax = call.definition().syntax(profile.version());
      return switch (syntax) {
        case FunctionSyntax function -> function.name() + "(" + renderFunctionArguments(call, profile) + ")";
        case InfixSyntax infix -> {
          if (call.arguments().size() != 2) {
            throw new IllegalArgumentException(
                "Infix syntax requires exactly two arguments for '" + call.semanticId() + "'.");
          }
          yield "(" + render(call.arguments().get(0), profile, 0)
              + " " + infix.symbol() + " "
              + render(call.arguments().get(1), profile, 0) + ")";
        }
      };
    }

    private static String renderFunctionArguments(FunctionCall call, LanguageProfile profile) {
      List<String> rendered = new ArrayList<>();
      for (int i = 0; i < call.arguments().size(); i++) {
        ConstraintExpression argument = call.arguments().get(i);
        ArgumentSpec spec = call.definition().arguments().get(i);
        if (spec.semantics() == ArgumentSemantics.ATTRIBUTE_PATH) {
          Path path = (Path) argument;
          rendered.add("\"" + escapeText(path.path()) + "\"");
        } else {
          rendered.add(render(argument, profile, 0));
        }
      }
      return String.join(", ", rendered);
    }

    private static String escapeText(String value) {
      return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static int precedence(ConstraintExpression expression) {
      return switch (expression) {
        case Implies ignored -> IMPLIES_PRECEDENCE;
        case Or ignored -> OR_PRECEDENCE;
        case And ignored -> AND_PRECEDENCE;
        case Comparison ignored -> COMPARISON_PRECEDENCE;
        case Defined ignored -> UNARY_PRECEDENCE;
        case Not ignored -> UNARY_PRECEDENCE;
        case NumericLiteral ignored -> ATOMIC_PRECEDENCE;
        case BooleanLiteral ignored -> ATOMIC_PRECEDENCE;
        case EnumLiteral ignored -> ATOMIC_PRECEDENCE;
        case TextLiteral ignored -> ATOMIC_PRECEDENCE;
        case Attribute ignored -> ATOMIC_PRECEDENCE;
        case Path ignored -> ATOMIC_PRECEDENCE;
        case FunctionCall ignored -> ATOMIC_PRECEDENCE;
      };
    }
  }
}
