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
 * <p>Each case is evaluated once by {@link ConstraintExpressionEngine}, materialized through the
 * normal model-aware object-graph synthesizer, and then checked independently by the real
 * iox-ili/ilivalidator runtime through {@link ConstraintTestTools}. The evaluator result is used as
 * the expected validator outcome; any semantic divergence therefore fails this suite.</p>
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
            assignment("undefined argument", Map.of(
                "A", ConstraintExpressionEngine.Undefined.INSTANCE)),
            assignment("ordinary counterexample", Map.of(
                "A", decimal("4"))),
            assignment("sqrt witness", Map.of(
                "A", decimal("9")))));
  }

  @Test
  void matchesValidatorForTextAndMtextFunctionsIncludingUndefined() {
    assertDifferential(
        FUNCTION_MODEL_23,
        "TextPrefix",
        List.of(
            assignment("undefined text", Map.of(
                "Label", ConstraintExpressionEngine.Undefined.INSTANCE)),
            assignment("text witness", Map.of("Label", "Abc")),
            assignment("text counterexample", Map.of("Label", "xbc"))));

    assertDifferential(
        FUNCTION_MODEL_23,
        "MTextEqualsIgnoreCase",
        List.of(
            assignment("undefined mtext", Map.of(
                "Note", ConstraintExpressionEngine.Undefined.INSTANCE)),
            assignment("mtext witness", Map.of("Note", "abc")),
            assignment("mtext counterexample", Map.of("Note", "abd"))));
  }

  @Test
  void matchesValidatorForInterlis24NativeDivisionAndZeroDenominator() {
    assertDifferential(
        ARITHMETIC_MODEL_24,
        "NativeDivision",
        List.of(
            assignment("division by zero", Map.of(
                "A", decimal("10"),
                "B", decimal("0"))),
            assignment("native arithmetic witness", Map.of(
                "A", decimal("10"),
                "B", decimal("5"))),
            assignment("native arithmetic counterexample", Map.of(
                "A", decimal("10"),
                "B", decimal("4")))));
  }

  @Test
  void matchesValidatorForInheritedAttributesInConcreteConstraintContext() {
    assertDifferential(
        INHERITANCE_MODEL_23,
        "InheritedLowerBound",
        List.of(
            assignment("below inherited boundary", Map.of("Value", decimal("9"))),
            assignment("at inherited boundary", Map.of("Value", decimal("10"))),
            assignment("above inherited boundary", Map.of("Value", decimal("11")))));
  }

  @Test
  void matchesValidatorForMultiStepUndefinedAndCardinalitySemantics() {
    List<DifferentialAssignment> assignments = List.of(
        assignment("optional nested structure absent", Map.of(
            "Eigentuemer->Info->Adresse->PLZ", ConstraintExpressionEngine.Undefined.INSTANCE)),
        assignment("nested structure present", Map.of(
            "Eigentuemer->Info->Adresse->PLZ", decimal("3000"))));

    assertDifferential(MULTI_STEP_MODEL_23, "PathDefined", assignments);
    assertDifferential(MULTI_STEP_MODEL_23, "PathLowerBound", assignments);
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
        validatorCases,
        null);

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

  private DifferentialAssignment assignment(String name, Map<String, Object> values) {
    return new DifferentialAssignment(name, values);
  }

  private BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> list(Object value) {
    return (List<Map<String, Object>>) value;
  }

  private record DifferentialAssignment(String name, Map<String, Object> values) {
    private DifferentialAssignment {
      values = Map.copyOf(new LinkedHashMap<>(values));
    }
  }
}
