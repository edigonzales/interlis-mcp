package ch.so.agi.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintDecisionTableSumTest {

  private static final String MODEL = """
      INTERLIS 2.3;

      CONTRACTED TYPE MODEL Math (en) AT "http://www.interlis.ch/models"
      VERSION "2018-11-19" =
        FUNCTION sum(attributePath: TEXT): NUMERIC;
      END Math.

      MODEL DecisionSumModel (de)
      AT "https://example.org"
      VERSION "2026-08-18" =
        IMPORTS Math;

        TOPIC Data =
          CLASS Hauptauspraegung =
            Gewichtung : MANDATORY 0 .. 100;
          END Hauptauspraegung;

          CLASS Nebenauspraegung =
            Gewichtung : MANDATORY 0 .. 100;
          END Nebenauspraegung;

          ASSOCIATION Haupt_Neben =
            Bodeneinheit -- {1} Hauptauspraegung;
            Nebenauspraegung -- {0..3} Nebenauspraegung;
          END Haupt_Neben;
        END Data;
      END DecisionSumModel.
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
  void provesSumBoundariesAcrossMultiValuedAssociationPath() {
    ConstraintDecisionTableTools.DecisionRow allowed = row(
        "secondary sum between 10 and 20",
        sumCondition("Nebenauspraegung->Gewichtung", ">=", 10),
        sumCondition("Nebenauspraegung->Gewichtung", "<=", 20));

    Map<String, Object> result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionSumModel.Data.Hauptauspraegung",
        "SecondarySumBetween10And20",
        List.of(allowed),
        null);

    assertEquals(true, result.get("generated"), String.valueOf(result));
    assertEquals(true, result.get("proofVerified"), String.valueOf(result));
    assertEquals(
        "(Math.sum(\"Nebenauspraegung->Gewichtung\") >= 10 AND Math.sum(\"Nebenauspraegung->Gewichtung\") <= 20)",
        result.get("constraintExpression"));

    List<Map<String, Object>> cases = list(result.get("boundaryCases"));
    assertEquals(7, cases.size(), String.valueOf(cases));
    assertCase(cases, "9", false);
    assertCase(cases, "10", true);
    assertCase(cases, "20", true);
    assertCase(cases, "21", false);

    Map<String, Object> empty = caseByPurpose(cases, "aggregate empty collection");
    assertEquals(true, empty.get("expectedConstraintValid"));
    assertEquals(1, ((Number) empty.get("objectCount")).intValue());
    assertEquals(0, ((Number) empty.get("associationLinkCount")).intValue());

    Map<String, Object> maximum = caseByPurpose(cases, "aggregate maximum relevant cardinality 3");
    assertEquals(4, ((Number) maximum.get("objectCount")).intValue());
    assertEquals(3, ((Number) maximum.get("associationLinkCount")).intValue());

    Map<String, Object> verification = map(result.get("verification"));
    assertEquals(7, verification.get("caseCount"));
    assertEquals(7, verification.get("passedCount"));
    assertEquals(true, verification.get("allPassed"));

    List<Map<String, Object>> verifiedCases = list(verification.get("cases"));
    String xtf = String.valueOf(verifiedCases.getFirst().get("xtfText"));
    assertTrue(count(xtf, "<Bodeneinheit REF=\"decision_case_1_root\"") >= 1, xtf);
  }

  @Test
  void supportsAggregateValuesAboveSingleAttributeMaximum() {
    Map<String, Object> result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionSumModel.Data.Hauptauspraegung",
        "SecondarySum150",
        List.of(row(
            "sum 150",
            sumCondition("Nebenauspraegung->Gewichtung", "==", 150))),
        null);

    assertEquals(true, result.get("generated"), String.valueOf(result));
    assertEquals(true, result.get("proofVerified"), String.valueOf(result));
    assertEquals("Math.sum(\"Nebenauspraegung->Gewichtung\") == 150", result.get("constraintExpression"));

    List<Map<String, Object>> cases = list(result.get("boundaryCases"));
    assertCase(cases, "150", true);
    assertCase(cases, "149", false);
    assertEquals(true, caseByPurpose(cases, "aggregate empty collection").get("expectedConstraintValid"));
    assertEquals(3, ((Number) caseByPurpose(
        cases, "aggregate maximum relevant cardinality 3").get("associationLinkCount")).intValue());

    Map<String, Object> verification = map(result.get("verification"));
    assertEquals(true, verification.get("allPassed"));
  }

  private ConstraintDecisionTableTools.DecisionRow row(
      String name,
      ConstraintDecisionTableTools.DecisionCondition... conditions) {
    ConstraintDecisionTableTools.DecisionRow row = new ConstraintDecisionTableTools.DecisionRow();
    row.name = name;
    row.conditions = List.of(conditions);
    return row;
  }

  private ConstraintDecisionTableTools.DecisionCondition sumCondition(
      String attribute,
      String operator,
      Object value) {
    ConstraintDecisionTableTools.DecisionCondition condition = new ConstraintDecisionTableTools.DecisionCondition();
    condition.attribute = attribute;
    condition.aggregate = "SUM";
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
            map(item.get("values")).get("Nebenauspraegung->Gewichtung"))))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing SUM boundary value " + expectedValue + ": " + cases));
    assertEquals(expectedValid, match.get("expectedConstraintValid"));
  }

  private Map<String, Object> caseByPurpose(
      List<Map<String, Object>> cases,
      String purpose) {
    return cases.stream()
        .filter(item -> purpose.equals(item.get("purpose")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing coverage case '" + purpose + "': " + cases));
  }

  private int count(String text, String needle) {
    int count = 0;
    int offset = 0;
    while ((offset = text.indexOf(needle, offset)) >= 0) {
      count++;
      offset += needle.length();
    }
    return count;
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
