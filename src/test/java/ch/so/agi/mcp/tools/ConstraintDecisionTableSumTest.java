package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.constraint.ConstraintAuthoringWorkflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.model.IliAuthoringResult;
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
  private final ConstraintKnowledgeTools knowledgeTools = new ConstraintKnowledgeTools(compilerService);
  private final ConstraintReviewTools reviewTools = new ConstraintReviewTools(compilerService, knowledgeTools);
  private final ConstraintTestTools testTools = new ConstraintTestTools(compilerService);
  private final ConstraintContextService contextService = new ConstraintContextService(compilerService);
  private final ConstraintCaseGenerationTools caseTools = new ConstraintCaseGenerationTools(contextService, testTools);
  private final ConstraintDecisionTableTools tools = new ConstraintDecisionTableTools(new ConstraintAuthoringWorkflow(compilerService), caseTools);

  @Test
  void provesSumBoundariesAcrossMultiValuedAssociationPath() {
    ConstraintDecisionTableTools.DecisionRow allowed = row(
        "secondary sum between 10 and 20",
        sumCondition("Nebenauspraegung->Gewichtung", ">=", 10),
        sumCondition("Nebenauspraegung->Gewichtung", "<=", 20));

    IliAuthoringResult result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionSumModel.Data.Hauptauspraegung",
        "SecondarySumBetween10And20",
        List.of(allowed));

    assertEquals(true, result.generated, String.valueOf(result));
    assertEquals(true, result.proofVerified, String.valueOf(result));
    assertEquals(
        "(Math.sum(\"Nebenauspraegung->Gewichtung\") >= 10 AND Math.sum(\"Nebenauspraegung->Gewichtung\") <= 20)",
        result.details.get("constraintExpression"));

    List<IliAuthoringResult.ProofCase> cases = result.constraintProofs.getFirst().generatedCases;
    assertEquals(7, cases.size(), String.valueOf(cases));
    assertCase(cases, "9", false);
    assertCase(cases, "10", true);
    assertCase(cases, "20", true);
    assertCase(cases, "21", false);

    IliAuthoringResult.ProofCase empty = caseByPurpose(cases, "aggregate empty collection");
    assertEquals(true, empty.get("expectedConstraintValid"));
    assertEquals(1, ((Number) empty.get("objectCount")).intValue());
    assertEquals(0, ((Number) empty.get("associationLinkCount")).intValue());

    IliAuthoringResult.ProofCase maximum = caseByPurpose(
        cases, "aggregate maximum relevant cardinality 3");
    assertEquals(4, ((Number) maximum.get("objectCount")).intValue());
    assertEquals(3, ((Number) maximum.get("associationLinkCount")).intValue());

    IliAuthoringResult.ProofVerification verification =
        result.constraintProofs.getFirst().verification;
    assertEquals(7, verification.caseCount);
    assertEquals(7, verification.passedCount);
    assertEquals(true, verification.allPassed);

    String xtf = verification.cases.getFirst().xtfText;
    assertTrue(count(xtf, "<Bodeneinheit REF=\"auto_case_1_root\"") >= 1, xtf);
  }

  @Test
  void supportsAggregateValuesAboveSingleAttributeMaximum() {
    IliAuthoringResult result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionSumModel.Data.Hauptauspraegung",
        "SecondarySum150",
        List.of(row(
            "sum 150",
            sumCondition("Nebenauspraegung->Gewichtung", "==", 150))));

    assertEquals(true, result.generated, String.valueOf(result));
    assertEquals(true, result.proofVerified, String.valueOf(result));
    assertEquals("Math.sum(\"Nebenauspraegung->Gewichtung\") == 150",
        result.details.get("constraintExpression"));

    List<IliAuthoringResult.ProofCase> cases = result.constraintProofs.getFirst().generatedCases;
    assertCase(cases, "150", true);
    assertCase(cases, "149", false);
    assertEquals(true, caseByPurpose(cases, "aggregate empty collection").get("expectedConstraintValid"));
    assertEquals(3, ((Number) caseByPurpose(
        cases, "aggregate maximum relevant cardinality 3").get("associationLinkCount")).intValue());

    assertEquals(true, result.constraintProofs.getFirst().verification.allPassed);
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
      List<IliAuthoringResult.ProofCase> cases,
      String expectedValue,
      boolean expectedValid) {
    IliAuthoringResult.ProofCase match = cases.stream()
        .filter(item -> expectedValue.equals(String.valueOf(
            map(item.get("values")).get("Nebenauspraegung->Gewichtung"))))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing SUM boundary value " + expectedValue + ": " + cases));
    assertEquals(expectedValid, match.get("expectedConstraintValid"));
  }

  private IliAuthoringResult.ProofCase caseByPurpose(
      List<IliAuthoringResult.ProofCase> cases,
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
