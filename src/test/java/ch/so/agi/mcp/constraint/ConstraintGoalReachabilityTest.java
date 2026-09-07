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

class ConstraintGoalReachabilityTest {
  private static final Attribute X = new Attribute("x", Type.scalar(ScalarKind.NUMERIC));

  @Test
  void numericPartitionsAgreeWithExhaustiveSmallPrecisionDomain() {
    var binding = binding("x", ScalarKind.NUMERIC, false);
    List<Object> values = new ArrayList<>();
    for (int i = -10; i <= 10; i++) values.add(BigDecimal.valueOf(i, 1));
    values.add(Undefined.INSTANCE);
    for (ComparisonOperator left : ComparisonOperator.values()) {
      for (ComparisonOperator right : ComparisonOperator.values()) {
        for (String pivot : List.of("-1.1", "-0.25", "0", "0.35", "1.1")) {
          var a = compare(X, left, new BigDecimal(pivot));
          var b = compare(X, right, BigDecimal.ZERO);
          for (ConstraintExpression expression : List.of(new And(List.of(a, b)),
              new Or(List.of(a, b)), new Implies(a, b), new Not(a))) {
            for (GoalKind state : List.of(GoalKind.TRUE, GoalKind.FALSE, GoalKind.UNDEFINED)) {
              boolean reachable = values.stream().anyMatch(value -> {
                Object actual = evaluate(expression, EvaluationContext.of(Map.of("x", value)));
                return switch (state) {
                  case TRUE -> Boolean.TRUE.equals(actual);
                  case FALSE -> Boolean.FALSE.equals(actual);
                  default -> isUndefined(actual);
                };
              });
              var result = ConstraintGoalReachability.analyze(new TestGoal(state, expression, "exhaustive check"), binding);
              assertThat(result.status()).as("%s %s: %s", state, expression, result)
                  .isEqualTo(reachable ? ConstraintGoalReachability.Status.UNKNOWN
                      : ConstraintGoalReachability.Status.PROVEN_UNREACHABLE);
            }
          }
        }
      }
    }
  }

  @Test
  void correlatesRepeatedReferencesAndEnumeratesAllMandatoryEnumValues() {
    var code = new Attribute("code", Type.scalar(ScalarKind.ENUM));
    var ph = new Path("soil->ph", Type.scalar(ScalarKind.NUMERIC));
    var domain = new ValueDomain(ScalarKind.NUMERIC, new NumericDomain(BigDecimal.ZERO, BigDecimal.TEN,
        new BigDecimal("0.1")), List.of(), true);
    var binding = new ModelBinding("Reach.Data.Sample", Map.of(
        "soil->ph", new ReferenceBinding(new Reference("soil->ph", ReferenceKind.PATH, ph.type()), domain, "ph", null,
            List.of(new NavigationBinding(NavigationKind.COMPOSITION, "soil", "Reach.Data.Soil", 0, 1, false, null))),
        "code", new ReferenceBinding(new Reference("code", ReferenceKind.ATTRIBUTE, code.type()),
            new ValueDomain(ScalarKind.ENUM, null, List.of("A", "B"), true), "code", null)));
    var branchA = new And(List.of(compare(ph, ComparisonOperator.GT, BigDecimal.ZERO),
        new Comparison(ComparisonOperator.EQ, code, new EnumLiteral("A"))));
    var branchB = new And(List.of(compare(ph, ComparisonOperator.LE, BigDecimal.ZERO),
        new Comparison(ComparisonOperator.EQ, code, new EnumLiteral("B"))));
    var impossible = new And(List.of(new Not(new Defined(ph)), new Not(branchA), new Not(branchB)));
    assertThat(ConstraintGoalReachability.analyze(new TestGoal(GoalKind.TRUE, impossible, "strict"), binding).status())
        .isEqualTo(ConstraintGoalReachability.Status.PROVEN_UNREACHABLE);
    assertThat(ConstraintGoalReachability.analyze(new TestGoal(GoalKind.UNDEFINED, ph, "missing soil"), binding).status())
        .isEqualTo(ConstraintGoalReachability.Status.UNKNOWN);
    assertThat(ConstraintGoalReachability.analyze(new TestGoal(GoalKind.UNDEFINED, code, "mandatory enum"), binding).status())
        .isEqualTo(ConstraintGoalReachability.Status.PROVEN_UNREACHABLE);
  }

