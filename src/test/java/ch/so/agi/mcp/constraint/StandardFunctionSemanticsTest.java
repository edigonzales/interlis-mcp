package ch.so.agi.mcp.constraint;

import static ch.so.agi.mcp.constraint.ConstraintExpression.ArgumentSemantics.VALUE;
import static ch.so.agi.mcp.constraint.ConstraintExpression.IliVersion.ILI_23;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ResultTypeRule.DECLARED;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.MTEXT;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.NUMERIC;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.interlis.ili2c.metamodel.Constraint;
import ch.interlis.ili2c.metamodel.Container;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.ConstraintTestTools;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StandardFunctionSemanticsTest {

  private static final String SQRT_MODEL = """
      INTERLIS 2.3;

      CONTRACTED TYPE MODEL Math (en) AT "http://www.interlis.ch/models"
      VERSION "2018-11-19" =
        FUNCTION sqrt(a: NUMERIC): NUMERIC;
      END Math.

      MODEL FunctionSemanticsModel (en)
      AT "http://example.org/models"
      VERSION "2026-08-18" =
        IMPORTS Math;

        TOPIC Data =
          CLASS Main =
            A : MANDATORY 0 .. 9;
          END Main;

          CONSTRAINTS OF FunctionSemanticsModel.Data.Main =
            !!@ name = "SqrtThree"
            MANDATORY CONSTRAINT Math.sqrt(A) == 3;
          END;
        END Data;
      END FunctionSemanticsModel.
      """;

  private final IliCompilerService compilerService = new IliCompilerService();

  @Test
  void everyRegisteredStandardFunctionHasExecutableSemantics() {
    assertEquals(54, StandardFunctionRegistry.all().size());

    for (StandardFunctionRegistry.StandardFunction function : StandardFunctionRegistry.all()) {
      ConstraintExpression.FunctionCall call = new ConstraintExpression.FunctionCall(
          function.definition(), sampleArguments(function));
      Map<String, Object> values = function.parameters().stream()
          .anyMatch(parameter -> parameter.semantics() == ConstraintExpression.ArgumentSemantics.ATTRIBUTE_PATH)
              ? Map.of("items->value", List.of(4, 9))
              : Map.of();

      Object result = ConstraintExpressionEngine.evaluate(
          call, ConstraintExpressionEngine.EvaluationContext.of(values));

      assertNotNull(result, function.semanticId());
      assertNotSame(ConstraintExpressionEngine.Undefined.INSTANCE, result, function.semanticId());
    }
  }

  @Test
  void followsValidatorMathRuntimeForRepresentativeFunctions() {
    assertDecimal("3", evaluate("NUMERIC_SQRT", numeric(9)));
    assertDecimal("5", evaluate("NUMERIC_HYPOT", numeric(3), numeric(4)));
    assertDecimal("9", evaluate("NUMERIC_POW", numeric(3), numeric(2)));
    assertDecimal("5", evaluate("NUMERIC_CBRT", numeric(125)));

    // Current iox-ili Math.cbrt returns cbrt.intValue(), so non-integer roots are truncated.
    assertDecimal("1", evaluate("NUMERIC_CBRT", numeric(2)));
    // Current iox-ili Math.round delegates to java.lang.Math.round (negative halves round toward zero).
    assertDecimal("-1", evaluate("NUMERIC_ROUND", new ConstraintExpression.NumericLiteral(new BigDecimal("-1.5"))));

    BigDecimal sin30 = (BigDecimal) evaluate("NUMERIC_SIN", numeric(30));
    assertTrue(sin30.subtract(new BigDecimal("0.5")).abs().compareTo(new BigDecimal("0.000000000000001")) < 0);

    assertEquals(
        ConstraintExpressionEngine.Undefined.INSTANCE,
        evaluate("NUMERIC_SQRT", new ConstraintExpression.NumericLiteral(-1)));
  }

  @Test
  void translatedSqrtConstraintIsSolvedSynthesizedAndValidatorProven() {
    IliCompilerService.CompilationResult compilation = compilerService.compile(
        SQRT_MODEL, null, "ili2c_standard_function_semantics_");
    assertTrue(compilation.valid(), String.valueOf(compilation.messages()));
    TransferDescription td = compilation.transferDescription();

    ConstraintAstTranslator.Translation translation = ConstraintAstTranslator.translate(
        constraint(td, "SqrtThree"));
    ConstraintExpression expression = translation.expression();
    ConstraintModelSynthesizer.ModelBinding binding = ConstraintModelSynthesizer.bind(
        td, translation.contextFqn(), expression);

    ConstraintExpressionEngine.TestGoal witnessGoal = ConstraintExpressionEngine.testGoals(expression).stream()
        .filter(goal -> goal.kind() == ConstraintExpressionEngine.GoalKind.TRUE)
        .filter(goal -> goal.expression().equals(expression))
        .findFirst()
        .orElseThrow();
    ConstraintExpressionEngine.TestGoal counterexampleGoal = ConstraintExpressionEngine.testGoals(expression).stream()
        .filter(goal -> goal.kind() == ConstraintExpressionEngine.GoalKind.FALSE)
        .filter(goal -> goal.expression().equals(expression))
        .findFirst()
        .orElseThrow();

    ConstraintGoalSolver.Solution witness = ConstraintGoalSolver.solve(witnessGoal, binding);
    ConstraintGoalSolver.Solution counterexample = ConstraintGoalSolver.solve(counterexampleGoal, binding);
    assertTrue(witness.solved(), String.valueOf(witness));
    assertTrue(counterexample.solved(), String.valueOf(counterexample));
    assertTrue(ConstraintExpressionEngine.evaluateConstraint(
        expression, ConstraintExpressionEngine.EvaluationContext.of(witness.assignment())));
    assertFalse(ConstraintExpressionEngine.evaluateConstraint(
        expression, ConstraintExpressionEngine.EvaluationContext.of(counterexample.assignment())));

    ConstraintModelSynthesizer.ObjectGraph witnessGraph = ConstraintModelSynthesizer.synthesize(
        binding, witness.assignment(), "sqrt_witness");
    ConstraintModelSynthesizer.ObjectGraph counterexampleGraph = ConstraintModelSynthesizer.synthesize(
        binding, counterexample.assignment(), "sqrt_counterexample");

    Map<String, Object> result = new ConstraintTestTools(compilerService).testIliConstraint(
        SQRT_MODEL,
        "SqrtThree",
        List.of(
            testCase("sqrt witness", true, witnessGraph),
            testCase("sqrt counterexample", false, counterexampleGraph)),
        null);

    assertEquals(true, result.get("allPassed"), String.valueOf(result));
    assertEquals(2, result.get("passedCount"));
  }

  @Test
  void unknownModelFunctionStillFailsExplicitly() {
    ConstraintExpression.FunctionDefinition definition = new ConstraintExpression.FunctionDefinition(
        "MODEL_FUNCTION:Example.custom",
        List.of(new ConstraintExpression.ArgumentSpec(ConstraintExpression.Type.scalar(NUMERIC), VALUE)),
        ConstraintExpression.Type.scalar(NUMERIC),
        DECLARED,
        Map.of(ILI_23, new ConstraintExpression.FunctionSyntax("Example.custom")));
    ConstraintExpression.FunctionCall call = new ConstraintExpression.FunctionCall(
        definition, List.of(numeric(1)));

    ConstraintExpressionEngine.UnsupportedFunctionSemanticsException ex = assertThrows(
        ConstraintExpressionEngine.UnsupportedFunctionSemanticsException.class,
        () -> ConstraintExpressionEngine.evaluate(
            call, ConstraintExpressionEngine.EvaluationContext.of(Map.of())));
    assertEquals("MODEL_FUNCTION:Example.custom", ex.semanticId());
  }

  private List<ConstraintExpression> sampleArguments(StandardFunctionRegistry.StandardFunction function) {
    List<ConstraintExpression> arguments = new ArrayList<>();
    for (int i = 0; i < function.definition().arguments().size(); i++) {
      ConstraintExpression.ArgumentSpec argument = function.definition().arguments().get(i);
      if (argument.semantics() == ConstraintExpression.ArgumentSemantics.ATTRIBUTE_PATH) {
        arguments.add(new ConstraintExpression.Path("items->value", argument.type()));
        continue;
      }
      arguments.add(switch (argument.type().scalarKind()) {
        case NUMERIC -> numeric(4);
        case TEXT -> new ConstraintExpression.TextLiteral(textSample(i), TEXT);
        case MTEXT -> new ConstraintExpression.TextLiteral(textSample(i), MTEXT);
        default -> throw new IllegalArgumentException(
            "No executable standard-function sample for " + argument.type().scalarKind());
      });
    }
    return List.copyOf(arguments);
  }

  private String textSample(int index) {
    return switch (index) {
      case 0 -> "abcdef";
      case 1 -> "a";
      default -> "z";
    };
  }

  private Object evaluate(String semanticId, ConstraintExpression... arguments) {
    StandardFunctionRegistry.StandardFunction function = StandardFunctionRegistry.findBySemanticId(semanticId)
        .orElseThrow();
    return ConstraintExpressionEngine.evaluate(
        new ConstraintExpression.FunctionCall(function.definition(), List.of(arguments)),
        ConstraintExpressionEngine.EvaluationContext.of(Map.of()));
  }

  private ConstraintExpression.NumericLiteral numeric(long value) {
    return new ConstraintExpression.NumericLiteral(value);
  }

  private void assertDecimal(String expected, Object actual) {
    assertTrue(actual instanceof BigDecimal, String.valueOf(actual));
    assertEquals(0, ((BigDecimal) actual).compareTo(new BigDecimal(expected)), String.valueOf(actual));
  }

  private Constraint constraint(TransferDescription td, String name) {
    List<Constraint> matches = new ArrayList<>();
    for (Model model : td.getModelsFromLastFile()) {
      collectConstraints(model, name, matches);
    }
    assertEquals(1, matches.size(), String.valueOf(matches));
    return matches.getFirst();
  }

  private void collectConstraints(Container<?> container, String name, List<Constraint> sink) {
    Iterator<?> iterator = container.iterator();
    while (iterator.hasNext()) {
      Object child = iterator.next();
      if (child instanceof Constraint constraint) {
        if (name.equals(constraint.getName()) || name.equals(constraint.getScopedName())) {
          sink.add(constraint);
        }
      } else if (child instanceof Container<?> nested) {
        collectConstraints(nested, name, sink);
      }
    }
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
