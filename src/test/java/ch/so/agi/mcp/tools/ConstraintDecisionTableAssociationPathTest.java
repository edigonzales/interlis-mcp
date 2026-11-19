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
  private final ConstraintKnowledgeTools knowledgeTools = new ConstraintKnowledgeTools(compilerService);
  private final ConstraintReviewTools reviewTools = new ConstraintReviewTools(compilerService, knowledgeTools);
  private final ConstraintTestTools testTools = new ConstraintTestTools(compilerService);
  private final ConstraintContextService contextService = new ConstraintContextService(compilerService);
  private final ConstraintCaseGenerationTools caseTools = new ConstraintCaseGenerationTools(contextService, testTools);
  private final ConstraintDecisionTableTools tools = new ConstraintDecisionTableTools(new ConstraintAuthoringWorkflow(compilerService), caseTools);

  @Test
  void provesNumericBoundariesAcrossSingleValuedAssociationPath() {
    ConstraintDecisionTableTools.DecisionRow allowed = row(
        "main weighting between 10 and 20",
        condition("Bodeneinheit->Gewichtung", ">=", 10),
        condition("Bodeneinheit->Gewichtung", "<=", 20));

    IliAuthoringResult result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionPathModel.Data.Nebenauspraegung",
        "MainWeightBetween10And20",
        List.of(allowed));

    assertEquals(IliAuthoringResult.Status.GENERATED, result.status, String.valueOf(result));
    assertEquals(IliAuthoringResult.ProofStatus.PASSED, result.constraintProofs.getFirst().status, String.valueOf(result));
    assertEquals(
        "(Bodeneinheit->Gewichtung >= 10 AND Bodeneinheit->Gewichtung <= 20)",
        result.details.get("constraintExpression"));

    List<IliAuthoringResult.ProofCase> boundaryCases =
        result.constraintProofs.getFirst().generatedCases;
    assertEquals(4, boundaryCases.size());
    assertCase(boundaryCases, "9", false);
    assertCase(boundaryCases, "10", true);
    assertCase(boundaryCases, "20", true);
    assertCase(boundaryCases, "21", false);
    assertTrue(boundaryCases.stream().allMatch(item -> Integer.valueOf(2).equals(item.objectCount)));
    assertTrue(boundaryCases.stream().allMatch(item -> Integer.valueOf(1).equals(item.associationLinkCount)));

    IliAuthoringResult.ProofVerification verification =
        result.constraintProofs.getFirst().verification;
    assertEquals(4, verification.cases.size());

    assertTrue(verification.cases.stream().allMatch(c -> Boolean.TRUE.equals(c.passed)));

    String xtf = verification.cases.getFirst().xtfText;
    assertTrue(xtf.contains("<Bodeneinheit REF=\""), xtf);
  }

  @Test
  void doesNotGuessForMultiValuedAssociationPath() {
    IliAuthoringResult result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionPathModel.Data.Hauptauspraegung",
        "SecondaryValueAtLeast10",
        List.of(row(
            "secondary value",
            condition("Nebenauspraegung->LokalerWert", ">=", 10))));

    assertEquals(null, result.updatedModelText);
    assertEquals(IliAuthoringResult.Status.AST_ROUND_TRIP_FAILED, result.status, String.valueOf(result));
    assertEquals("UNSUPPORTED_CONSTRAINT_SEMANTICS", result.reasonCode);
    assertTrue(result.afterCompilation.valid());
    assertEquals(null, result.constraintProofs);
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
      List<IliAuthoringResult.ProofCase> cases,
      String expectedValue,
      boolean expectedValid) {
    IliAuthoringResult.ProofCase match = cases.stream()
        .filter(item -> expectedValue.equals(String.valueOf(
            map(item.values).get("Bodeneinheit->Gewichtung"))))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing path boundary value " + expectedValue + ": " + cases));
    assertEquals(expectedValid, match.expectedConstraintValid);
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
