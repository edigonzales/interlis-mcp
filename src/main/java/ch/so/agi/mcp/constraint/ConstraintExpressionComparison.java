package ch.so.agi.mcp.constraint;

import ch.so.agi.mcp.constraint.ConstraintExpression.*;
import ch.so.agi.mcp.model.IliConstraintSpec;
import java.math.BigDecimal;
import java.util.List;

/** Ordered structural comparison; never removes characters from literal values. */
final class ConstraintExpressionComparison {
  private record Node(String kind, Object value, List<Node> children) {}

  private ConstraintExpressionComparison() {}

  static boolean matches(IliConstraintSpec.ExpressionSpec requested, ConstraintExpression actual) {
    return requested == null || actual == null ? requested == null && actual == null
        : requested(requested).equals(actual(actual));
  }

  private static Node leaf(String kind, Object value) { return new Node(kind, value, List.of()); }

  private static Node branch(String kind, List<Node> children) {
    // A one-operand connective is rendered as parentheses and has no AST operator of its own.
    if ((kind.equals("AND") || kind.equals("OR")) && children.size() == 1) return children.getFirst();
    return new Node(kind, "", children);
  }

  private static Node implication(Node left, Node right) {
    return branch("OR", List.of(branch("NOT", List.of(left)), right));
  }

  private static Node enumeration(String value) {
    return value.equals("true") || value.equals("false")
        ? leaf("BOOLEAN", Boolean.valueOf(value)) : leaf("ENUM", value);
  }

  private static Node requested(IliConstraintSpec.ExpressionSpec spec) {
    List<Node> children = spec.children == null ? List.of()
        : spec.children.stream().map(ConstraintExpressionComparison::requested).toList();
    return switch (spec.kind) {
      case ATTRIBUTE, PATH -> leaf("REFERENCE", spec.name.trim());
      case NUMERIC -> leaf("NUMERIC", new BigDecimal(String.valueOf(spec.value)).stripTrailingZeros());
      case BOOLEAN -> leaf("BOOLEAN", spec.value);
      case ENUM -> enumeration(String.valueOf(spec.value));
      case TEXT, MTEXT -> leaf("TEXT", String.valueOf(spec.value));
      case DEFINED, NOT, AND, OR -> branch(spec.kind.name(), children);
      case IMPLIES -> implication(children.get(0), children.get(1));
      case COMPARE -> new Node("COMPARE", spec.operator.trim().equals("<>") ? "!=" : spec.operator.trim(), children);
      case FUNCTION -> new Node("FUNCTION",
          spec.functionOrigin == IliConstraintSpec.FunctionOrigin.STANDARD ? spec.name.trim()
              : "MODEL_FUNCTION:" + spec.name.trim(), children);
      case OBJECT_COUNT -> leaf("OBJECT_COUNT", spec.objects instanceof IliConstraintSpec.PathObjectsSpec path ? path.path.trim() : "ALL");
    };
  }

  private static Node actual(ConstraintExpression expression) {
    return switch (expression) {
      case Attribute attribute -> leaf("REFERENCE", attribute.name());
      case ObjectCount count -> leaf("OBJECT_COUNT", count.objects().path());
      case Path path -> leaf("REFERENCE", path.path());
      case NumericLiteral literal -> leaf("NUMERIC", literal.value().stripTrailingZeros());
      case BooleanLiteral literal -> leaf("BOOLEAN", literal.value());
      case EnumLiteral literal -> enumeration(literal.value());
      // TEXT/MTEXT share the same literal syntax; the compiler verifies their contextual type.
      case TextLiteral literal -> leaf("TEXT", literal.value());
      case Defined defined -> branch("DEFINED", List.of(actual(defined.operand())));
      case Not not -> branch("NOT", List.of(actual(not.operand())));
      case And and -> branch("AND", and.operands().stream().map(ConstraintExpressionComparison::actual).toList());
      case Or or -> branch("OR", or.operands().stream().map(ConstraintExpressionComparison::actual).toList());
      case Implies implies -> implication(actual(implies.antecedent()), actual(implies.consequent()));
      case Comparison comparison -> new Node("COMPARE", comparison.operator().interlis(),
          List.of(actual(comparison.left()), actual(comparison.right())));
      case FunctionCall call -> new Node("FUNCTION", call.semanticId(),
          call.arguments().stream().map(ConstraintExpressionComparison::actual).toList());
    };
  }
}
