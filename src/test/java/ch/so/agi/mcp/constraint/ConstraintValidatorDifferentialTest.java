package ch.so.agi.mcp.constraint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Differential regression tests for semantic Mandatory-Constraint evaluation.
 *
 * <p>Each case has an explicit semantic expectation, is evaluated by {@link
 * ConstraintExpressionEngine}, materialized through the normal model-aware object-graph
 * synthesizer, and then checked independently by the real iox-ili/ilivalidator runtime through
 * {@link ConstraintTestTools}. Any semantic or validator divergence therefore fails this suite.</p>
 */
class ConstraintValidatorDifferentialTest {

  private static final String FUNCTION_MODEL_23 = """
      INTERLIS 2.3;

      CONTRACTED TYPE MODEL Math (en) AT "http://www.interlis.ch/models"
      VERSION "2018-11-19" =
        FUNCTION sqrt(a: NUMERIC): NUMERIC;
      END Math.

      CONTRACTED TYPE MODEL Text (en) AT "http://www.interlis.ch/models"
      VERSION "2018-11-19" =
        FUNCTION startsWith(val: TEXT; prefix: TEXT): BOOLEAN;
        FUNCTION equalsIgnoreCaseM(val: MTEXT; anotherVal: MTEXT): BOOLEAN;
      END Text.

      MODEL DifferentialFunctions23 (en)
      AT "http://example.org/models"
      VERSION "2026-08-19" =
        IMPORTS Math, Text;

        TOPIC Data =
          CLASS Sample =
            A : 0 .. 100;
            Label : TEXT*40;
            Note : MTEXT*80;
          END Sample;

          CONSTRAINTS OF DifferentialFunctions23.Data.Sample =
            !!@ name = "SqrtThree"
            MANDATORY CONSTRAINT Math.sqrt(A) == 3;

            !!@ name = "TextPrefix"
            MANDATORY CONSTRAINT Text.startsWith(Label, "Ab");

            !!@ name = "MTextEqualsIgnoreCase"
            MANDATORY CONSTRAINT Text.equalsIgnoreCaseM(Note, "ABC");
          END;
        END Data;
      END DifferentialFunctions23.
      """;

  private static final String ARITHMETIC_MODEL_24 = """
      INTERLIS 2.4;

      MODEL DifferentialArithmetic24 (en)
      AT "http://example.org/models"
      VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Sample =
            A : MANDATORY 0 .. 100;
            B : MANDATORY 0 .. 10;
          END Sample;

          CONSTRAINTS OF DifferentialArithmetic24.Data.Sample =
            !!@ name = "NativeDivision"
            MANDATORY CONSTRAINT (A / B) == 2;
          END;
        END Data;
      END DifferentialArithmetic24.
      """;

  private static final String INHERITANCE_MODEL_23 = """
      INTERLIS 2.3;

      MODEL DifferentialInheritance23 (en)
      AT "http://example.org/models"
      VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Base (ABSTRACT) =
            Value : MANDATORY 0 .. 100;
          END Base;

          CLASS Concrete EXTENDS Base =
          END Concrete;

          CONSTRAINTS OF DifferentialInheritance23.Data.Concrete =
            !!@ name = "InheritedLowerBound"
            MANDATORY CONSTRAINT Value >= 10;
          END;
        END Data;
      END DifferentialInheritance23.
      """;

  private static final String MULTI_STEP_MODEL_23 = """
      INTERLIS 2.3;

      MODEL DifferentialPath23 (en)
      AT "http://example.org/models"
      VERSION "2026-08-19" =
        TOPIC Data =
          STRUCTURE Address =
            PLZ : MANDATORY 1000 .. 9999;
          END Address;

          STRUCTURE OwnerInfo =
            Adresse : Address;
          END OwnerInfo;

          CLASS Owner =
            Info : MANDATORY OwnerInfo;
          END Owner;

          CLASS Parcel =
            Dummy : MANDATORY 0 .. 1;
          END Parcel;

          ASSOCIATION ParcelOwner =
            Parzelle -- {1} Parcel;
            Eigentuemer -- {1} Owner;
          END ParcelOwner;

          CONSTRAINTS OF DifferentialPath23.Data.Parcel =
            !!@ name = "PathDefined"
            MANDATORY CONSTRAINT DEFINED(Eigentuemer->Info->Adresse->PLZ);

            !!@ name = "PathLowerBound"
            MANDATORY CONSTRAINT Eigentuemer->Info->Adresse->PLZ >= 3000;
          END;
        END Data;
      END DifferentialPath23.
      """;