  @Test
  void unsupportedExpressionsAndAnalysisLimitsRemainUnknown() {
    var abs = new FunctionCall(StandardFunctionRegistry.findBySemanticId("NUMERIC_ABS").orElseThrow().definition(), List.of(X));
    assertUnknown(new TestGoal(GoalKind.UNDEFINED, abs, "function"), binding("x", ScalarKind.NUMERIC, true));
    assertUnknown(new TestGoal(GoalKind.FALSE, new Comparison(ComparisonOperator.EQ, X, X), "reference equality"),
        binding("x", ScalarKind.NUMERIC, true));
    var expressions = new ArrayList<ConstraintExpression>();
    var references = new LinkedHashMap<String, ReferenceBinding>();
    for (int i = 0; i < 16; i++) {
      String name = "b" + i;
      expressions.add(new Attribute(name, Type.scalar(ScalarKind.BOOLEAN)));
      references.putAll(binding(name, ScalarKind.BOOLEAN, true).references());
    }
    var limit = ConstraintGoalReachability.analyze(new TestGoal(GoalKind.TRUE,
        new And(expressions), "state limit"), new ModelBinding("Reach.Data.Sample", references));
    assertThat(limit.status()).isEqualTo(ConstraintGoalReachability.Status.UNKNOWN);
    assertThat(limit.justification()).contains("50000");
  }

  @Test
  void countsCoveredGoalsSeparatelyFromDeduplicatedFixturesAndKeepsRequiredOutcomes() {
    var b = new Attribute("b", Type.scalar(ScalarKind.BOOLEAN));
    var model = binding("b", ScalarKind.BOOLEAN, true);
    var plan = ConstraintCoveragePlanner.solve(new And(List.of(b, b)), model);
    assertThat(plan.unsolved()).isEmpty();
    assertThat(plan.excluded()).isNotEmpty();
    assertThat(ConstraintCoveragePlanner.solvedGoalCount(plan)).isGreaterThan(plan.cases().size());
    assertThat(plan.cases()).allSatisfy(item -> item.coveredGoals().forEach(goal ->
        assertThat(ConstraintGoalSolver.goalSatisfied(goal, item.solution().assignment())).isTrue()));
    var tautology = ConstraintCoveragePlanner.solve(new Or(List.of(b, new Not(b))), model);
    assertThat(tautology.unsolved()).anySatisfy(item -> assertThat(item.goal().reason()).isEqualTo("constraint counterexample"));
    assertThat(tautology.excluded()).noneSatisfy(item -> assertThat(item.goal().reason()).isEqualTo("constraint counterexample"));
    var contradiction = ConstraintCoveragePlanner.solve(new And(List.of(b, new Not(b))), model);
    assertThat(contradiction.unsolved()).anySatisfy(item -> assertThat(item.goal().reason()).isEqualTo("constraint witness"));
  }

