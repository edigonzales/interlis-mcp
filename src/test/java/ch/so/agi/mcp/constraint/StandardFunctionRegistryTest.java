package ch.so.agi.mcp.constraint;

import static ch.so.agi.mcp.constraint.ConstraintExpression.ArgumentSemantics.ATTRIBUTE_PATH;
import static ch.so.agi.mcp.constraint.ConstraintExpression.IliVersion.ILI_23;
import static ch.so.agi.mcp.constraint.ConstraintExpression.IliVersion.ILI_24;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.NUMERIC;
import static ch.so.agi.mcp.constraint.StandardFunctionRegistry.Family.MATH;
import static ch.so.agi.mcp.constraint.StandardFunctionRegistry.Family.TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.constraint.ConstraintExpression.Attribute;
import ch.so.agi.mcp.constraint.ConstraintExpression.FunctionCall;
import ch.so.agi.mcp.constraint.ConstraintExpression.NumericLiteral;
import ch.so.agi.mcp.constraint.ConstraintExpression.Path;
import ch.so.agi.mcp.constraint.ConstraintExpression.TextLiteral;
import ch.so.agi.mcp.constraint.ConstraintExpression.Type;
import ch.so.agi.mcp.constraint.StandardFunctionRegistry.StandardFunction;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class StandardFunctionRegistryTest {

  @Test
  void coversCompleteMathAndTextCatalogWithoutDuplicateSemanticIds() {
    assertEquals(30, StandardFunctionRegistry.functions(MATH).size());
    assertEquals(24, StandardFunctionRegistry.functions(TEXT).size());
    assertEquals(54, StandardFunctionRegistry.all().size());

    Set<String> semanticIds = StandardFunctionRegistry.all().stream()
        .map(StandardFunction::semanticId)
        .collect(Collectors.toSet());
    assertEquals(54, semanticIds.size());

    assertEquals(54, StandardFunctionRegistry.all().stream()
        .map(function -> function.qualifiedName(ILI_23))
        .distinct()
        .count());
    assertEquals(54, StandardFunctionRegistry.all().stream()
        .map(function -> function.qualifiedName(ILI_24))
        .distinct()
        .count());
  }

  @Test
  void maps23And24FunctionNamesAndNativeOperatorToSameSemantics() {
    StandardFunction add23 = StandardFunctionRegistry
        .findByQualifiedName(ILI_23, "Math.add")
        .orElseThrow();
    StandardFunction add24 = StandardFunctionRegistry
        .findByQualifiedName(ILI_24, "Math_V2.add")
        .orElseThrow();
    StandardFunction plus24 = StandardFunctionRegistry
        .findByOperator(ILI_24, "+")
        .orElseThrow();

    assertSame(add23, add24);
    assertSame(add23, plus24);
    assertEquals("NUMERIC_ADD", add23.semanticId());
    assertTrue(StandardFunctionRegistry.findByOperator(ILI_23, "+").isEmpty());

    FunctionCall call = new FunctionCall(
        add23.definition(),
        List.of(
            new Attribute("A", Type.scalar(NUMERIC)),
            new NumericLiteral(5)));
    assertEquals("Math.add(A, 5)", call.toInterlis(ILI_23));
    assertEquals("(A + 5)", call.toInterlis(ILI_24));
  }

  @Test
  void aggregateFunctionsCarryAttributePathSemanticsAndNullableResult() {
    StandardFunction sum = StandardFunctionRegistry
        .findByQualifiedName(ILI_24, "Math_V2.sum")
        .orElseThrow();

    assertEquals("COLLECTION_SUM", sum.semanticId());
    assertEquals("Math.sum(attributePath: TEXT): NUMERIC", sum.signature(ILI_23));
    assertEquals("Math_V2.sum(attributePath: TEXT): NUMERIC", sum.signature(ILI_24));
    assertEquals(ATTRIBUTE_PATH, sum.parameters().getFirst().semantics());
    assertTrue(sum.parameters().getFirst().irType().isCollectionOf(NUMERIC));
    assertTrue(sum.definition().declaredResultType().nullable());

    FunctionCall call = new FunctionCall(
        sum.definition(),
        List.of(new Path("Nebenauspraegung->Gewichtung", Type.collection(NUMERIC))));
    assertEquals("Math.sum(\"Nebenauspraegung->Gewichtung\")", call.toInterlis(ILI_23));
    assertEquals("Math_V2.sum(\"Nebenauspraegung->Gewichtung\")", call.toInterlis(ILI_24));
  }

  @Test
  void textFunctionsUseSameSemanticDefinitionAcrossVersions() {
    StandardFunction startsWith = StandardFunctionRegistry
        .findBySemanticId("TEXT_STARTS_WITH")
        .orElseThrow();

    assertEquals("Text.startsWith(val: TEXT; prefix: TEXT): BOOLEAN", startsWith.signature(ILI_23));
    assertEquals("Text_V2.startsWith(val: TEXT; prefix: TEXT): BOOLEAN", startsWith.signature(ILI_24));

    FunctionCall call = new FunctionCall(
        startsWith.definition(),
        List.of(new TextLiteral("SO123"), new TextLiteral("SO")));
    assertEquals("Text.startsWith(\"SO123\", \"SO\")", call.toInterlis(ILI_23));
    assertEquals("Text_V2.startsWith(\"SO123\", \"SO\")", call.toInterlis(ILI_24));
  }

  @Test
  void rejectsUnknownNamesAndOperatorsWithoutGuessing() {
    assertFalse(StandardFunctionRegistry.findByQualifiedName(ILI_24, "Math_V2.unknown").isPresent());
    assertFalse(StandardFunctionRegistry.findBySemanticId("UNKNOWN_FUNCTION").isPresent());
    assertFalse(StandardFunctionRegistry.findByOperator(ILI_24, "%").isPresent());
  }
}
