package ch.so.agi.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintDecisionTableAssociationPathTest {

  private static final String MODEL = """
      INTERLIS 2.3;

      MODEL DecisionPathModel (de)
      AT "https://example.org"
      VERSION "2026-08-18" =
        TOPIC Data =
          CLASS Hauptauspraegung =
            Gewichtung : MANDATORY 0 .. 100;
          END Hauptauspraegung;

          CLASS Nebenauspraegung =
            LokalerWert : MANDATORY 0 .. 100;
          END Nebenauspraegung;

          ASSOCIATION Haupt_Neben =
            Bodeneinheit -- {1} Hauptauspraegung;
            Nebenauspraegung -- {0..3} Nebenauspraegung;
          END Haupt_Neben;
        END Data;
      END DecisionPathModel.
      """;

  private final IliCompilerService compilerService = new IliCompilerService();
  private final ConstraintKnowledgeTools knowledgeTools = new ConstraintKnowledgeTools(
      new MathTools(), new TextTools(), compilerService);
  private final ConstraintReviewTools reviewTools = new ConstraintReviewTools(compilerService, knowledgeTools);
  private final ConstraintTestTools testTools = new ConstraintTestTools(compilerService);
  private final ConstraintDecisionTableTools tools = new ConstraintDecisionTableTools(
      reviewTools,
      testTools,
      compilerService);

  @Test
  void provesNumericBoundariesAcrossSingleValuedAssociationPath() {
    ConstraintDecisionTableTools.DecisionRow allowed = row(
        "main weighting between 10 and 20",
        condition("Bodeneinheit->Gewichtung", ">=", 10),
        condition("Bodeneinheit->Gewichtung", "<=", 20));

    Map<String, Object> result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionPathModel.Data.Nebenauspraegung",
        "MainWeightBetween10And20",
        List.of(allowed),
        null);

    assertEquals(true, result.get("generated"), String.valueOf(result));
    assertEquals(true, result.get("proofVerified"), String.valueOf(result));
    assertEquals(
        "(Bodeneinheit->Gewichtung >= 10 AND Bodeneinheit->Gewichtung <= 20)",
        result.get("constraintExpression"));

    List<Map<String, Object>> boundaryCases = list(result.get("boundaryCases"));
    assertEquals(4, boundaryCases.size());
    assertCase(boundaryCases, "9", false);
    assertCase(boundaryCases, "10", true);
    assertCase(boundaryCases, "20", true);
    assertCase(boundaryCases, "21", false);
    assertTrue(boundaryCases.stream().allMatch(item -> Integer.valueOf(2).equals(item.get("objectCount"))));
    assertTrue(boundaryCases.stream().allMatch(item -> Integer.valueOf(1).equals(item.get("associationLinkCount"))));

    Map<String, Object> verification = map(result.get("verification"));
    assertEquals(4, verification.get("caseCount"));
    assertEquals(4, verification.get("passedCount"));
    assertEquals(true, verification.get("allPassed"));

    List<Map<String, Object>> verifiedCases = list(verification.get("cases"));
    String xtf = String.valueOf(verifiedCases.getFirst().get("xtfText"));
    assertTrue(xtf.contains("<Bodeneinheit REF=\"decision_case_1_p1_1\""), xtf);
  }

  @Test
  void doesNotGuessForMultiValuedAssociationPath() {
    Map<String, Object> result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionPathModel.Data.Hauptauspraegung",
        "SecondaryValueAtLeast10",
        List.of(row(
            "secondary value",
            condition("Nebenauspraegung->LokalerWert", ">=", 10))),
        null);

    assertEquals(false, result.get("generated"));
    assertEquals(false, result.get("proofVerified"));
    assertTrue(
        List.of("GENERATED_CONSTRAINT_NOT_COMPILABLE", "UNSUPPORTED_ATTRIBUTE_PATH_OR_TYPE")
            .contains(String.valueOf(result.get("reasonCode"))),
        String.valueOf(result));
  }

  private ConstraintDecisionTableTools.DecisionRow row(
      String name,
      ConstraintDecisionTableTools.DecisionCondition... conditions) {
    ConstraintDecisionTableTools.DecisionRow row = new ConstraintDecisionTableTools.DecisionRow();
    row.name = name;
    row.conditions = List.of(conditions);
    return row;
  }

  private ConstraintDecisionTableTools.DecisionCondition condition(
      String attribute,
      String operator,
      Object value) {
    ConstraintDecisionTableTools.DecisionCondition condition = new ConstraintDecisionTableTools.DecisionCondition();
    condition.attribute = attribute;
    condition.operator = operator;
    condition.value = value;
    return condition;
  }

  private void assertCase(
      List<Map<String, Object>> cases,
      String expectedValue,
      boolean expectedValid) {
    Map<String, Object> match = cases.stream()
        .filter(item -> expectedValue.equals(String.valueOf(
            map(item.get("values")).get("Bodeneinheit->Gewichtung"))))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing path boundary value " + expectedValue + ": " + cases));
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
