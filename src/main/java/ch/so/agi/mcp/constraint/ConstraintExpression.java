package ch.so.agi.mcp.constraint;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Small typed intermediate representation for constraint expressions.
 *
 * <p>The IR is intentionally independent of MCP DTOs, ili2c AST classes and XTF fixture generation.
 * Frontends translate into this representation; rendering, case generation and solver adapters can then
 * operate on the same expression tree.</p>
 */
public sealed interface ConstraintExpression
    permits ConstraintExpression.NumericLiteral,
        ConstraintExpression.BooleanLiteral,
        ConstraintExpression.EnumLiteral,
        ConstraintExpression.Attribute,
        ConstraintExpression.Path,
        ConstraintExpression.Sum,
        ConstraintExpression.Add,
        ConstraintExpression.Defined,
        ConstraintExpression.Not,
        ConstraintExpression.And,
        ConstraintExpression.Or,
        ConstraintExpression.Implies,
        ConstraintExpression.Comparison {

  Type type();

  default String toInterlis() {
    return Renderer.render(this);
  }

  default Set<Reference> references() {
    LinkedHashSet<Reference> result = new LinkedHashSet<>();
    collectReferences(this, result);
    return Set.copyOf(result);
  }

  enum ScalarKind {
    BOOLEAN,
    NUMERIC,
    ENUM,
    TEXT,
    GEOMETRY,
    UNKNOWN
  }

  enum ReferenceKind {
    ATTRIBUTE,
    PATH
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

  record Sum(Path path) implements ConstraintExpression {
    public Sum {
      Objects.requireNonNull(path, "path");
      if (!path.type().isCollectionOf(ScalarKind.NUMERIC)) {
        throw new IllegalArgumentException("SUM requires a collection of NUMERIC values.");
      }
    }

    @Override
    public Type type() {
      // An empty collection makes the INTERLIS Math.sum result undefined.
      return Type.optionalScalar(ScalarKind.NUMERIC);
    }
  }

  record Add(ConstraintExpression left, ConstraintExpression right) implements ConstraintExpression {
    public Add {
      requireScalarKind(left, ScalarKind.NUMERIC, "Math.add left operand");
      requireScalarKind(right, ScalarKind.NUMERIC, "Math.add right operand");
    }

    @Override
    public Type type() {
      return new Type(
          ScalarKind.NUMERIC,
          false,
          left.type().nullable() || right.type().nullable());
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
      case Sum sum -> collectReferences(sum.path(), sink);
      case Add add -> {
        collectReferences(add.left(), sink);
        collectReferences(add.right(), sink);
      }
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
    }
  }

  final class Renderer {
    private Renderer() {
    }

    static String render(ConstraintExpression expression) {
      return switch (expression) {
        case NumericLiteral number -> number.value().stripTrailingZeros().toPlainString();
        case BooleanLiteral bool -> "#" + Boolean.toString(bool.value());
        case EnumLiteral enumeration -> "#" + enumeration.value();
        case Attribute attribute -> attribute.name();
        case Path path -> path.path();
        case Sum sum -> "Math.sum(\"" + sum.path().path() + "\")";
        case Add add -> "Math.add(" + render(add.left()) + ", " + render(add.right()) + ")";
        case Defined defined -> "DEFINED(" + render(defined.operand()) + ")";
        case Not not -> "NOT(" + render(not.operand()) + ")";
        case Comparison comparison -> render(comparison.left())
            + " " + comparison.operator().interlis() + " " + render(comparison.right());
        case And and -> parenthesized(and.operands(), " AND ");
        case Or or -> or.operands().stream()
            .map(Renderer::render)
            .reduce((left, right) -> left + " OR " + right)
            .orElseThrow();
        case Implies implies -> "(" + render(implies.antecedent())
            + " IMPLIES " + render(implies.consequent()) + ")";
      };
    }

    private static String parenthesized(List<ConstraintExpression> expressions, String separator) {
      return "(" + expressions.stream()
          .map(Renderer::render)
          .reduce((left, right) -> left + separator + right)
          .orElseThrow() + ")";
    }
  }
}
