package ch.so.agi.mcp.constraint;

import static ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator.GE;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator.LE;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ReferenceKind.ATTRIBUTE;
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
    ConstraintExpression.Attribute value = new ConstraintExpression.Attribute(
        "value", ConstraintExpression.Type.scalar(NUMERIC));
    ConstraintExpression expression = new ConstraintExpression.And(List.of(
        new ConstraintExpression.Comparison(GE, value, new ConstraintExpression.NumericLiteral(10)),
        new ConstraintExpression.Comparison(LE, value, new ConstraintExpression.NumericLiteral(20))));

    ConstraintExpression.Reference reference = new ConstraintExpression.Reference(
        "value", ATTRIBUTE, ConstraintExpression.Type.scalar(NUMERIC));
    ConstraintModelSynthesizer.ValueDomain domain = new ConstraintModelSynthesizer.ValueDomain(
        NUMERIC,
        new ConstraintModelSynthesizer.NumericDomain(
            BigDecimal.ZERO,
            BigDecimal.valueOf(100),
            BigDecimal.ONE),
        List.of(),
        true);
    ConstraintModelSynthesizer.ReferenceBinding referenceBinding =
        new ConstraintModelSynthesizer.ReferenceBinding(reference, domain, "value", null);
    ConstraintModelSynthesizer.ModelBinding binding = new ConstraintModelSynthesizer.ModelBinding(
        "CoverageModel.Data.Item",
        Map.of("value", referenceBinding));

    ConstraintCoveragePlanner.CoveragePlan plan = ConstraintCoveragePlanner.solve(expression, binding);

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
}