  @Test
  void sampledSearchExhaustionUnsupportedSemanticsAndLimitsAreNeverExclusionEvidence() {
    var b = new Attribute("b", Type.scalar(ScalarKind.BOOLEAN));
    var model = binding("b", ScalarKind.BOOLEAN, true);
    var impossible = new TestGoal(GoalKind.TRUE, new And(List.of(b, new Not(b))), "structural contradiction");
    for (String reason : List.of("SOLVER_SEARCH_LIMIT", "UNSUPPORTED_FUNCTION_SEMANTICS", "OBJECT_GRAPH_SYNTHESIS_FAILED")) {
      var failure = new ConstraintGoalSolver.Solution(impossible, false, Map.of(), 50_000, reason, "unresolved");
      assertThat(ConstraintCoveragePlanner.exclusionFor(failure, model)).isNull();
    }
    var code = new Attribute("code", Type.scalar(ScalarKind.ENUM));
    List<String> values = java.util.stream.IntStream.range(0, 25).mapToObj(i -> "E" + i).toList();
    var enumModel = new ModelBinding("Reach.Data.Sample", Map.of("code",
        new ReferenceBinding(new Reference("code", ReferenceKind.ATTRIBUTE, code.type()),
            new ValueDomain(ScalarKind.ENUM, null, values, true), "code", null)));
    var last = new TestGoal(GoalKind.TRUE,
        new Comparison(ComparisonOperator.EQ, code, new EnumLiteral("E24")), "enum outside sample");
    var failure = ConstraintGoalSolver.solve(last, enumModel);
    assertThat(failure.reasonCode()).isEqualTo("NO_SOLUTION_FOUND");
    assertThat(ConstraintCoveragePlanner.exclusionFor(failure, enumModel)).isNull();
  }

  @Test
  void numericCandidateCapRetainsUndefinedStateForOptionalPaths() {
    var path = new Path("soil->ph", Type.scalar(ScalarKind.NUMERIC));
    var model = new ModelBinding("Reach.Data.Sample", Map.of("soil->ph",
        new ReferenceBinding(new Reference("soil->ph", ReferenceKind.PATH, path.type()),
            new ValueDomain(ScalarKind.NUMERIC, new NumericDomain(BigDecimal.ZERO, BigDecimal.TEN,
                new BigDecimal("0.1")), List.of(), true), "ph", null,
            List.of(new NavigationBinding(NavigationKind.COMPOSITION, "soil", "Reach.Data.Soil", 0, 1, false, null)))));
    List<ConstraintExpression> clauses = new ArrayList<>();
    List<StateCondition> conditions = new ArrayList<>();
    conditions.add(new StateCondition(GoalKind.UNDEFINED, path));
    for (int i = 0; i < 20; i++) {
      var comparison = compare(path, ComparisonOperator.EQ, BigDecimal.valueOf(i, 1));
      clauses.add(comparison);
      conditions.add(new StateCondition(GoalKind.UNDEFINED, comparison));
    }
    var solved = ConstraintGoalSolver.solve(new TestGoal(GoalKind.TRUE, new And(clauses),
        "undefined with many pivots", conditions), model);
    assertThat(solved.solved()).as(solved.toString()).isTrue();
    assertThat(solved.assignment().get("soil->ph")).isEqualTo(Undefined.INSTANCE);
    assertThat(solved.attempts()).isLessThanOrEqualTo(18);
  }

  @Test
  void provesOnlyDefinednessOfStrictArithmeticWithoutInferringNumericFunctionResults() {
    var add = new FunctionCall(StandardFunctionRegistry.findBySemanticId("NUMERIC_ADD").orElseThrow().definition(),
        List.of(X, new NumericLiteral(1)));
    var comparison = compare(add, ComparisonOperator.EQ, BigDecimal.ONE);
    var impossible = new And(List.of(new Not(new Defined(X)), comparison));
    var model = binding("x", ScalarKind.NUMERIC, false);
    var result = ConstraintGoalReachability.analyze(new TestGoal(GoalKind.TRUE, impossible, "undefined argument"), model);
    assertThat(result.status()).isEqualTo(ConstraintGoalReachability.Status.PROVEN_UNREACHABLE);
    assertThat(result.justification()).contains("definedness", "no numerical function results");
    for (Object value : List.of(new BigDecimal("-1"), BigDecimal.ZERO, BigDecimal.ONE, Undefined.INSTANCE)) {
      assertThat(evaluate(impossible, EvaluationContext.of(Map.of("x", value)))).isNotEqualTo(true);
    }
    assertUnknown(new TestGoal(GoalKind.TRUE, compare(add, ComparisonOperator.EQ, new BigDecimal("1000")),
        "numeric function result"), model);
  }

