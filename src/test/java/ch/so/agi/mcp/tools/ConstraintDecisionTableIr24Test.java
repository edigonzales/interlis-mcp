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

class ConstraintDecisionTableIr24Test {

  private static final String MODEL = """
      INTERLIS 2.4;

      CONTRACTED TYPE MODEL Math_V2 (en) AT "http://www.interlis.ch/models"
      VERSION "2026-08-18" =
        FUNCTION sum(attributePath: TEXT): NUMERIC;
      END Math_V2.

      MODEL DecisionIr24Model (de)
      AT "https://example.org"
      VERSION "2026-08-18" =
        IMPORTS Math_V2;

        TOPIC Data =
          CLASS Haupt =
            Gewichtung : MANDATORY 0 .. 100;
          END Haupt;

          CLASS Neben =
            Gewichtung : MANDATORY 0 .. 100;
          END Neben;

          ASSOCIATION Haupt_Neben =
            Hauptobjekt -- {1} Haupt;
            Nebenauspraegung -- {0..3} Neben;
          END Haupt_Neben;
        END Data;
      END DecisionIr24Model.
      """;

  private final IliCompilerService compilerService = new IliCompilerService();
  private final ConstraintKnowledgeTools knowledgeTools = new ConstraintKnowledgeTools(compilerService);
  private final ConstraintReviewTools reviewTools = new ConstraintReviewTools(compilerService, knowledgeTools);
  private final ConstraintTestTools testTools = new ConstraintTestTools(compilerService);
  private final ConstraintContextService contextService = new ConstraintContextService(compilerService);
  private final ConstraintCaseGenerationTools caseTools = new ConstraintCaseGenerationTools(contextService, testTools);
  private final ConstraintDecisionTableTools tools = new ConstraintDecisionTableTools(new ConstraintAuthoringWorkflow(compilerService), caseTools);

  @Test
  void rendersAndProvesSameSemanticsWithInterlis24SurfaceSyntax() {
    ConstraintDecisionTableTools.DecisionRow withSecondary = row(
        "with secondary",
        sumDefined(true),
        sumPlusDirectEquals100());
    ConstraintDecisionTableTools.DecisionRow withoutSecondary = row(
        "without secondary",
        condition("Gewichtung", "==", 100),
        sumDefined(false));

    IliAuthoringResult result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionIr24Model.Data.Haupt",
        "WeightSum100",
        List.of(withSecondary, withoutSecondary));

    assertEquals(true, result.generated, String.valueOf(result));
    assertEquals(true, result.proofVerified, String.valueOf(result));

    String expression = String.valueOf(result.details.get("constraintExpression"));
    assertTrue(expression.contains("Math_V2.sum(\"Nebenauspraegung->Gewichtung\")"), expression);
    assertTrue(expression.contains("Math_V2.sum(\"Nebenauspraegung->Gewichtung\") + Gewichtung"), expression);
    assertTrue(!expression.contains("Math.add("), expression);

    IliAuthoringResult.ProofVerification verification =
        result.constraintProofs.getFirst().verification;
    assertEquals(true, verification.allPassed, String.valueOf(verification));
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
      String attribute, String operator, Object value) {
    ConstraintDecisionTableTools.DecisionCondition condition = new ConstraintDecisionTableTools.DecisionCondition();
    condition.attribute = attribute;
    condition.operator = operator;
    condition.value = value;
    return condition;
  }

  private ConstraintDecisionTableTools.DecisionCondition sumDefined(boolean defined) {
    ConstraintDecisionTableTools.DecisionCondition condition = new ConstraintDecisionTableTools.DecisionCondition();
    condition.attribute = "Nebenauspraegung->Gewichtung";
    condition.aggregate = "SUM";
    condition.defined = defined;
    return condition;
  }

  private ConstraintDecisionTableTools.DecisionCondition sumPlusDirectEquals100() {
    ConstraintDecisionTableTools.DecisionCondition condition = new ConstraintDecisionTableTools.DecisionCondition();
    condition.attribute = "Nebenauspraegung->Gewichtung";
    condition.aggregate = "SUM";
    condition.addAttribute = "Gewichtung";
    condition.operator = "==";
    condition.value = 100;
    return condition;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
