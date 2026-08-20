package ch.so.agi.mcp.constraint;

import static ch.so.agi.mcp.constraint.ConstraintExpression.IliVersion.ILI_23;
import static ch.so.agi.mcp.constraint.ConstraintExpression.IliVersion.ILI_24;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.NUMERIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.interlis.ili2c.metamodel.Constraint;
import ch.interlis.ili2c.metamodel.Container;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.ConstraintTestTools;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintAstTranslatorTest {

  private static final String MODEL_23 = """
      INTERLIS 2.3;

      CONTRACTED TYPE MODEL Math (en) AT "http://www.interlis.ch/models"
      VERSION "2018-11-19" =
        FUNCTION add(a: NUMERIC; b: NUMERIC): NUMERIC;
        FUNCTION sum(attributePath: TEXT): NUMERIC;
      END Math.

      MODEL AstModel23 (en)
      AT "http://example.org/models"
      VERSION "2026-08-18" =
        IMPORTS Math;

        TOPIC Data =
          CLASS Main =
            Gewichtung : MANDATORY 0 .. 100;
          END Main;

          CLASS Secondary =
            Gewichtung : MANDATORY 0 .. 100;
          END Secondary;

          ASSOCIATION MainSecondary =
            Hauptobjekt -- {1} Main;
            Nebenauspraegung -- {0..3} Secondary;
          END MainSecondary;

          CONSTRAINTS OF AstModel23.Data.Main =
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
      END AstModel23.
      """;

  private static final String MODEL_24 = """
      INTERLIS 2.4;

      MODEL AstModel24 (en)
      AT "http://example.org/models"
      VERSION "2026-08-18" =
        TOPIC Data =
          CLASS Main =
            A : MANDATORY 0 .. 100;
            B : MANDATORY 0 .. 100;
          END Main;

          CONSTRAINTS OF AstModel24.Data.Main =
            !!@ name = "NativeAdd"
            MANDATORY CONSTRAINT (A + B) == 100;
          END;
        END Data;
      END AstModel24.
      """;

  private final IliCompilerService compilerService = new IliCompilerService();

  @Test
  void translatesExistingAfuConstraintFromIli2cAst() {
    TransferDescription td = compile(MODEL_23, "ili2c_constraint_ast23_");
    Constraint constraint = constraint(td, "WeightSum100");

    ConstraintAstTranslator.Translation translation = ConstraintAstTranslator.translate(constraint);

    assertEquals(ILI_23, translation.version());
    assertEquals("AstModel23.Data.Main", translation.contextFqn());
    assertEquals("WeightSum100", translation.constraintName());

    ConstraintExpression expression = translation.expression();
    String ili = expression.toInterlis(ILI_23);
    assertTrue(ili.contains("DEFINED(Math.sum(\"Nebenauspraegung->Gewichtung\"))"), ili);
    assertTrue(ili.contains(
        "Math.add(Math.sum(\"Nebenauspraegung->Gewichtung\"), Gewichtung) == 100"), ili);
    assertTrue(ili.contains("NOT(DEFINED(Math.sum(\"Nebenauspraegung->Gewichtung\")))"), ili);

    assertTrue(expression.references().contains(new ConstraintExpression.Reference(
        "Nebenauspraegung->Gewichtung",
        ConstraintExpression.ReferenceKind.PATH,
        ConstraintExpression.Type.collection(NUMERIC))));
    assertTrue(expression.references().stream().anyMatch(reference ->
        "Gewichtung".equals(reference.name())
            && reference.kind() == ConstraintExpression.ReferenceKind.ATTRIBUTE));
  }

  @Test
  void existingConstraintRunsThroughGenericSolverSynthesizerAndValidator() {
    TransferDescription td = compile(MODEL_23, "ili2c_constraint_ast_proof_");
    ConstraintAstTranslator.Translation translation = ConstraintAstTranslator.translate(
        constraint(td, "WeightSum100"));
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
        binding, witness.assignment(), "ast_witness");
    ConstraintModelSynthesizer.ObjectGraph counterexampleGraph = ConstraintModelSynthesizer.synthesize(
        binding, counterexample.assignment(), "ast_counterexample");

    List<ConstraintTestTools.TestCase> cases = List.of(
        testCase("AST solver witness", true, witnessGraph),
        testCase("AST solver counterexample", false, counterexampleGraph));
    Map<String, Object> result = new ConstraintTestTools(compilerService)
        .testIliConstraint(MODEL_23, "WeightSum100", cases);

    assertEquals(true, result.get("allPassed"), String.valueOf(result));
    assertEquals(2, result.get("passedCount"));
  }

  @Test
  void translatesNativeInterlis24ArithmeticToSameNumericSemantics() {
    TransferDescription td = compile(MODEL_24, "ili2c_constraint_ast24_");
    ConstraintAstTranslator.Translation translation = ConstraintAstTranslator.translate(
        constraint(td, "NativeAdd"));

    assertEquals(ILI_24, translation.version());
    assertEquals("(A + B) == 100", translation.expression().toInterlis(ILI_24));

    ConstraintExpression.Comparison comparison =
        (ConstraintExpression.Comparison) translation.expression();
    ConstraintExpression.FunctionCall add =
        (ConstraintExpression.FunctionCall) comparison.left();
    assertEquals("NUMERIC_ADD", add.semanticId());
    assertEquals(List.of("A", "B"), add.arguments().stream()
        .map(argument -> ((ConstraintExpression.Attribute) argument).name())
        .toList());
  }

  private TransferDescription compile(String model, String prefix) {
    IliCompilerService.CompilationResult compilation = compilerService.compile(model, null, prefix);
    assertTrue(compilation.valid(), String.valueOf(compilation.messages()));
    return compilation.transferDescription();
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