  @Test
  void optionalBooleanPlansCoverAllImpliesStatesAndDominatingUnknownNeighbours() {
    var a = new Attribute("a", Type.scalar(ScalarKind.BOOLEAN));
    var b = new Attribute("b", Type.scalar(ScalarKind.BOOLEAN));
    var references = new LinkedHashMap<>(binding("a", ScalarKind.BOOLEAN, false).references());
    references.putAll(binding("b", ScalarKind.BOOLEAN, false).references());
    var model = new ModelBinding("Reach.Data.Sample", references);
    var implies = ConstraintCoveragePlanner.solve(new Implies(a, b), model);
    assertThat(implies.unsolved()).isEmpty();
    assertThat(implies.cases()).hasSize(9);
    var or = ConstraintCoveragePlanner.solve(new Or(List.of(a, b)), model);
    var and = ConstraintCoveragePlanner.solve(new And(List.of(a, b)), model);
    assertThat(or.cases()).anySatisfy(item -> assertThat(evaluate(new Or(List.of(a, b)),
        EvaluationContext.of(item.solution().assignment()))).isEqualTo(NotComputable.INSTANCE));
    assertThat(and.cases()).anySatisfy(item -> assertThat(evaluate(new And(List.of(a, b)),
        EvaluationContext.of(item.solution().assignment()))).isEqualTo(NotComputable.INSTANCE));
    // Later unknown operands do not matter; unknown predecessors prevent reaching a branch.
    var orGoal = or.cases().stream().flatMap(item -> item.coveredGoals().stream())
        .filter(goal -> goal.reason().equals("OR branch 1 dominates true")).findFirst().orElseThrow();
    assertThat(ConstraintGoalSolver.goalSatisfied(orGoal, Map.of("a", true, "b", Undefined.INSTANCE))).isTrue();
    var andGoal = and.cases().stream().flatMap(item -> item.coveredGoals().stream())
        .filter(goal -> goal.reason().equals("AND operand 1 dominates false")).findFirst().orElseThrow();
    assertThat(ConstraintGoalSolver.goalSatisfied(andGoal, Map.of("a", false, "b", Undefined.INSTANCE))).isTrue();
    var laterOr = or.cases().stream().flatMap(item -> item.coveredGoals().stream())
        .filter(goal -> goal.reason().equals("OR branch 2 dominates true")).findFirst().orElseThrow();
    var laterAnd = and.cases().stream().flatMap(item -> item.coveredGoals().stream())
        .filter(goal -> goal.reason().equals("AND operand 2 dominates false")).findFirst().orElseThrow();
    assertThat(ConstraintGoalSolver.goalSatisfied(laterOr, Map.of("a", Undefined.INSTANCE, "b", true))).isFalse();
    assertThat(ConstraintGoalSolver.goalSatisfied(laterAnd, Map.of("a", Undefined.INSTANCE, "b", false))).isFalse();
    assertThat(ConstraintGoalSolver.goalSatisfied(laterOr, Map.of("a", false, "b", true))).isTrue();
    assertThat(ConstraintGoalSolver.goalSatisfied(laterAnd, Map.of("a", true, "b", false))).isTrue();
  }

  static ModelBinding binding(String name, ScalarKind kind, boolean mandatory) {
    var type = Type.scalar(kind);
    return new ModelBinding("Reach.Data.Sample", Map.of(name,
        new ReferenceBinding(new Reference(name, ReferenceKind.ATTRIBUTE, type), new ValueDomain(kind,
            kind == ScalarKind.NUMERIC ? new NumericDomain(new BigDecimal("-1.0"), new BigDecimal("1.0"),
                new BigDecimal("0.1")) : null, List.of(), mandatory), name, null)));
  }

  private static Comparison compare(ConstraintExpression operand, ComparisonOperator operator, BigDecimal value) {
    return new Comparison(operator, operand, new NumericLiteral(value));
  }

  private static void assertUnknown(TestGoal goal, ModelBinding binding) {
    assertThat(ConstraintGoalReachability.analyze(goal, binding).status()).isEqualTo(ConstraintGoalReachability.Status.UNKNOWN);
  }
}
