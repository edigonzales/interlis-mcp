package ch.so.agi.mcp.constraint;

import static ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator.EQ;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.NUMERIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.ConstraintTestTools;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintExpressionDirectedSolverTest {

  private static final String MODEL = """
      INTERLIS 2.3;

      CONTRACTED TYPE MODEL Math (en)
      AT "http://www.interlis.ch/models"
      VERSION "2018-11-19" =
        FUNCTION add(a: NUMERIC; b: NUMERIC): NUMERIC;
        FUNCTION sub(a: NUMERIC; b: NUMERIC): NUMERIC;
        FUNCTION mul(a: NUMERIC; b: NUMERIC): NUMERIC;
        FUNCTION div(a: NUMERIC; b: NUMERIC): NUMERIC;
      END Math.

      MODEL DirectedSolverModel (en)
      AT "http://example.org/models"
      VERSION "2026-08-18" =
        IMPORTS Math;

        TOPIC Data =
          CLASS Addition =
            A : MANDATORY 30 .. 60;
            B : MANDATORY 30 .. 60;
          END Addition;

          CONSTRAINTS OF DirectedSolverModel.Data.Addition =
            !!@ name = "Addition100"
            MANDATORY CONSTRAINT Math.add(A, B) == 100;
          END;

          CLASS Difference =
            A : MANDATORY 10 .. 90;
            B : MANDATORY 10 .. 90;
          END Difference;

          CONSTRAINTS OF DirectedSolverModel.Data.Difference =
            !!@ name = "Difference20"
            MANDATORY CONSTRAINT Math.sub(A, B) == 20;
          END;

          CLASS Product =
            A : MANDATORY 2 .. 20;
            B : MANDATORY 2 .. 20;
          END Product;

          CONSTRAINTS OF DirectedSolverModel.Data.Product =
            !!@ name = "Product40"
            MANDATORY CONSTRAINT Math.mul(A, B) == 40;
          END;

          CLASS Ratio =
            A : MANDATORY 10 .. 100;
            B : MANDATORY 3 .. 40;
          END Ratio;

          CONSTRAINTS OF DirectedSolverModel.Data.Ratio =
            !!@ name = "Ratio2"
            MANDATORY CONSTRAINT Math.div(A, B) == 2;
          END;
        END Data;
      END DirectedSolverModel.
      """;

  private final IliCompilerService compilerService = new IliCompilerService();

  @Test
  void solvesSimpleArithmeticEqualitiesFromTheIrOperation() {
    IliCompilerService.CompilationResult compilation = compilerService.compile(
        MODEL, null, "ili2c_expression_directed_solver_");
    assertTrue(compilation.valid(), String.valueOf(compilation.messages()));

    for (Scenario scenario : scenarios()) {
      ConstraintExpression expression = expression(scenario.semanticId(), scenario.target());
      ConstraintModelSynthesizer.ModelBinding binding = ConstraintModelSynthesizer.bind(
          compilation.transferDescription(), scenario.contextFqn(), expression);

      ConstraintGoalSolver.Solution solution = ConstraintGoalSolver.solve(
          new ConstraintExpressionEngine.TestGoal(
              ConstraintExpressionEngine.GoalKind.TRUE,
              expression,
              scenario.constraintName()),
          binding);

      assertTrue(solution.solved(), scenario + ": " + solution);
      assertEquals(
          true,
          ConstraintExpressionEngine.evaluateConstraint(
              expression,
              ConstraintExpressionEngine.EvaluationContext.of(solution.assignment())),
          scenario.toString());
    }
  }

  @Test
  void directedArithmeticAssignmentsAreConfirmedByIlivalidator() {
    IliCompilerService.CompilationResult compilation = compilerService.compile(
        MODEL, null, "ili2c_expression_directed_validator_");
    assertTrue(compilation.valid(), String.valueOf(compilation.messages()));
    ConstraintTestTools testTools = new ConstraintTestTools(compilerService);

    for (Scenario scenario : scenarios()) {
      ConstraintExpression expression = expression(scenario.semanticId(), scenario.target());
      ConstraintModelSynthesizer.ModelBinding binding = ConstraintModelSynthesizer.bind(
          compilation.transferDescription(), scenario.contextFqn(), expression);
      ConstraintGoalSolver.Solution solution = ConstraintGoalSolver.solve(
          new ConstraintExpressionEngine.TestGoal(
              ConstraintExpressionEngine.GoalKind.TRUE,
              expression,
              scenario.constraintName()),
          binding);
      assertTrue(solution.solved(), scenario + ": " + solution);

      ConstraintModelSynthesizer.ObjectGraph graph = ConstraintModelSynthesizer.synthesize(
          binding, solution.assignment(), "directed_" + scenario.constraintName().toLowerCase());
      ConstraintTestTools.TestCase testCase = toTestCase(
          "expression-directed " + scenario.constraintName(), graph);

      Map<String, Object> result = testTools.testIliConstraint(
          MODEL,
          scenario.constraintName(),
          List.of(testCase),
          null);

      assertEquals(true, result.get("allPassed"), scenario + ": " + result);
    }
  }

  private ConstraintExpression expression(String semanticId, long target) {
    ConstraintExpression.Attribute a = new ConstraintExpression.Attribute(
        "A", ConstraintExpression.Type.scalar(NUMERIC));
    ConstraintExpression.Attribute b = new ConstraintExpression.Attribute(
        "B", ConstraintExpression.Type.scalar(NUMERIC));
    ConstraintExpression.FunctionDefinition definition = StandardFunctionRegistry.findBySemanticId(semanticId)
        .orElseThrow()
        .definition();
    ConstraintExpression.FunctionCall arithmetic = new ConstraintExpression.FunctionCall(
        definition, List.of(a, b));
    return new ConstraintExpression.Comparison(
        EQ, arithmetic, new ConstraintExpression.NumericLiteral(target));
  }

  private List<Scenario> scenarios() {
    return List.of(
        new Scenario(
            "DirectedSolverModel.Data.Addition",
            "Addition100",
            "NUMERIC_ADD",
            100),
        new Scenario(
            "DirectedSolverModel.Data.Difference",
            "Difference20",
            "NUMERIC_SUB",
            20),
        new Scenario(
            "DirectedSolverModel.Data.Product",
            "Product40",
            "NUMERIC_MUL",
            40),
        new Scenario(
            "DirectedSolverModel.Data.Ratio",
            "Ratio2",
            "NUMERIC_DIV",
            2));
  }

  private ConstraintTestTools.TestCase toTestCase(
      String name,
      ConstraintModelSynthesizer.ObjectGraph graph) {
    ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
    testCase.name = name;
    testCase.expectedConstraintValid = true;
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

  private record Scenario(
      String contextFqn,
      String constraintName,
      String semanticId,
      long target) {
  }
}
