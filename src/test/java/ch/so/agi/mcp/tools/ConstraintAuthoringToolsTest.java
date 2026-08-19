package ch.so.agi.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintAuthoringToolsTest {

  private static final String MODEL_23 = """
      INTERLIS 2.3;

      MODEL ConstraintAuthoring23 (en)
      AT "https://example.org"
      VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Item =
            value : MANDATORY 0 .. 100;
            label : TEXT*20;
          END Item;
        END Data;
      END ConstraintAuthoring23.
      """;

  private static final String MODEL_24 = """
      INTERLIS 2.4;

      MODEL ConstraintAuthoring24 (en)
      AT "https://example.org"
      VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Item =
            A : MANDATORY 0 .. 100;
            B : MANDATORY 0 .. 100;
          END Item;
        END Data;
      END ConstraintAuthoring24.
      """;

  private static final String SUM_MODEL_23 = """
      INTERLIS 2.3;

      CONTRACTED TYPE MODEL Math (en) AT "http://www.interlis.ch/models"
      VERSION "2018-11-19" =
        FUNCTION sum(attributePath: TEXT): NUMERIC;
      END Math.

      MODEL ConstraintAuthoringSum23 (en)
      AT "https://example.org"
      VERSION "2026-08-19" =
        IMPORTS Math;

        TOPIC Data =
          CLASS Main =
            code : MANDATORY TEXT*20;
          END Main;

          CLASS Secondary =
            weight : MANDATORY 0 .. 100;
          END Secondary;

          ASSOCIATION MainSecondary =
            MainObject -- {1} Main;
            Secondaries -- {0..3} Secondary;
          END MainSecondary;
        END Data;
      END ConstraintAuthoringSum23.
      """;

  private final IliCompilerService compilerService = new IliCompilerService();
  private final ConstraintKnowledgeTools knowledgeTools = new ConstraintKnowledgeTools(
      new MathTools(), new TextTools(), compilerService);
  private final ConstraintReviewTools reviewTools = new ConstraintReviewTools(compilerService, knowledgeTools);
  private final ConstraintTestTools testTools = new ConstraintTestTools(compilerService);
  private final ConstraintCaseGenerationTools caseGenerationTools = new ConstraintCaseGenerationTools(
      reviewTools, testTools, compilerService);
  private final ConstraintAuthoringTools tools = new ConstraintAuthoringTools(
      compilerService, caseGenerationTools);

  @Test
  void authorsTypedRangeAndProvesBoundariesWithValidator() {
    List<ConstraintAuthoringTools.ExpressionNode> nodes = List.of(
        node("value", "ATTRIBUTE", "value", null, null, null),
        node("low", "NUMERIC", null, null, 10, null),
        node("ge", "COMPARE", null, ">=", null, List.of("value", "low")),
        node("high", "NUMERIC", null, null, 20, null),
        node("le", "COMPARE", null, "<=", null, List.of("value", "high")),
        node("root", "AND", null, null, null, List.of("ge", "le")));

    Map<String, Object> result = tools.authorIliMandatoryConstraint(
        MODEL_23,
        "ConstraintAuthoring23.Data.Item",
        "ValueRange",
        "root",
        nodes,
        null);

    assertEquals(true, result.get("generated"), String.valueOf(result));
    assertEquals(true, result.get("proofVerified"), String.valueOf(result));
    assertTrue(String.valueOf(result.get("constraintExpression")).contains("value >= 10"));
    assertTrue(String.valueOf(result.get("constraintExpression")).contains("value <= 20"));
    assertTrue(String.valueOf(result.get("typedCanonicalExpression")).contains("value >= 10"));

    List<Map<String, Object>> references = list(result.get("typedReferences"));
    assertEquals(1, references.size());
    assertEquals("NUMERIC", references.getFirst().get("scalarKind"));
    assertEquals(false, references.getFirst().get("collection"));

    Map<String, Object> proof = map(result.get("proof"));
    List<Map<String, Object>> generatedCases = list(proof.get("generatedCases"));
    assertCase(generatedCases, "9", false);
    assertCase(generatedCases, "10", true);
    assertCase(generatedCases, "20", true);
    assertCase(generatedCases, "21", false);
    assertEquals(true, map(proof.get("verification")).get("allPassed"));
  }

  @Test
  void rendersSemanticArithmeticAsInterlis24NativeOperatorAndProvesIt() {
    List<ConstraintAuthoringTools.ExpressionNode> nodes = List.of(
        node("a", "ATTRIBUTE", "A", null, null, null),
        node("b", "ATTRIBUTE", "B", null, null, null),
        node("add", "FUNCTION", "NUMERIC_ADD", null, null, List.of("a", "b")),
        node("hundred", "NUMERIC", null, null, 100, null),
        node("root", "COMPARE", null, "==", null, List.of("add", "hundred")));

    Map<String, Object> result = tools.authorIliMandatoryConstraint(
        MODEL_24,
        "ConstraintAuthoring24.Data.Item",
        "Sum100",
        "root",
        nodes,
        null);

    assertEquals(true, result.get("generated"), String.valueOf(result));
    assertEquals(true, result.get("proofVerified"), String.valueOf(result));
    assertTrue(String.valueOf(result.get("constraintExpression")).contains("(A + B)"));
    assertEquals(List.of(), result.get("requiredFunctionModels"));
    assertEquals(true, map(result.get("proof")).get("generationVerified"));
  }

  @Test
  void authorsAttributePathAggregateAndProvesPresenceWithValidator() {
    List<ConstraintAuthoringTools.ExpressionNode> nodes = List.of(
        node("weights", "PATH", "Secondaries->weight", null, null, null),
        node("sum", "FUNCTION", "COLLECTION_SUM", null, null, List.of("weights")),
        node("root", "DEFINED", null, null, null, List.of("sum")));

    Map<String, Object> result = tools.authorIliMandatoryConstraint(
        SUM_MODEL_23,
        "ConstraintAuthoringSum23.Data.Main",
        "SecondaryWeightPresent",
        "root",
        nodes,
        null);

    assertEquals(true, result.get("generated"), String.valueOf(result));
    assertEquals(true, result.get("proofVerified"), String.valueOf(result));
    assertEquals(List.of("Math"), result.get("requiredFunctionModels"));
    assertTrue(String.valueOf(result.get("constraintExpression"))
        .contains("Math.sum(\"Secondaries->weight\")"));

    List<Map<String, Object>> references = list(result.get("typedReferences"));
    assertEquals(1, references.size());
    assertEquals("Secondaries->weight", references.getFirst().get("name"));
    assertEquals(true, references.getFirst().get("collection"));

    Map<String, Object> proof = map(result.get("proof"));
    List<Map<String, Object>> generatedCases = list(proof.get("generatedCases"));
    assertTrue(generatedCases.stream().anyMatch(item ->
        Boolean.FALSE.equals(item.get("expectedConstraintValid"))
            && ((Number) item.get("associationLinkCount")).intValue() == 0), String.valueOf(generatedCases));
    assertTrue(generatedCases.stream().anyMatch(item ->
        Boolean.TRUE.equals(item.get("expectedConstraintValid"))
            && ((Number) item.get("associationLinkCount")).intValue() > 0), String.valueOf(generatedCases));
    assertEquals(true, map(proof.get("verification")).get("allPassed"));
  }

  @Test
  void rejectsModelTypeMismatchThroughCompilerAndTypedRoundTrip() {
    List<ConstraintAuthoringTools.ExpressionNode> nodes = List.of(
        node("label", "ATTRIBUTE", "label", null, null, null),
        node("ten", "NUMERIC", null, null, 10, null),
        node("root", "COMPARE", null, ">=", null, List.of("label", "ten")));

    Map<String, Object> result = tools.authorIliMandatoryConstraint(
        MODEL_23,
        "ConstraintAuthoring23.Data.Item",
        "InvalidTextOrdering",
        "root",
        nodes,
        null);

    assertEquals(false, result.get("generated"), String.valueOf(result));
    assertEquals(false, result.get("proofVerified"));
    assertEquals("GENERATED_CONSTRAINT_NOT_COMPILABLE", result.get("reasonCode"));
    assertTrue(result.containsKey("compilerMessages"));
  }

  private ConstraintAuthoringTools.ExpressionNode node(
      String id,
      String kind,
      String name,
      String operator,
      Object value,
      List<String> children) {
    ConstraintAuthoringTools.ExpressionNode node = new ConstraintAuthoringTools.ExpressionNode();
    node.id = id;
    node.kind = kind;
    node.name = name;
    node.operator = operator;
    node.value = value;
    node.children = children;
    return node;
  }

  private void assertCase(
      List<Map<String, Object>> cases,
      String value,
      boolean expectedValid) {
    Map<String, Object> match = cases.stream()
        .filter(item -> value.equals(String.valueOf(item.get("value"))))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing value " + value + ": " + cases));
    assertEquals(expectedValid, match.get("expectedConstraintValid"));
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> list(Object value) {
    return (List<Map<String, Object>>) value;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
