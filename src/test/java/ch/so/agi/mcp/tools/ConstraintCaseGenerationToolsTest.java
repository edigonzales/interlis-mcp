package ch.so.agi.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
        END Data;
      END ConstraintAutoCases.
      """;

  private final IliCompilerService compilerService = new IliCompilerService();
  private final ConstraintKnowledgeTools knowledgeTools = new ConstraintKnowledgeTools(
      new MathTools(), new TextTools(), compilerService);
  private final ConstraintReviewTools reviewTools = new ConstraintReviewTools(compilerService, knowledgeTools);
  private final ConstraintTestTools testTools = new ConstraintTestTools(compilerService);
  private final ConstraintCaseGenerationTools tools = new ConstraintCaseGenerationTools(reviewTools, testTools);

  @Test
  void generatesAndVerifiesNumericWitnessAndCounterexample() {
    Map<String, Object> result = tools.generateIliConstraintCases(
        MODEL,
        "ValueAtLeast10",
        null);

    assertEquals(true, result.get("automaticCasesAvailable"));
    assertEquals(true, result.get("automaticCasesGenerated"));
    assertEquals(true, result.get("generationVerified"));
    assertEquals("SCALAR_COMPARISON >=", result.get("pattern"));

    List<Map<String, Object>> generated = list(result.get("generatedCases"));
    assertEquals(2, generated.size());
    assertEquals("WITNESS", generated.get(0).get("purpose"));
    assertEquals("10", generated.get(0).get("value"));
    assertEquals("COUNTEREXAMPLE", generated.get(1).get("purpose"));
    assertEquals("9", generated.get(1).get("value"));

    Map<String, Object> verification = map(result.get("verification"));
    assertEquals(2, verification.get("caseCount"));
    assertEquals(2, verification.get("passedCount"));
    assertEquals(true, verification.get("allPassed"));

    List<Map<String, Object>> cases = list(verification.get("cases"));
    assertEquals(true, cases.get(0).get("actualConstraintValid"));
    assertEquals(false, cases.get(1).get("actualConstraintValid"));
    assertEquals(true, cases.get(0).get("fixtureValid"));
    assertEquals(true, cases.get(1).get("fixtureValid"));
  }

  @Test
  void generatesDefinedCasesByOmittingOptionalAttribute() {
    Map<String, Object> result = tools.generateIliConstraintCases(
        MODEL,
        "LabelDefined",
        null);

    assertEquals(true, result.get("automaticCasesAvailable"));
    assertEquals("DEFINED", result.get("pattern"));

    List<Map<String, Object>> generated = list(result.get("generatedCases"));
    assertEquals("x", generated.get(0).get("value"));
    assertEquals(true, generated.get(1).get("attributeOmitted"));

    Map<String, Object> verification = map(result.get("verification"));
    assertEquals(true, verification.get("allPassed"));
  }

  @Test
  void refusesComplexBooleanExpressionInsteadOfGuessing() {
    Map<String, Object> result = tools.generateIliConstraintCases(
        MODEL,
        "ComplexValueRange",
        null);

    assertEquals(false, result.get("automaticCasesAvailable"));
    assertEquals(false, result.get("automaticCasesGenerated"));
    assertEquals(false, result.get("generationVerified"));
    assertEquals("UNSUPPORTED_EXPRESSION", result.get("reasonCode"));
    assertFalse(result.containsKey("verification"));
    assertTrue(String.valueOf(result.get("reason")).contains("direct scalar"));
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
