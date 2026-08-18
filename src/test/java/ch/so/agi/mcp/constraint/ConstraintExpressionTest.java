package ch.so.agi.mcp.constraint;

import static ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator.EQ;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator.GE;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator.LE;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.BOOLEAN;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.ENUM;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.NUMERIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConstraintExpressionTest {

  @Test
  void rendersAfuWeightingConstraintFromGenericNodes() {
    ConstraintExpression.Path secondaryWeights = new ConstraintExpression.Path(
        "Nebenauspraegung->Gewichtung",
        ConstraintExpression.Type.collection(NUMERIC));
    ConstraintExpression.Sum sum = new ConstraintExpression.Sum(secondaryWeights);
    ConstraintExpression.Attribute mainWeight = new ConstraintExpression.Attribute(
        "Gewichtung",
        ConstraintExpression.Type.scalar(NUMERIC));
    ConstraintExpression.NumericLiteral hundred = new ConstraintExpression.NumericLiteral(100);

    ConstraintExpression expression = new ConstraintExpression.Or(List.of(
        new ConstraintExpression.And(List.of(
            new ConstraintExpression.Defined(sum),
            new ConstraintExpression.Comparison(
                EQ,
                new ConstraintExpression.Add(sum, mainWeight),
                hundred))),
        new ConstraintExpression.And(List.of(
            new ConstraintExpression.Comparison(EQ, mainWeight, hundred),
            new ConstraintExpression.Not(new ConstraintExpression.Defined(sum))))));

    assertEquals(
        "(DEFINED(Math.sum(\"Nebenauspraegung->Gewichtung\")) AND Math.add(Math.sum(\"Nebenauspraegung->Gewichtung\"), Gewichtung) == 100)"
            + " OR (Gewichtung == 100 AND NOT(DEFINED(Math.sum(\"Nebenauspraegung->Gewichtung\"))))",
        expression.toInterlis());
    assertEquals(ConstraintExpression.Type.scalar(BOOLEAN), expression.type());

    Set<ConstraintExpression.Reference> references = expression.references();
    assertEquals(2, references.size());
    assertTrue(references.contains(new ConstraintExpression.Reference(
        "Nebenauspraegung->Gewichtung",
        ConstraintExpression.ReferenceKind.PATH,
        ConstraintExpression.Type.collection(NUMERIC))));
    assertTrue(references.contains(new ConstraintExpression.Reference(
        "Gewichtung",
        ConstraintExpression.ReferenceKind.ATTRIBUTE,
        ConstraintExpression.Type.scalar(NUMERIC))));
  }

  @Test
  void rendersNumericBooleanAndEnumExpressions() {
    ConstraintExpression.Attribute value = new ConstraintExpression.Attribute(
        "value",
        ConstraintExpression.Type.scalar(NUMERIC));
    ConstraintExpression numeric = new ConstraintExpression.And(List.of(
        new ConstraintExpression.Comparison(
            GE,
            value,
            new ConstraintExpression.NumericLiteral(BigDecimal.TEN)),
        new ConstraintExpression.Comparison(
            LE,
            value,
            new ConstraintExpression.NumericLiteral(BigDecimal.valueOf(20)))));
    assertEquals("(value >= 10 AND value <= 20)", numeric.toInterlis());

    ConstraintExpression.Attribute enabled = new ConstraintExpression.Attribute(
        "enabled",
        ConstraintExpression.Type.scalar(BOOLEAN));
    assertEquals(
        "enabled == #true",
        new ConstraintExpression.Comparison(
            EQ,
            enabled,
            new ConstraintExpression.BooleanLiteral(true)).toInterlis());

    ConstraintExpression.Attribute status = new ConstraintExpression.Attribute(
        "status",
        ConstraintExpression.Type.scalar(ENUM));
    assertEquals(
        "status == #active",
        new ConstraintExpression.Comparison(
            EQ,
            status,
            new ConstraintExpression.EnumLiteral("active")).toInterlis());
  }

  @Test
  void preservesLogicalPrecedenceWhenExpressionsAreNested() {
    ConstraintExpression.Attribute a = new ConstraintExpression.Attribute(
        "a",
        ConstraintExpression.Type.scalar(BOOLEAN));
    ConstraintExpression.Attribute b = new ConstraintExpression.Attribute(
        "b",
        ConstraintExpression.Type.scalar(BOOLEAN));
    ConstraintExpression.Attribute c = new ConstraintExpression.Attribute(
        "c",
        ConstraintExpression.Type.scalar(BOOLEAN));

    ConstraintExpression expression = new ConstraintExpression.And(List.of(
        new ConstraintExpression.Or(List.of(a, b)),
        c));

    assertEquals("((a OR b) AND c)", expression.toInterlis());
  }

  @Test
  void rejectsInvalidTypedCombinationsEarly() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConstraintExpression.Sum(new ConstraintExpression.Path(
            "items->status",
            ConstraintExpression.Type.collection(ENUM))));

    assertThrows(
        IllegalArgumentException.class,
        () -> new ConstraintExpression.Add(
            new ConstraintExpression.EnumLiteral("active"),
            new ConstraintExpression.NumericLiteral(1)));

    assertThrows(
        IllegalArgumentException.class,
        () -> new ConstraintExpression.And(List.of(
            new ConstraintExpression.NumericLiteral(1),
            new ConstraintExpression.BooleanLiteral(true))));

    assertThrows(
        IllegalArgumentException.class,
        () -> new ConstraintExpression.Comparison(
            GE,
            new ConstraintExpression.EnumLiteral("draft"),
            new ConstraintExpression.EnumLiteral("active")));
  }
}