  private final IliCompilerService compilerService = new IliCompilerService();
  private final ConstraintTestTools constraintTestTools = new ConstraintTestTools(compilerService);

  @Test
  void matchesValidatorForUndefinedMathFunctionArguments() {
    assertDifferential(
        FUNCTION_MODEL_23,
        "SqrtThree",
        List.of(
            assignment("undefined argument", true, Map.of(
                "A", ConstraintExpressionEngine.Undefined.INSTANCE)),
            assignment("ordinary counterexample", false, Map.of(
                "A", decimal("4"))),
            assignment("sqrt witness", true, Map.of(
                "A", decimal("9")))));
  }

  @Test
  void matchesValidatorForTextAndMtextFunctionsIncludingUndefined() {
    assertDifferential(
        FUNCTION_MODEL_23,
        "TextPrefix",
        List.of(
            assignment("undefined text", true, Map.of(
                "Label", ConstraintExpressionEngine.Undefined.INSTANCE)),
            assignment("text witness", true, Map.of("Label", "Abc")),
            assignment("text counterexample", false, Map.of("Label", "xbc"))));

    assertDifferential(
        FUNCTION_MODEL_23,
        "MTextEqualsIgnoreCase",
        List.of(
            assignment("undefined mtext", true, Map.of(
                "Note", ConstraintExpressionEngine.Undefined.INSTANCE)),
            assignment("mtext witness", true, Map.of("Note", "abc")),
            assignment("mtext counterexample", false, Map.of("Note", "abd"))));
  }

  @Test
  void matchesValidatorForInterlis24NativeDivisionAndZeroDenominator() {
    assertDifferential(
        ARITHMETIC_MODEL_24,
        "NativeDivision",
        List.of(
            assignment("division by zero", false, Map.of(
                "A", decimal("10"),
                "B", decimal("0"))),
            assignment("native arithmetic witness", true, Map.of(
                "A", decimal("10"),
                "B", decimal("5"))),
            assignment("native arithmetic counterexample", false, Map.of(
                "A", decimal("10"),
                "B", decimal("4")))));
  }

  @Test
  void matchesValidatorForInheritedAttributesInConcreteConstraintContext() {
    assertDifferential(
        INHERITANCE_MODEL_23,
        "InheritedLowerBound",
        List.of(
            assignment("below inherited boundary", false, Map.of("Value", decimal("9"))),
            assignment("at inherited boundary", true, Map.of("Value", decimal("10"))),
            assignment("above inherited boundary", true, Map.of("Value", decimal("11")))));
  }

  @Test
  void matchesValidatorForMultiStepUndefinedAndCardinalitySemantics() {
    Map<String, Object> absent = Map.of(
        "Eigentuemer->Info->Adresse->PLZ", ConstraintExpressionEngine.Undefined.INSTANCE);
    Map<String, Object> present = Map.of(
        "Eigentuemer->Info->Adresse->PLZ", decimal("3000"));

    assertDifferential(
        MULTI_STEP_MODEL_23,
        "PathDefined",
        List.of(
            assignment("optional nested structure absent", false, absent),
            assignment("nested structure present", true, present)));
    assertDifferential(
        MULTI_STEP_MODEL_23,
        "PathLowerBound",
        List.of(
            assignment("optional nested structure absent", true, absent),
            assignment("nested structure present", true, present)));
  }

