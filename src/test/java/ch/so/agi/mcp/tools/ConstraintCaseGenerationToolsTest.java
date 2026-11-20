package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.model.IliAuthoringResult.ConstraintProof;
import ch.so.agi.mcp.model.IliAuthoringResult.ProofStatus;
import ch.so.agi.mcp.model.IliAuthoringResult.ProofVerification;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConstraintCaseGenerationToolsTest {

  private static final String MODEL = """
      INTERLIS 2.3;

      MODEL ConstraintAutoCases (en)
      AT "https://example.org"
      VERSION "2026-08-18" =
        TOPIC Data =
          CLASS Item =
            value : MANDATORY 0 .. 100;
            label : TEXT*20;

            !!@ name = "ValueAtLeast10"
            MANDATORY CONSTRAINT value >= 10;

            !!@ name = "LabelDefined"
            MANDATORY CONSTRAINT DEFINED(label);

            !!@ name = "ComplexValueRange"
            MANDATORY CONSTRAINT value >= 10 AND value <= 20;
          END Item;

          CLASS Choice =
            left : MANDATORY 0 .. 1;
            right : MANDATORY 0 .. 1;

            !!@ name = "EitherSide"
            MANDATORY CONSTRAINT left == 1 OR right == 1;
          END Choice;
        END Data;
      END ConstraintAutoCases.
      """;

  private static final String AFU_MODEL = """
      INTERLIS 2.3;

      CONTRACTED TYPE MODEL Math (en) AT "http://www.interlis.ch/models"
      VERSION "2018-11-19" =
        FUNCTION add(a: NUMERIC; b: NUMERIC): NUMERIC;
        FUNCTION sum(attributePath: TEXT): NUMERIC;
      END Math.

      MODEL AutoAfu (de)
      AT "https://example.org"
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

          CONSTRAINTS OF AutoAfu.Data.Main =
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
      END AutoAfu.
      """;

  private final IliCompilerService compilerService = new IliCompilerService();
  private final ConstraintKnowledgeTools knowledgeTools = new ConstraintKnowledgeTools(compilerService);
  private final ConstraintReviewTools reviewTools = new ConstraintReviewTools(compilerService, knowledgeTools);
  private final ConstraintTestTools testTools = new ConstraintTestTools(compilerService);
  private final ConstraintCaseGenerationTools tools = new ConstraintCaseGenerationTools(
      new ConstraintContextService(compilerService), testTools);

  @Test
  void declaredExternalTargetMustStillExistInTheCompiledModel() {
    var compiler = new IliCompilerService();
    var contexts = new ConstraintContextService(compiler);
    var tools = new ConstraintCaseGenerationTools(contexts, new ConstraintTestTools(compiler));
    String fqn = "ConstraintAutoCases.Data.Item.Missing";
    var resolution = contexts.compileAndResolve(MODEL, fqn, null, "ili2c_missing_proof_target_");
    var proof = tools.prove(fqn, resolution, true);

    assertTrue(resolution.compilation().valid());
    assertEquals(ch.so.agi.mcp.model.IliAuthoringResult.ProofStatus.INCOMPLETE, proof.status);
    assertEquals("CONSTRAINT_LOOKUP_FAILED", proof.reasonCode);
    assertEquals(null, proof.verification);
  }

  @ParameterizedTest
  @CsvSource({
      "unchanged, PASSED, true, true, true",
      "unexpected, FAILED, true, true, true",
      "invalid fixture, FAILED, true, false, true",
      "no fixture, INCOMPLETE, false, false, false",
      "no subject, INCOMPLETE, true, true, false"
  })
  void statusUsesTheRealValidatorFindings(String change, ProofStatus expected,
      boolean validatorRan, boolean fixtureValid, boolean exercised) {
    var contexts = new ConstraintContextService(compilerService);
    var checking = new ConstraintTestTools(compilerService) {
      @Override ProofVerification testCompiledConstraint(CompiledConstraintContext context, List<TestCase> cases) {
        var test = cases.getFirst();
        var object = test.objects.getFirst();
        switch (change) {
          case "unexpected" -> test.expectedConstraintValid = !test.expectedConstraintValid;
          case "invalid fixture" -> object.values = Map.of("value", 101);
          case "no fixture" -> object.classFqn = "Missing";
          case "no subject" -> {
            object.classFqn = "ConstraintAutoCases.Data.Choice";
            object.values = Map.of();
          }
          default -> { }
        }
        var verification = super.testCompiledConstraint(context, cases);
        // The findings still drive status when there is no XTF in the response.
        verification.cases.forEach(checked -> checked.xtfText = null);
        return verification;
      }
    };
    String fqn = "ConstraintAutoCases.Data.Item.ValueAtLeast10";
    var resolution = contexts.compileAndResolve(MODEL, fqn, null, "proof_status_");
    var proof = new ConstraintCaseGenerationTools(contexts, checking).prove(fqn, resolution, false);
    assertThat(proof.status).isEqualTo(expected);
    var checked = proof.verification.cases.getFirst();
    assertThat(checked.validatorRan).isEqualTo(validatorRan);
    assertThat(checked.fixtureValid).isEqualTo(fixtureValid);
    assertThat(checked.constraintExercised).isEqualTo(exercised);
    if (change.equals("unexpected")) {
      assertThat(checked.actualConstraintValid).isNotEqualTo(checked.expectedConstraintValid);
      assertThat(proof.verification.cases.subList(1, proof.verification.cases.size()))
          .isNotEmpty().allSatisfy(test -> assertThat(test.passed).isTrue());
    }
  }

  @Test
  void externalSemanticsTakePriorityOverFailedAndIncompleteProofs() {
    var incomplete = new ConstraintProof();
    incomplete.status = ProofStatus.INCOMPLETE;
    var failed = new ConstraintProof();
    failed.status = ProofStatus.FAILED;
    var external = new ConstraintProof();
    external.status = ProofStatus.EXTERNAL_FUNCTION_SEMANTICS_REQUIRED;
    var result = new IliAuthoringResult();
    assertThat(result.rejectFailedProof(List.of(incomplete, failed, external), MODEL)).isTrue();
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.EXTERNAL_FUNCTION_SEMANTICS_REQUIRED);
    assertThat(result.rejectFailedProof(List.of(incomplete, failed), MODEL)).isTrue();
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.PROOF_FAILED);
    assertThat(result.rejectFailedProof(List.of(incomplete), MODEL)).isTrue();
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.PROOF_INCOMPLETE);
  }

  @Test
  void generatesAndVerifiesNumericBoundaryCasesThroughSemanticPipeline() {
    Map<String, Object> result = tools.generateIliConstraintCases(
        MODEL,
        "ValueAtLeast10");

    assertEquals(true, result.get("automaticCasesAvailable"));
    assertEquals(true, result.get("automaticCasesGenerated"));
    assertEquals(true, result.get("generationVerified"));
    assertEquals("SEMANTIC_IR_COVERAGE", result.get("pattern"));
    assertEquals(true, result.get("coverageComplete"));

    List<Map<String, Object>> generated = list(result.get("generatedCases"));
    assertSingleValueCase(generated, "9", false);
    assertSingleValueCase(generated, "10", true);

    Map<String, Object> verification = map(result.get("verification"));
    assertEquals(2, verification.get("caseCount"));
    assertEquals(2, verification.get("passedCount"));
    assertEquals(true, verification.get("allPassed"));
  }

  @Test
  void generatesDefinedAndUndefinedCasesThroughSemanticPipeline() {
    Map<String, Object> result = tools.generateIliConstraintCases(
        MODEL,
        "LabelDefined");

    assertEquals(true, result.get("automaticCasesAvailable"), String.valueOf(result));
    assertEquals("SEMANTIC_IR_COVERAGE", result.get("pattern"));

    List<Map<String, Object>> generated = list(result.get("generatedCases"));
    assertTrue(generated.stream().anyMatch(item -> "x".equals(String.valueOf(item.get("value")))
        && Boolean.TRUE.equals(item.get("expectedConstraintValid"))), String.valueOf(generated));
    assertTrue(generated.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("attributeOmitted"))
        && Boolean.FALSE.equals(item.get("expectedConstraintValid"))), String.valueOf(generated));

    assertEquals(true, map(result.get("verification")).get("allPassed"));
  }

  @Test
  void nowSupportsComplexBooleanRangeInsteadOfRejectingIt() {
    Map<String, Object> result = tools.generateIliConstraintCases(
        MODEL,
        "ComplexValueRange");

    assertEquals(true, result.get("automaticCasesAvailable"), String.valueOf(result));
    assertEquals(true, result.get("generationVerified"));
    List<Map<String, Object>> generated = list(result.get("generatedCases"));
    assertEquals(4, generated.size(), String.valueOf(generated));
    assertSingleValueCase(generated, "9", false);
    assertSingleValueCase(generated, "10", true);
    assertSingleValueCase(generated, "20", true);
    assertSingleValueCase(generated, "21", false);
    assertEquals(true, map(result.get("verification")).get("allPassed"));
  }

  @Test
  void verifiesIndependentOrBranchesWithRealValidator() {
    Map<String, Object> result = tools.generateIliConstraintCases(
        MODEL,
        "EitherSide");

    assertEquals(true, result.get("automaticCasesAvailable"), String.valueOf(result));
    List<Map<String, Object>> generated = list(result.get("generatedCases"));
    assertAssignment(generated, "1", "0", true);
    assertAssignment(generated, "0", "1", true);
    assertAssignment(generated, "0", "0", false);
    assertTrue(generated.stream().anyMatch(item ->
        "OR branch 1 independently true".equals(item.get("reason"))), String.valueOf(generated));
    assertTrue(generated.stream().anyMatch(item ->
        "OR branch 2 independently true".equals(item.get("reason"))), String.valueOf(generated));
    assertTrue(generated.stream().anyMatch(item ->
        "OR all branches false".equals(item.get("reason"))), String.valueOf(generated));
    assertEquals(true, map(result.get("verification")).get("allPassed"));
  }

  @Test
  void exposesAfuSumAssociationPipelineThroughMcpTool() {
    Map<String, Object> result = tools.generateIliConstraintCases(
        AFU_MODEL,
        "WeightSum100");

    assertEquals(true, result.get("automaticCasesAvailable"), String.valueOf(result));
    assertEquals(true, result.get("generationVerified"), String.valueOf(result));
    List<Map<String, Object>> generated = list(result.get("generatedCases"));
    assertTrue(generated.stream().anyMatch(item ->
        ((Number) item.get("associationLinkCount")).intValue() == 0), String.valueOf(generated));
    assertTrue(generated.stream().anyMatch(item ->
        ((Number) item.get("associationLinkCount")).intValue() > 0), String.valueOf(generated));
    assertTrue(generated.stream().anyMatch(item ->
        ((Number) item.get("associationLinkCount")).intValue() == 3), String.valueOf(generated));
    assertTrue(generated.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("expectedConstraintValid"))),
        String.valueOf(generated));
    assertTrue(generated.stream().anyMatch(item -> Boolean.FALSE.equals(item.get("expectedConstraintValid"))),
        String.valueOf(generated));
    assertEquals(true, map(result.get("verification")).get("allPassed"));
  }

  private void assertSingleValueCase(
      List<Map<String, Object>> cases,
      String value,
      boolean expectedValid) {
    Map<String, Object> match = cases.stream()
        .filter(item -> value.equals(String.valueOf(item.get("value"))))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing value " + value + ": " + cases));
    assertEquals(expectedValid, match.get("expectedConstraintValid"));
    assertEquals(expectedValid ? "WITNESS" : "COUNTEREXAMPLE", match.get("purpose"));
  }

  private void assertAssignment(
      List<Map<String, Object>> cases,
      String left,
      String right,
      boolean expectedValid) {
    Map<String, Object> match = cases.stream()
        .filter(item -> {
          Map<String, Object> values = map(item.get("values"));
          return left.equals(String.valueOf(values.get("left")))
              && right.equals(String.valueOf(values.get("right")));
        })
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "Missing assignment left=" + left + ", right=" + right + ": " + cases));
    assertEquals(expectedValid, match.get("expectedConstraintValid"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
