package ch.so.agi.mcp.constraint;

import static ch.so.agi.mcp.constraint.ConstraintExpression.ArgumentSemantics.ATTRIBUTE_PATH;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ArgumentSemantics.VALUE;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator.EQ;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator.GE;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator.LE;
import static ch.so.agi.mcp.constraint.ConstraintExpression.IliVersion.ILI_23;
import static ch.so.agi.mcp.constraint.ConstraintExpression.IliVersion.ILI_24;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ResultTypeRule.DECLARED;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ResultTypeRule.PROPAGATE_NULLABILITY;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.BOOLEAN;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.ENUM;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.NUMERIC;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConstraintExpressionTest {

  @Test
  void rendersSameAfuSemanticsForInterlis23And24() {
    ConstraintExpression.Path secondaryWeights = new ConstraintExpression.Path(
        "Nebenauspraegung->Gewichtung",
        ConstraintExpression.Type.collection(NUMERIC));
    ConstraintExpression.FunctionCall sum = new ConstraintExpression.FunctionCall(
        sumDefinition(),
        List.of(secondaryWeights));
    ConstraintExpression.Attribute mainWeight = new ConstraintExpression.Attribute(
        "Gewichtung",
        ConstraintExpression.Type.scalar(NUMERIC));
    ConstraintExpression.NumericLiteral hundred = new ConstraintExpression.NumericLiteral(100);
    ConstraintExpression.FunctionCall total = new ConstraintExpression.FunctionCall(
        addDefinition(),
        List.of(sum, mainWeight));

    ConstraintExpression expression = new ConstraintExpression.Or(List.of(
        new ConstraintExpression.And(List.of(
            new ConstraintExpression.Defined(sum),
            new ConstraintExpression.Comparison(EQ, total, hundred))),
        new ConstraintExpression.And(List.of(
            new ConstraintExpression.Comparison(EQ, mainWeight, hundred),
            new ConstraintExpression.Not(new ConstraintExpression.Defined(sum))))));

    assertEquals(
        "(DEFINED(Math.sum(\"Nebenauspraegung->Gewichtung\")) AND Math.add(Math.sum(\"Nebenauspraegung->Gewichtung\"), Gewichtung) == 100)"
            + " OR (Gewichtung == 100 AND NOT(DEFINED(Math.sum(\"Nebenauspraegung->Gewichtung\"))))",
        expression.toInterlis(ILI_23));
    assertEquals(
        "(DEFINED(Math_V2.sum(\"Nebenauspraegung->Gewichtung\")) AND (Math_V2.sum(\"Nebenauspraegung->Gewichtung\") + Gewichtung) == 100)"
            + " OR (Gewichtung == 100 AND NOT(DEFINED(Math_V2.sum(\"Nebenauspraegung->Gewichtung\"))))",
        expression.toInterlis(ILI_24));

    assertEquals(ConstraintExpression.Type.scalar(BOOLEAN), expression.type());
    assertEquals("COLLECTION_SUM", sum.semanticId());
    assertEquals("NUMERIC_ADD", total.semanticId());

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
  void languageProfilesDescribeVersionSpecificStandardModels() {
    ConstraintExpression.LanguageProfile ili23 = ConstraintExpression.LanguageProfile.forVersion(ILI_23);
    ConstraintExpression.LanguageProfile ili24 = ConstraintExpression.LanguageProfile.forVersion(ILI_24);

    assertEquals("Math", ili23.mathModel());
    assertEquals("Text", ili23.textModel());
    assertEquals(false, ili23.nativeArithmeticOperators());

    assertEquals("Math_V2", ili24.mathModel());
    assertEquals("Text_V2", ili24.textModel());
    assertEquals(true, ili24.nativeArithmeticOperators());
  }

  @Test
  void genericFunctionCallAlsoCoversTextFunctions() {
    ConstraintExpression.Attribute code = new ConstraintExpression.Attribute(
        "Code",
        ConstraintExpression.Type.scalar(TEXT));
    ConstraintExpression.FunctionCall startsWith = new ConstraintExpression.FunctionCall(
        startsWithDefinition(),
        List.of(code, new ConstraintExpression.TextLiteral("SO")));

    assertEquals(ConstraintExpression.Type.scalar(BOOLEAN), startsWith.type());
    assertEquals("TEXT_STARTS_WITH", startsWith.semanticId());
    assertEquals("Text.startsWith(Code, \"SO\")", startsWith.toInterlis(ILI_23));
    assertEquals("Text_V2.startsWith(Code, \"SO\")", startsWith.toInterlis(ILI_24));
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
    assertEquals("(value >= 10 AND value <= 20)", numeric.toInterlis(ILI_23));

    ConstraintExpression.Attribute enabled = new ConstraintExpression.Attribute(
        "enabled",
        ConstraintExpression.Type.scalar(BOOLEAN));
    assertEquals(
        "enabled == #true",
        new ConstraintExpression.Comparison(
            EQ,
            enabled,
            new ConstraintExpression.BooleanLiteral(true)).toInterlis(ILI_24));

    ConstraintExpression.Attribute status = new ConstraintExpression.Attribute(
        "status",
        ConstraintExpression.Type.scalar(ENUM));
    assertEquals(
        "status == #active",
        new ConstraintExpression.Comparison(
            EQ,
            status,
            new ConstraintExpression.EnumLiteral("active")).toInterlis(ILI_23));
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

    assertEquals("((a OR b) AND c)", expression.toInterlis(ILI_23));
  }

  @Test
  void rejectsInvalidTypedFunctionCallsAndLogicalCombinationsEarly() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConstraintExpression.FunctionCall(
            sumDefinition(),
            List.of(new ConstraintExpression.Path(
                "items->status",
                ConstraintExpression.Type.collection(ENUM)))));

    assertThrows(
        IllegalArgumentException.class,
        () -> new ConstraintExpression.FunctionCall(
            addDefinition(),
            List.of(
                new ConstraintExpression.EnumLiteral("active"),
                new ConstraintExpression.NumericLiteral(1))));

    assertThrows(
        IllegalArgumentException.class,
        () -> new ConstraintExpression.FunctionCall(
            sumDefinition(),
            List.of(new ConstraintExpression.NumericLiteral(1))));

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

  private ConstraintExpression.FunctionDefinition addDefinition() {
    return new ConstraintExpression.FunctionDefinition(
        "NUMERIC_ADD",
        List.of(
            new ConstraintExpression.ArgumentSpec(ConstraintExpression.Type.scalar(NUMERIC), VALUE),
            new ConstraintExpression.ArgumentSpec(ConstraintExpression.Type.scalar(NUMERIC), VALUE)),
        ConstraintExpression.Type.scalar(NUMERIC),
        PROPAGATE_NULLABILITY,
        Map.of(
            ILI_23, new ConstraintExpression.FunctionSyntax("Math.add"),
            ILI_24, new ConstraintExpression.InfixSyntax("+")));
  }

  private ConstraintExpression.FunctionDefinition sumDefinition() {
    return new ConstraintExpression.FunctionDefinition(
        "COLLECTION_SUM",
        List.of(new ConstraintExpression.ArgumentSpec(
            ConstraintExpression.Type.collection(NUMERIC),
            ATTRIBUTE_PATH)),
        ConstraintExpression.Type.optionalScalar(NUMERIC),
        DECLARED,
        Map.of(
            ILI_23, new ConstraintExpression.FunctionSyntax("Math.sum"),
            ILI_24, new ConstraintExpression.FunctionSyntax("Math_V2.sum")));
  }

  private ConstraintExpression.FunctionDefinition startsWithDefinition() {
    return new ConstraintExpression.FunctionDefinition(
        "TEXT_STARTS_WITH",
        List.of(
            new ConstraintExpression.ArgumentSpec(ConstraintExpression.Type.scalar(TEXT), VALUE),
            new ConstraintExpression.ArgumentSpec(ConstraintExpression.Type.scalar(TEXT), VALUE)),
        ConstraintExpression.Type.scalar(BOOLEAN),
        PROPAGATE_NULLABILITY,
        Map.of(
            ILI_23, new ConstraintExpression.FunctionSyntax("Text.startsWith"),
            ILI_24, new ConstraintExpression.FunctionSyntax("Text_V2.startsWith")));
  }
}
