package ch.so.agi.mcp.constraint;

import static ch.so.agi.mcp.constraint.ConstraintExpression.*;
import static ch.so.agi.mcp.constraint.ConstraintExpressionEngine.*;
import static ch.so.agi.mcp.constraint.ConstraintModelSynthesizer.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintNumericRefinementTest {
  @Test
  void searchesLateNumericBoundariesAfterFastCandidatesAreExhausted() {
    var x = new Attribute("x", Type.scalar(ScalarKind.NUMERIC));
    var clauses = distractors(x, 25);
    clauses.add(compare(x, ComparisonOperator.GE, 50));
    var goal = new TestGoal(GoalKind.TRUE, new And(clauses), "late boundary");
    var result = ConstraintGoalSolver.solve(goal, binding(List.of(x), false));
    assertThat(result.solved()).isTrue();
    assertThat(result.attempts()).isGreaterThan(18).isLessThan(50000);
    assertThat((BigDecimal) result.assignment().get("x")).isGreaterThanOrEqualTo(BigDecimal.valueOf(50));
    assertThat(ConstraintGoalSolver.goalSatisfied(goal, result.assignment())).isTrue();
    // UNDEFINED remains a fast-path state even with more than 18 candidate pivots.
    var undefined = new TestGoal(GoalKind.UNDEFINED, new And(clauses), "undefined");
    var missing = ConstraintGoalSolver.solve(undefined, binding(List.of(x), false));
    assertThat(missing.solved()).isTrue();
    assertThat(missing.assignment().get("x")).isEqualTo(Undefined.INSTANCE);
    assertThat(missing.attempts()).isLessThanOrEqualTo(18);
  }

  @Test
  void sharesFiftyThousandAttemptsAcrossInitialSearchAndRefinement() {
    List<Attribute> attributes = List.of(new Attribute("x", Type.scalar(ScalarKind.NUMERIC)),
        new Attribute("y", Type.scalar(ScalarKind.NUMERIC)), new Attribute("z", Type.scalar(ScalarKind.NUMERIC)));
    List<ConstraintExpression> clauses = new ArrayList<>();
    attributes.forEach(attribute -> clauses.addAll(distractors(attribute, 40)));
    clauses.add(compare(attributes.getFirst(), ComparisonOperator.LT, 0));
    var result = ConstraintGoalSolver.solve(new TestGoal(GoalKind.TRUE, new And(clauses), "bounded refinement"), binding(attributes, true));
    assertThat(result.solved()).isFalse();
    assertThat(result.reasonCode()).isEqualTo("SOLVER_SEARCH_LIMIT");
    assertThat(result.attempts()).isEqualTo(50000);
    assertThat(ConstraintCoveragePlanner.exclusionFor(result, binding(attributes, true))).isNull();
  }

  private static List<ConstraintExpression> distractors(Attribute x, int count) {
    List<ConstraintExpression> result = new ArrayList<>();
    for (int i = 0; i < count; i++) result.add(new Or(List.of(
        compare(x, ComparisonOperator.LE, i), compare(x, ComparisonOperator.GT, i))));
    return result;
  }

  private static Comparison compare(Attribute x, ComparisonOperator operator, int value) {
    return new Comparison(operator, x, new NumericLiteral(BigDecimal.valueOf(value)));
  }

  private static ModelBinding binding(List<Attribute> attributes, boolean mandatory) {
    Map<String, ReferenceBinding> references = new LinkedHashMap<>();
    for (var attribute : attributes) references.put(attribute.name(), new ReferenceBinding(
        new Reference(attribute.name(), ReferenceKind.ATTRIBUTE, attribute.type()),
        new ValueDomain(ScalarKind.NUMERIC, new NumericDomain(BigDecimal.ZERO, BigDecimal.valueOf(100), BigDecimal.ONE),
            List.of(), mandatory), attribute.name(), null));
    return new ModelBinding("Refinement.Data.Item", references);
  }
}
