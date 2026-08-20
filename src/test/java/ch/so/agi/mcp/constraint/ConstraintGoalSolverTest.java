package ch.so.agi.mcp.constraint;

import static ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator.EQ;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.ENUM;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.NUMERIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.ConstraintTestTools;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintGoalSolverTest {

  private static final String MODEL = """
      INTERLIS 2.3;

      CONTRACTED TYPE MODEL Math (en) AT "http://www.interlis.ch/models"
      VERSION "2018-11-19" =
        FUNCTION add(a: NUMERIC; b: NUMERIC): NUMERIC;
        FUNCTION sqrt(a: NUMERIC): NUMERIC;
        FUNCTION sum(attributePath: TEXT): NUMERIC;
      END Math.

      MODEL SolverModel (en)
      AT "http://example.org/models"
      VERSION "2026-08-18" =
        IMPORTS Math;

        TOPIC Data =
          CLASS Main =
            Gewichtung : MANDATORY 0 .. 100;
            Status : MANDATORY (draft, active);
          END Main;

          CLASS Secondary =
            Gewichtung : MANDATORY 0 .. 100;
          END Secondary;

          ASSOCIATION MainSecondary =
            Hauptobjekt -- {1} Main;
            Nebenauspraegung -- {0..3} Secondary;
          END MainSecondary;

          CONSTRAINTS OF SolverModel.Data.Main =
            !!@ name = "WeightSum100"
            MANDATORY CONSTRAINT
              (
                DEFINED(Math.sum("Nebenauspraegung->Gewichtung"))
                AND Math.add(Math.sum("Nebenauspraegung->Gewichtung"), Gewichtung) == 100
              )
              OR
              (
                Gewichtung == 100
                AND NOT(DEFINED(Math.sum("Nebenauspraegung->Gewichtung")))
              );
          END;
        END Data;
      END SolverModel.
      """;

  private static final String CONTEXT = "SolverModel.Data.Main";

  private final IliCompilerService compilerService = new IliCompilerService();

  @Test
  void solvesAfuWitnessCounterexampleAndPresenceGoals() {
    ConstraintExpression expression = afuExpression();
    ConstraintModelSynthesizer.ModelBinding binding = binding(expression);
    List<ConstraintExpressionEngine.TestGoal> goals = ConstraintExpressionEngine.testGoals(expression);

    ConstraintGoalSolver.Solution witness = ConstraintGoalSolver.solve(goals.get(0), binding);
    ConstraintGoalSolver.Solution counterexample = ConstraintGoalSolver.solve(goals.get(1), binding);

    assertTrue(witness.solved(), String.valueOf(witness));
    assertTrue(counterexample.solved(), String.valueOf(counterexample));
    assertTrue(ConstraintExpressionEngine.evaluateConstraint(
        expression, ConstraintExpressionEngine.EvaluationContext.of(witness.assignment())));
    assertFalse(ConstraintExpressionEngine.evaluateConstraint(
        expression, ConstraintExpressionEngine.EvaluationContext.of(counterexample.assignment())));

    ConstraintExpression.FunctionCall sum = findSum(expression);
    ConstraintExpressionEngine.TestGoal defined = new ConstraintExpressionEngine.TestGoal(
        ConstraintExpressionEngine.GoalKind.DEFINED, sum, "sum defined");
    ConstraintExpressionEngine.TestGoal undefined = new ConstraintExpressionEngine.TestGoal(
        ConstraintExpressionEngine.GoalKind.UNDEFINED, sum, "sum undefined");

    ConstraintGoalSolver.Solution definedSolution = ConstraintGoalSolver.solve(defined, binding);
    ConstraintGoalSolver.Solution undefinedSolution = ConstraintGoalSolver.solve(undefined, binding);
    assertTrue(definedSolution.solved(), String.valueOf(definedSolution));
    assertTrue(undefinedSolution.solved(), String.valueOf(undefinedSolution));
    assertTrue(((List<?>) definedSolution.assignment().get("Nebenauspraegung->Gewichtung")).size() > 0);
    assertEquals(List.of(), undefinedSolution.assignment().get("Nebenauspraegung->Gewichtung"));
  }

  @Test
  void solvesAggregateTotalBeyondSingleEndpointMaximum() {
    ConstraintExpression.Path weights = new ConstraintExpression.Path(
        "Nebenauspraegung->Gewichtung",
        ConstraintExpression.Type.collection(NUMERIC));
    ConstraintExpression sum150 = new ConstraintExpression.Comparison(
        EQ,
        call("COLLECTION_SUM", weights),
        new ConstraintExpression.NumericLiteral(150));
    ConstraintModelSynthesizer.ModelBinding binding = binding(sum150);
    ConstraintExpressionEngine.TestGoal goal = new ConstraintExpressionEngine.TestGoal(
        ConstraintExpressionEngine.GoalKind.TRUE, sum150, "aggregate equality");

    ConstraintGoalSolver.Solution solution = ConstraintGoalSolver.solve(goal, binding);

    assertTrue(solution.solved(), String.valueOf(solution));
    List<?> terms = (List<?>) solution.assignment().get("Nebenauspraegung->Gewichtung");
    assertTrue(terms.size() >= 2, String.valueOf(terms));
    BigDecimal total = terms.stream()
        .map(value -> (BigDecimal) value)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertEquals(0, total.compareTo(BigDecimal.valueOf(150)));
    assertTrue(terms.stream().allMatch(value -> ((BigDecimal) value).compareTo(BigDecimal.valueOf(100)) <= 0));
  }

  @Test
  void solvesEnumGoalsFromTheBoundModelDomain() {
    ConstraintExpression expression = new ConstraintExpression.Comparison(
        EQ,
        new ConstraintExpression.Attribute("Status", ConstraintExpression.Type.scalar(ENUM)),
        new ConstraintExpression.EnumLiteral("active"));
    ConstraintModelSynthesizer.ModelBinding binding = binding(expression);

    ConstraintGoalSolver.Solution trueSolution = ConstraintGoalSolver.solve(
        new ConstraintExpressionEngine.TestGoal(
            ConstraintExpressionEngine.GoalKind.TRUE, expression, "enum true"),
        binding);
    ConstraintGoalSolver.Solution falseSolution = ConstraintGoalSolver.solve(
        new ConstraintExpressionEngine.TestGoal(
            ConstraintExpressionEngine.GoalKind.FALSE, expression, "enum false"),
        binding);

    assertEquals("active", trueSolution.assignment().get("Status"));
    assertEquals("draft", falseSolution.assignment().get("Status"));
  }

  @Test
  void reportsUnsupportedFunctionSemanticsInsteadOfGuessing() {
    ConstraintExpression.Attribute weight = new ConstraintExpression.Attribute(
        "Gewichtung", ConstraintExpression.Type.scalar(NUMERIC));
    ConstraintExpression.FunctionDefinition unknownFunction = new ConstraintExpression.FunctionDefinition(
        "MODEL_FUNCTION:SolverModel.custom",
        List.of(new ConstraintExpression.ArgumentSpec(
            ConstraintExpression.Type.scalar(NUMERIC),
            ConstraintExpression.ArgumentSemantics.VALUE)),
        ConstraintExpression.Type.scalar(NUMERIC),
        ConstraintExpression.ResultTypeRule.DECLARED,
        Map.of(
            ConstraintExpression.IliVersion.ILI_23,
            new ConstraintExpression.FunctionSyntax("SolverModel.custom")));
    ConstraintExpression expression = new ConstraintExpression.Comparison(
        EQ,
        new ConstraintExpression.FunctionCall(unknownFunction, List.of(weight)),
        new ConstraintExpression.NumericLiteral(10));
    ConstraintModelSynthesizer.ModelBinding binding = binding(expression);

    ConstraintGoalSolver.Solution solution = ConstraintGoalSolver.solve(
        new ConstraintExpressionEngine.TestGoal(
            ConstraintExpressionEngine.GoalKind.TRUE, expression, "unknown function equality"),
        binding);

    assertFalse(solution.solved());
    assertEquals("UNSUPPORTED_FUNCTION_SEMANTICS", solution.reasonCode());
    assertTrue(solution.reason().contains("MODEL_FUNCTION:SolverModel.custom"));
  }

  @Test
  void solverSynthesizerAssignmentsAreConfirmedByTheRealValidator() {
    ConstraintExpression expression = afuExpression();
    ConstraintModelSynthesizer.ModelBinding binding = binding(expression);
    List<ConstraintExpressionEngine.TestGoal> goals = ConstraintExpressionEngine.testGoals(expression);
    ConstraintGoalSolver.Solution witness = ConstraintGoalSolver.solve(goals.get(0), binding);
    ConstraintGoalSolver.Solution counterexample = ConstraintGoalSolver.solve(goals.get(1), binding);
    assertTrue(witness.solved(), String.valueOf(witness));
    assertTrue(counterexample.solved(), String.valueOf(counterexample));

    List<ConstraintTestTools.TestCase> cases = new ArrayList<>();
    cases.add(testCase(
        "solver witness",
        true,
        ConstraintModelSynthesizer.synthesize(binding, witness.assignment(), "solver_witness")));
    cases.add(testCase(
        "solver counterexample",
        false,
        ConstraintModelSynthesizer.synthesize(binding, counterexample.assignment(), "solver_counterexample")));

    ConstraintTestTools tools = new ConstraintTestTools(compilerService);
    Map<String, Object> result = tools.testIliConstraint(MODEL, "WeightSum100", cases);

    assertEquals(true, result.get("allPassed"), String.valueOf(result));
    assertEquals(2, result.get("passedCount"));
  }

  private ConstraintModelSynthesizer.ModelBinding binding(ConstraintExpression expression) {
    IliCompilerService.CompilationResult compilation = compilerService.compile(
        MODEL, null, "ili2c_constraint_goal_solver_test_");
    assertTrue(compilation.valid(), String.valueOf(compilation.messages()));
    return ConstraintModelSynthesizer.bind(compilation.transferDescription(), CONTEXT, expression);
  }

  private ConstraintExpression afuExpression() {
    ConstraintExpression.Path secondary = new ConstraintExpression.Path(
        "Nebenauspraegung->Gewichtung",
        ConstraintExpression.Type.collection(NUMERIC));
    ConstraintExpression.FunctionCall sum = call("COLLECTION_SUM", secondary);
    ConstraintExpression.Attribute main = new ConstraintExpression.Attribute(
        "Gewichtung", ConstraintExpression.Type.scalar(NUMERIC));
    ConstraintExpression.NumericLiteral hundred = new ConstraintExpression.NumericLiteral(100);
    ConstraintExpression.FunctionCall total = call("NUMERIC_ADD", sum, main);

    return new ConstraintExpression.Or(List.of(
        new ConstraintExpression.And(List.of(
            new ConstraintExpression.Defined(sum),
            new ConstraintExpression.Comparison(EQ, total, hundred))),
        new ConstraintExpression.And(List.of(
            new ConstraintExpression.Comparison(EQ, main, hundred),
            new ConstraintExpression.Not(new ConstraintExpression.Defined(sum))))));
  }

  private ConstraintExpression.FunctionCall findSum(ConstraintExpression expression) {
    return expression.references().stream()
        .filter(reference -> "Nebenauspraegung->Gewichtung".equals(reference.name()))
        .findFirst()
        .map(ignored -> call(
            "COLLECTION_SUM",
            new ConstraintExpression.Path(
                "Nebenauspraegung->Gewichtung",
                ConstraintExpression.Type.collection(NUMERIC))))
        .orElseThrow();
  }

  private ConstraintExpression.FunctionCall call(
      String semanticId,
      ConstraintExpression... arguments) {
    ConstraintExpression.FunctionDefinition definition = StandardFunctionRegistry.findBySemanticId(semanticId)
        .orElseThrow()
        .definition();
    return new ConstraintExpression.FunctionCall(definition, List.of(arguments));
  }

  private ConstraintTestTools.TestCase testCase(
      String name,
      boolean expected,
      ConstraintModelSynthesizer.ObjectGraph graph) {
    ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
    testCase.name = name;
    testCase.expectedConstraintValid = expected;
    testCase.objects = graph.objects().stream().map(object -> {
      ConstraintTestTools.TestObject result = new ConstraintTestTools.TestObject();
      result.classFqn = object.classFqn();
      result.oid = object.oid();
      result.values = object.values();
      return result;
    }).toList();
    testCase.links = graph.links().stream().map(link -> {
      ConstraintTestTools.TestLink result = new ConstraintTestTools.TestLink();
      result.associationFqn = link.associationFqn();
      result.roles = link.roles();
      return result;
    }).toList();
    return testCase;
  }
}
