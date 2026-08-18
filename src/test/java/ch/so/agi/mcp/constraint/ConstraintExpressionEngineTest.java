package ch.so.agi.mcp.constraint;

import static ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator.EQ;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator.GE;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.NUMERIC;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.TEXT;
import static ch.so.agi.mcp.constraint.ConstraintExpressionEngine.GoalKind.DEFINED;
import static ch.so.agi.mcp.constraint.ConstraintExpressionEngine.GoalKind.FALSE;
import static ch.so.agi.mcp.constraint.ConstraintExpressionEngine.GoalKind.TRUE;
import static ch.so.agi.mcp.constraint.ConstraintExpressionEngine.GoalKind.UNDEFINED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintExpressionEngineTest {

  @Test
  void evaluatesAfuSumPresenceRuleWithoutKnowingInterlisVersion() {
    ConstraintExpression.Path secondaryWeights = new ConstraintExpression.Path(
        "Nebenauspraegung->Gewichtung",
        ConstraintExpression.Type.collection(NUMERIC));
    ConstraintExpression.FunctionCall sum = call("COLLECTION_SUM", secondaryWeights);
    ConstraintExpression.Attribute mainWeight = new ConstraintExpression.Attribute(
        "Gewichtung",
        ConstraintExpression.Type.scalar(NUMERIC));
    ConstraintExpression.NumericLiteral hundred = new ConstraintExpression.NumericLiteral(100);
    ConstraintExpression.FunctionCall total = call("NUMERIC_ADD", sum, mainWeight);

    ConstraintExpression expression = new ConstraintExpression.Or(List.of(
        new ConstraintExpression.And(List.of(
            new ConstraintExpression.Defined(sum),
            new ConstraintExpression.Comparison(EQ, total, hundred))),
        new ConstraintExpression.And(List.of(
            new ConstraintExpression.Comparison(EQ, mainWeight, hundred),
            new ConstraintExpression.Not(new ConstraintExpression.Defined(sum))))));

    assertTrue(ConstraintExpressionEngine.evaluateConstraint(
        expression,
        ConstraintExpressionEngine.EvaluationContext.of(Map.of("Gewichtung", 100))));
    assertFalse(ConstraintExpressionEngine.evaluateConstraint(
        expression,
        ConstraintExpressionEngine.EvaluationContext.of(Map.of("Gewichtung", 99))));
    assertTrue(ConstraintExpressionEngine.evaluateConstraint(
        expression,
        ConstraintExpressionEngine.EvaluationContext.of(Map.of(
            "Gewichtung", 60,
            "Nebenauspraegung->Gewichtung", List.of(20, 20)))));
    assertFalse(ConstraintExpressionEngine.evaluateConstraint(
        expression,
        ConstraintExpressionEngine.EvaluationContext.of(Map.of(
            "Gewichtung", 60,
            "Nebenauspraegung->Gewichtung", List.of(20)))));
  }

  @Test
  void evaluatesComparisonsArithmeticAggregatesAndUndefined() {
    ConstraintExpression.Attribute a = new ConstraintExpression.Attribute(
        "A", ConstraintExpression.Type.scalar(NUMERIC));
    ConstraintExpression.Attribute b = new ConstraintExpression.Attribute(
        "B", ConstraintExpression.Type.scalar(NUMERIC));
    ConstraintExpression.FunctionCall add = call("NUMERIC_ADD", a, b);
    ConstraintExpression expression = new ConstraintExpression.Comparison(
        GE, add, new ConstraintExpression.NumericLiteral(10));

    assertTrue(ConstraintExpressionEngine.evaluateConstraint(
        expression,
        ConstraintExpressionEngine.EvaluationContext.of(Map.of("A", 4, "B", 6))));
    assertFalse(ConstraintExpressionEngine.evaluateConstraint(
        expression,
        ConstraintExpressionEngine.EvaluationContext.of(Map.of("A", 4))));

    ConstraintExpression.FunctionCall avg = call(
        "COLLECTION_AVG",
        new ConstraintExpression.Path("items->value", ConstraintExpression.Type.collection(NUMERIC)));
    Object average = ConstraintExpressionEngine.evaluate(
        avg,
        ConstraintExpressionEngine.EvaluationContext.of(Map.of("items->value", List.of(10, 20))));
    assertTrue(average instanceof BigDecimal);
    assertEquals(0, ((BigDecimal) average).compareTo(new BigDecimal("15")));
    assertEquals(
        ConstraintExpressionEngine.Undefined.INSTANCE,
        ConstraintExpressionEngine.evaluate(
            avg,
            ConstraintExpressionEngine.EvaluationContext.of(Map.of("items->value", List.of()))));
  }

  @Test
  void evaluatesStraightforwardTextSemanticsFromRegistry() {
    ConstraintExpression.Attribute code = new ConstraintExpression.Attribute(
        "Code", ConstraintExpression.Type.scalar(TEXT));
    ConstraintExpression.FunctionCall startsWith = call(
        "TEXT_STARTS_WITH", code, new ConstraintExpression.TextLiteral("SO"));
    ConstraintExpression.FunctionCall upper = call("TEXT_TO_UPPER_CASE", code);

    var context = ConstraintExpressionEngine.EvaluationContext.of(Map.of("Code", "So123"));
    assertFalse((Boolean) ConstraintExpressionEngine.evaluate(startsWith, context));
    assertEquals("SO123", ConstraintExpressionEngine.evaluate(upper, context));
  }

  @Test
  void derivesTruthAndPresenceGoalsFromExpressionTree() {
    ConstraintExpression.Path values = new ConstraintExpression.Path(
        "items->value", ConstraintExpression.Type.collection(NUMERIC));
    ConstraintExpression.FunctionCall sum = call("COLLECTION_SUM", values);
    ConstraintExpression.Defined defined = new ConstraintExpression.Defined(sum);
    ConstraintExpression.Comparison comparison = new ConstraintExpression.Comparison(
        GE, sum, new ConstraintExpression.NumericLiteral(10));
    ConstraintExpression expression = new ConstraintExpression.And(List.of(defined, comparison));

    List<ConstraintExpressionEngine.TestGoal> goals = ConstraintExpressionEngine.testGoals(expression);

    assertTrue(goals.contains(new ConstraintExpressionEngine.TestGoal(TRUE, expression, "constraint witness")));
    assertTrue(goals.contains(new ConstraintExpressionEngine.TestGoal(FALSE, expression, "constraint counterexample")));
    assertTrue(goals.contains(new ConstraintExpressionEngine.TestGoal(DEFINED, sum, "DEFINED branch")));
    assertTrue(goals.contains(new ConstraintExpressionEngine.TestGoal(UNDEFINED, sum, "NOT DEFINED branch")));
    assertTrue(goals.stream().anyMatch(goal -> goal.kind() == TRUE && goal.expression().equals(comparison)));
    assertTrue(goals.stream().anyMatch(goal -> goal.kind() == FALSE && goal.expression().equals(comparison)));
  }

  @Test
  void unsupportedFunctionSemanticsFailsExplicitlyInsteadOfGuessing() {
    ConstraintExpression.FunctionCall sqrt = call(
        "NUMERIC_SQRT", new ConstraintExpression.NumericLiteral(4));

    ConstraintExpressionEngine.UnsupportedFunctionSemanticsException ex = assertThrows(
        ConstraintExpressionEngine.UnsupportedFunctionSemanticsException.class,
        () -> ConstraintExpressionEngine.evaluate(
            sqrt, ConstraintExpressionEngine.EvaluationContext.of(Map.of())));
    assertEquals("NUMERIC_SQRT", ex.semanticId());
  }

  private ConstraintExpression.FunctionCall call(
      String semanticId,
      ConstraintExpression... arguments) {
    StandardFunctionRegistry.StandardFunction function = StandardFunctionRegistry.findBySemanticId(semanticId)
        .orElseThrow();
    return new ConstraintExpression.FunctionCall(function.definition(), List.of(arguments));
  }
}