  @Test
  void orderedTruthTablesMatchValidatorValuesAndConstraintValidity() {
    Object u = ConstraintExpressionEngine.Undefined.INSTANCE;
    Object[] states = {true, false, u};
    // Rows and columns are TRUE, FALSE, UNDEFINED. Expectations are independent of the evaluator.
    Object[][] and = {{true, false, u}, {false, false, false}, {u, u, u}};
    Object[][] or = {{true, true, true}, {true, false, u}, {u, u, u}};
    Object[][] implies = {{true, false, u}, {true, true, true}, {u, u, u}};
    Object[] not = {false, true, u};
    Object[] defined = {true, true, false};
    Object[][] notAnd = {{false, true, u}, {true, true, true}, {u, u, u}};
    Object[][] notOr = {{false, false, false}, {false, true, u}, {u, u, u}};
    String template = """
        INTERLIS 2.4;
        MODEL ThreeValued (en) AT "https://example.org" VERSION "2026-09-07" =
          TOPIC Data =
            CLASS Sample =
              A : BOOLEAN;
              B : BOOLEAN;
              !!@ name = "TruthTable"
              MANDATORY CONSTRAINT %s;
            END Sample;
          END Data;
        END ThreeValued.
        """;
    for (String version : List.of("2.3", "2.4")) {
      for (int operator = 0; operator < 8; operator++) {
        String syntax = List.of("A AND B", "A OR B", "NOT(A) OR B", "NOT(A)",
            "NOT(A AND B)", "NOT(A OR B)", "(A AND B) OR NOT(A)", "DEFINED(A)").get(operator);
        String model = template.formatted(syntax).replace("INTERLIS 2.4", "INTERLIS " + version);
        var compilation = compilerService.compile(model, null);
        assertTrue(compilation.valid(), compilation.messages().toString());
        var expression = ConstraintAstTranslator.translate(constraint(compilation.transferDescription(), "TruthTable")).expression();
        List<DifferentialAssignment> cases = new ArrayList<>();
        for (int a = 0; a < 3; a++) {
          for (int b = 0; b < ((operator == 3 || operator == 7) ? 1 : 3); b++) {
            Object expected = switch (operator) {
              case 0 -> and[a][b];
              case 1 -> or[a][b];
              case 2, 6 -> implies[a][b];
              case 4 -> notAnd[a][b];
              case 5 -> notOr[a][b];
              case 7 -> defined[a];
              default -> not[a];
            };
            Map<String, Object> values = (operator == 3 || operator == 7) ? Map.of("A", states[a]) : Map.of("A", states[a], "B", states[b]);
            assertEquals(expected == u ? ConstraintExpressionEngine.NotComputable.INSTANCE : expected,
                ConstraintExpressionEngine.evaluate(expression,
                    ConstraintExpressionEngine.EvaluationContext.of(values)), syntax + " " + values);
            assertRawValidatorState(compilation.transferDescription(),
                constraint(compilation.transferDescription(), "TruthTable"), values, expected);
            cases.add(assignment("truth_" + a + "_" + b, !Boolean.FALSE.equals(expected), values));
          }
        }
        var binding = ConstraintModelSynthesizer.bind(compilation.transferDescription(), "ThreeValued.Data.Sample", expression);
        List<ConstraintTestTools.TestCase> fixtures = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
          var item = cases.get(i);
          fixtures.add(testCase(item.name(), item.expectedConstraintValid(),
              ConstraintModelSynthesizer.synthesize(binding, item.values(), "truth_" + i)));
        }
        var verification = constraintTestTools.testIliConstraint(model, "TruthTable", fixtures);
        var failures = list(verification.get("cases")).stream()
            .filter(item -> !Boolean.TRUE.equals(item.get("passed")))
            .map(item -> String.valueOf(item.get("name"))).toList();
        assertEquals(List.of(), failures, syntax);
        assertEquals(true, verification.get("allPassed"), syntax);
      }
    }
  }

  private static void assertRawValidatorState(TransferDescription td, Constraint constraint,
      Map<String, Object> values, Object expected) {
    var logging = org.mockito.Mockito.mock(ch.interlis.iox.IoxLogging.class);
    var validator = new ch.interlis.iox_j.validator.Validator(td,
        new ch.interlis.iox_j.validator.ValidationConfig(), logging,
        new ch.interlis.iox_j.logging.LogEventFactory(), new ch.interlis.iox_j.PipelinePool(),
        new ch.ehi.basics.settings.Settings());
    var object = new ch.interlis.iom_j.Iom_jObject("ThreeValued.Data.Sample", "sample");
    values.forEach((name, value) -> {
      if (value != ConstraintExpressionEngine.Undefined.INSTANCE) object.setattrvalue(name, value.toString());
    });
    try {
      var actual = validator.evaluateExpression(null, "MANDATORY", "ThreeValued.Data.Sample", object,
          constraint.getCondition(), null);
      assertEquals(expected == ConstraintExpressionEngine.Undefined.INSTANCE,
          actual.skipEvaluation() || actual.isUndefined(), values.toString());
      if (expected instanceof Boolean bool) assertEquals(bool, actual.isTrue(), values.toString());
    } finally {
      validator.close();
    }
  }

  private void assertDifferential(
      String modelText,
      String constraintName,
      List<DifferentialAssignment> assignments) {
    IliCompilerService.CompilationResult compilation = compilerService.compile(
        modelText, null, "ili2c_constraint_differential_");
    assertTrue(compilation.valid(), String.valueOf(compilation.messages()));
    TransferDescription td = compilation.transferDescription();
    assertNotNull(td);

    ConstraintAstTranslator.Translation translation = ConstraintAstTranslator.translate(
        constraint(td, constraintName));
    ConstraintExpression expression = translation.expression();
    ConstraintModelSynthesizer.ModelBinding binding = ConstraintModelSynthesizer.bind(
        td, translation.contextFqn(), expression);

    List<Boolean> semanticOutcomes = new ArrayList<>();
    List<ConstraintTestTools.TestCase> validatorCases = new ArrayList<>();
    for (int i = 0; i < assignments.size(); i++) {
      DifferentialAssignment differential = assignments.get(i);
      boolean semanticValid = ConstraintExpressionEngine.evaluateConstraint(
          expression,
          ConstraintExpressionEngine.EvaluationContext.of(differential.values()));
      assertEquals(
          differential.expectedConstraintValid(),
          semanticValid,
          () -> "Unexpected semantic result for " + constraintName + " / " + differential.name());
      semanticOutcomes.add(semanticValid);

      ConstraintModelSynthesizer.ObjectGraph graph = ConstraintModelSynthesizer.synthesize(
          binding,
          differential.values(),
          "differential_" + constraintName.toLowerCase() + "_" + (i + 1));
      validatorCases.add(testCase(differential.name(), semanticValid, graph));
    }

    Map<String, Object> validation = constraintTestTools.testIliConstraint(
        modelText,
        constraintName,
        validatorCases);

    assertEquals(true, validation.get("allPassed"), () -> differentialFailure(
        constraintName, semanticOutcomes, validation));
    assertEquals(assignments.size(), ((Number) validation.get("passedCount")).intValue(),
        String.valueOf(validation));

    for (Map<String, Object> result : list(validation.get("cases"))) {
      assertEquals(true, result.get("fixtureValid"), String.valueOf(result));
      assertEquals(result.get("expectedConstraintValid"), result.get("actualConstraintValid"),
          String.valueOf(result));
    }
  }

  private String differentialFailure(
      String constraintName,
      List<Boolean> semanticOutcomes,
      Map<String, Object> validation) {
    return "Semantic evaluator diverged from ilivalidator for " + constraintName
        + "; semanticOutcomes=" + semanticOutcomes
        + "; validator=" + validation;
  }

  private ConstraintTestTools.TestCase testCase(
      String name,
      boolean expected,
      ConstraintModelSynthesizer.ObjectGraph graph) {
    ConstraintTestTools.TestCase result = new ConstraintTestTools.TestCase();
    result.name = name;
    result.expectedConstraintValid = expected;
    result.objects = graph.objects().stream().map(object -> {
      ConstraintTestTools.TestObject testObject = new ConstraintTestTools.TestObject();
      testObject.classFqn = object.classFqn();
      testObject.oid = object.oid();
      testObject.values = object.values();
      testObject.references = object.references();
      return testObject;
    }).toList();
    result.links = graph.links().stream().map(link -> {
      ConstraintTestTools.TestLink testLink = new ConstraintTestTools.TestLink();
      testLink.associationFqn = link.associationFqn();
      testLink.roles = link.roles();
      return testLink;
    }).toList();
    return result;
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

  private DifferentialAssignment assignment(
      String name,
      boolean expectedConstraintValid,
      Map<String, Object> values) {
    return new DifferentialAssignment(name, expectedConstraintValid, values);
  }

  private BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> list(Object value) {
    return (List<Map<String, Object>>) value;
  }

  private record DifferentialAssignment(
      String name,
      boolean expectedConstraintValid,
      Map<String, Object> values) {
    private DifferentialAssignment {
      values = Map.copyOf(new LinkedHashMap<>(values));
    }
  }
}
