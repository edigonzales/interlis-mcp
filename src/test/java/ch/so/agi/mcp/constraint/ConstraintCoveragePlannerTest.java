package ch.so.agi.mcp.constraint;

import static ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator.EQ;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator.GE;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator.LE;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ReferenceKind.ATTRIBUTE;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ReferenceKind.PATH;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.BOOLEAN;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.NUMERIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ConstraintCoveragePlannerTest {

  @Test
  void derivesNumericBoundariesFromIrAndBoundDomain() {
    ConstraintExpression.Attribute value = numeric("value");
    ConstraintExpression expression = new ConstraintExpression.And(List.of(
        new ConstraintExpression.Comparison(GE, value, new ConstraintExpression.NumericLiteral(10)),
        new ConstraintExpression.Comparison(LE, value, new ConstraintExpression.NumericLiteral(20))));

    ConstraintCoveragePlanner.CoveragePlan plan = ConstraintCoveragePlanner.solve(
        expression,
        binding(Map.of("value", numericBinding("value", BigDecimal.ZERO, BigDecimal.valueOf(100), BigDecimal.ONE))));

    Set<BigDecimal> values = plan.cases().stream()
        .map(item -> (BigDecimal) item.solution().assignment().get("value"))
        .collect(Collectors.toSet());
    assertEquals(Set.of(
        BigDecimal.valueOf(9),
        BigDecimal.valueOf(10),
        BigDecimal.valueOf(20),
        BigDecimal.valueOf(21)), values);
    assertTrue(plan.unsolved().isEmpty(), String.valueOf(plan.unsolved()));
  }

  @Test
  void derivesIndependentAndAndOrBranches() {
    ConstraintExpression.Attribute a = bool("a");
    ConstraintExpression.Attribute b = bool("b");
    ConstraintModelSynthesizer.ModelBinding binding = binding(Map.of(
        "a", booleanBinding("a"),
        "b", booleanBinding("b")));

    ConstraintCoveragePlanner.CoveragePlan andPlan = ConstraintCoveragePlanner.solve(
        new ConstraintExpression.And(List.of(a, b)), binding);
    assertAssignment(andPlan, false, false);
    assertAssignment(andPlan, false, true);
    assertAssignment(andPlan, true, false);
    assertAssignment(andPlan, true, true);
    assertReason(andPlan, "AND all operands true");
    assertReason(andPlan, "AND operand 1 independently false");
    assertReason(andPlan, "AND operand 2 independently false");

    ConstraintCoveragePlanner.CoveragePlan orPlan = ConstraintCoveragePlanner.solve(
        new ConstraintExpression.Or(List.of(a, b)), binding);
    assertAssignment(orPlan, false, false);
    assertAssignment(orPlan, true, false);
    assertAssignment(orPlan, false, true);
    assertReason(orPlan, "OR all branches false");
    assertReason(orPlan, "OR branch 1 independently true");
    assertReason(orPlan, "OR branch 2 independently true");
  }

  @Test
  void derivesCompleteImpliesTruthTable() {
    ConstraintExpression.Attribute a = bool("a");
    ConstraintExpression.Attribute b = bool("b");
    ConstraintCoveragePlanner.CoveragePlan plan = ConstraintCoveragePlanner.solve(
        new ConstraintExpression.Implies(a, b),
        binding(Map.of("a", booleanBinding("a"), "b", booleanBinding("b"))));

    assertAssignment(plan, false, false);
    assertAssignment(plan, false, true);
    assertAssignment(plan, true, false);
    assertAssignment(plan, true, true);
    assertReason(plan, "IMPLIES false -> false");
    assertReason(plan, "IMPLIES false -> true");
    assertReason(plan, "IMPLIES true -> true");
    assertReason(plan, "IMPLIES true -> false violation");
  }

  @Test
  void derivesEmptyOneAndMaximumRelevantSumCardinality() {
    ConstraintExpression.Path values = new ConstraintExpression.Path(
        "items->value", ConstraintExpression.Type.collection(NUMERIC));
    ConstraintExpression.FunctionCall sum = call("COLLECTION_SUM", values);
    ConstraintModelSynthesizer.ReferenceBinding pathBinding = new ConstraintModelSynthesizer.ReferenceBinding(
        new ConstraintExpression.Reference("items->value", PATH, ConstraintExpression.Type.collection(NUMERIC)),
        numericDomain(BigDecimal.ZERO, BigDecimal.valueOf(100), BigDecimal.ONE),
        "value",
        new ConstraintModelSynthesizer.AssociationBinding(
            "CoverageModel.Data.RootItems",
            "items",
            "root",
            "CoverageModel.Data.Item",
            0,
            3,
            false));

    ConstraintCoveragePlanner.CoveragePlan plan = ConstraintCoveragePlanner.solve(
        new ConstraintExpression.Defined(sum),
        binding(Map.of("items->value", pathBinding)));

    Set<Integer> sizes = plan.cases().stream()
        .map(item -> ((List<?>) item.solution().assignment().get("items->value")).size())
        .collect(Collectors.toSet());
    assertTrue(sizes.containsAll(Set.of(0, 1, 3)), String.valueOf(plan.cases()));
    assertTrue(plan.cases().stream().anyMatch(item ->
        item.goal().reason().equals("aggregate maximum relevant cardinality 3")
            && ((List<?>) item.solution().assignment().get("items->value")).size() == 3),
        String.valueOf(plan.cases()));
  }

  @Test
  void derivesNumericFunctionDomainAndRoundingEdges() {
    ConstraintExpression.Attribute numerator = numeric("numerator");
    ConstraintExpression.Attribute denominator = numeric("denominator");
    ConstraintExpression.FunctionCall divide = call("NUMERIC_DIV", numerator, denominator);
    ConstraintCoveragePlanner.CoveragePlan dividePlan = ConstraintCoveragePlanner.solve(
        new ConstraintExpression.Defined(divide),
        binding(Map.of(
            "numerator", numericBinding("numerator", BigDecimal.valueOf(-10), BigDecimal.TEN, BigDecimal.ONE),
            "denominator", numericBinding("denominator", BigDecimal.valueOf(-10), BigDecimal.TEN, BigDecimal.ONE))));

    Set<BigDecimal> denominators = dividePlan.cases().stream()
        .map(item -> (BigDecimal) item.solution().assignment().get("denominator"))
        .collect(Collectors.toSet());
    assertTrue(denominators.containsAll(Set.of(BigDecimal.valueOf(-1), BigDecimal.ZERO, BigDecimal.ONE)),
        String.valueOf(dividePlan.cases()));

    ConstraintExpression.Attribute value = numeric("value");
    ConstraintExpression.FunctionCall round = call("NUMERIC_ROUND", value);
    ConstraintCoveragePlanner.CoveragePlan roundPlan = ConstraintCoveragePlanner.solve(
        new ConstraintExpression.Comparison(EQ, round, new ConstraintExpression.NumericLiteral(1)),
        binding(Map.of("value", numericBinding(
            "value", new BigDecimal("-1.0"), new BigDecimal("1.0"), new BigDecimal("0.1")))));

    Set<BigDecimal> roundInputs = roundPlan.cases().stream()
        .map(item -> (BigDecimal) item.solution().assignment().get("value"))
        .collect(Collectors.toSet());
    assertTrue(roundInputs.contains(new BigDecimal("-0.5")), String.valueOf(roundInputs));
    assertTrue(roundInputs.contains(new BigDecimal("0.5")), String.valueOf(roundInputs));
  }

  private ConstraintExpression.Attribute numeric(String name) {
    return new ConstraintExpression.Attribute(name, ConstraintExpression.Type.scalar(NUMERIC));
  }

  private ConstraintExpression.Attribute bool(String name) {
    return new ConstraintExpression.Attribute(name, ConstraintExpression.Type.scalar(BOOLEAN));
  }

  private ConstraintExpression.FunctionCall call(
      String semanticId,
      ConstraintExpression... arguments) {
    return new ConstraintExpression.FunctionCall(
        StandardFunctionRegistry.findBySemanticId(semanticId).orElseThrow().definition(),
        List.of(arguments));
  }

  private ConstraintModelSynthesizer.ModelBinding binding(
      Map<String, ConstraintModelSynthesizer.ReferenceBinding> references) {
    return new ConstraintModelSynthesizer.ModelBinding("CoverageModel.Data.Item", references);
  }

  private ConstraintModelSynthesizer.ReferenceBinding numericBinding(
      String name,
      BigDecimal minimum,
      BigDecimal maximum,
      BigDecimal step) {
    return new ConstraintModelSynthesizer.ReferenceBinding(
        new ConstraintExpression.Reference(name, ATTRIBUTE, ConstraintExpression.Type.scalar(NUMERIC)),
        numericDomain(minimum, maximum, step),
        name,
        null);
  }

  private ConstraintModelSynthesizer.ValueDomain numericDomain(
      BigDecimal minimum,
      BigDecimal maximum,
      BigDecimal step) {
    return new ConstraintModelSynthesizer.ValueDomain(
        NUMERIC,
        new ConstraintModelSynthesizer.NumericDomain(minimum, maximum, step),
        List.of(),
        true);
  }

  private ConstraintModelSynthesizer.ReferenceBinding booleanBinding(String name) {
    return new ConstraintModelSynthesizer.ReferenceBinding(
        new ConstraintExpression.Reference(name, ATTRIBUTE, ConstraintExpression.Type.scalar(BOOLEAN)),
        new ConstraintModelSynthesizer.ValueDomain(BOOLEAN, null, List.of(), true),
        name,
        null);
  }

  private void assertAssignment(
      ConstraintCoveragePlanner.CoveragePlan plan,
      boolean a,
      boolean b) {
    assertTrue(plan.cases().stream().anyMatch(item ->
        Boolean.valueOf(a).equals(item.solution().assignment().get("a"))
            && Boolean.valueOf(b).equals(item.solution().assignment().get("b"))),
        "Missing assignment a=" + a + ", b=" + b + ": " + plan.cases());
  }

  private void assertReason(ConstraintCoveragePlanner.CoveragePlan plan, String reason) {
    assertTrue(plan.cases().stream().anyMatch(item -> reason.equals(item.goal().reason())),
        "Missing reason '" + reason + "': " + plan.cases());
  }
}
