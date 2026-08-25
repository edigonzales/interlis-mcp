package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.constraint.ConstraintAuthoringWorkflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.service.IliCompilerService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintDecisionTableAfuTest {

  private static final String MODEL = """
      INTERLIS 2.3;

      CONTRACTED TYPE MODEL Math (en) AT "http://www.interlis.ch/models"
      VERSION "2018-11-19" =
        FUNCTION add(a: NUMERIC; b: NUMERIC): NUMERIC;
        FUNCTION sum(attributePath: TEXT): NUMERIC;
      END Math.

      MODEL SO_AFU_Bodeneinheiten_20251210 (de)
      AT "http://geo.so.ch/models/AFU"
      VERSION "2025-08-21" =
        IMPORTS Math;

        TOPIC Bodeneinheiten =
          CLASS Auspraegung =
            Gewichtung : MANDATORY 0 .. 100;
          END Auspraegung;

          CLASS BodeneinheitHauptauspraegung_Wald
          EXTENDS Auspraegung =
          END BodeneinheitHauptauspraegung_Wald;

          CLASS Nebenauspraegung_Wald
          EXTENDS Auspraegung =
          END Nebenauspraegung_Wald;

          ASSOCIATION Bodeneinheit_Nebenauspraegungen_Wald =
            Bodeneinheit -- {1} BodeneinheitHauptauspraegung_Wald;
            Nebenauspraegung -- {0..3} Nebenauspraegung_Wald;
          END Bodeneinheit_Nebenauspraegungen_Wald;
        END Bodeneinheiten;
      END SO_AFU_Bodeneinheiten_20251210.
      """;

  private static final String CONTEXT =
      "SO_AFU_Bodeneinheiten_20251210.Bodeneinheiten.BodeneinheitHauptauspraegung_Wald";

  private final IliCompilerService compilerService = new IliCompilerService();
  private final ConstraintKnowledgeTools knowledgeTools = new ConstraintKnowledgeTools(compilerService);
  private final ConstraintReviewTools reviewTools = new ConstraintReviewTools(compilerService, knowledgeTools);
  private final ConstraintTestTools testTools = new ConstraintTestTools(compilerService);
  private final ConstraintContextService contextService = new ConstraintContextService(compilerService);
  private final ConstraintCaseGenerationTools caseTools = new ConstraintCaseGenerationTools(contextService, testTools);
  private final ConstraintDecisionTableTools tools = new ConstraintDecisionTableTools(new ConstraintAuthoringWorkflow(compilerService), caseTools);

  @Test
  void generatesAndProvesProductionShapedAfuWeightingConstraint() {
    ConstraintDecisionTableTools.DecisionRow withSecondary = row(
        "with secondary weights",
        sumDefined("Nebenauspraegung->Gewichtung", true),
        sumPlusDirectEquals("Nebenauspraegung->Gewichtung", "Gewichtung", 100));
    ConstraintDecisionTableTools.DecisionRow withoutSecondary = row(
        "without secondary weights",
        condition("Gewichtung", "==", 100),
        sumDefined("Nebenauspraegung->Gewichtung", false));

    IliAuthoringResult result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        CONTEXT,
        "GewichtungSumme100_Wald",
        List.of(withSecondary, withoutSecondary));

    assertEquals(true, result.generated, String.valueOf(result));
    assertEquals(true, result.proofVerified, String.valueOf(result));

    String expression = String.valueOf(result.details.get("constraintExpression"));
    assertTrue(expression.contains("DEFINED(Math.sum(\"Nebenauspraegung->Gewichtung\"))"), expression);
    assertTrue(expression.contains(
        "Math.add(Math.sum(\"Nebenauspraegung->Gewichtung\"), Gewichtung) == 100"), expression);
    assertTrue(expression.contains("Gewichtung == 100"), expression);
    assertTrue(expression.contains("NOT(DEFINED(Math.sum(\"Nebenauspraegung->Gewichtung\")))"), expression);

    List<IliAuthoringResult.ProofCase> cases = result.constraintProofs.getFirst().generatedCases;
    assertTrue(cases.size() >= 5, String.valueOf(cases));

    IliAuthoringResult.ProofCase noSecondary = cases.stream()
        .filter(item -> "UNDEFINED".equals(String.valueOf(
            map(item.get("values")).get("Nebenauspraegung->Gewichtung"))))
        .filter(item -> "100".equals(String.valueOf(map(item.get("values")).get("Gewichtung"))))
        .findFirst()
        .orElseThrow();
    assertEquals(true, noSecondary.get("expectedConstraintValid"));
    assertEquals(1, ((Number) noSecondary.get("objectCount")).intValue());
    assertEquals(0, ((Number) noSecondary.get("associationLinkCount")).intValue());

    IliAuthoringResult.ProofCase definedWitness = cases.stream()
        .filter(item -> !"UNDEFINED".equals(String.valueOf(
            map(item.get("values")).get("Nebenauspraegung->Gewichtung"))))
        .filter(item -> Boolean.TRUE.equals(item.get("expectedConstraintValid")))
        .filter(item -> ((Number) item.get("associationLinkCount")).intValue() > 0)
        .findFirst()
        .orElseThrow();
    Map<String, Object> witnessValues = map(definedWitness.get("values"));
    BigDecimal sum = new BigDecimal(String.valueOf(witnessValues.get("Nebenauspraegung->Gewichtung")));
    BigDecimal main = new BigDecimal(String.valueOf(witnessValues.get("Gewichtung")));
    assertEquals(0, sum.add(main).compareTo(BigDecimal.valueOf(100)));

    assertTrue(cases.stream().anyMatch(item ->
        Boolean.FALSE.equals(item.get("expectedConstraintValid"))
            && ((Number) item.get("associationLinkCount")).intValue() > 0));

    IliAuthoringResult.ProofVerification verification =
        result.constraintProofs.getFirst().verification;
    assertEquals(true, verification.allPassed);
    assertEquals(verification.caseCount, verification.passedCount);
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

  private ConstraintDecisionTableTools.DecisionCondition sumDefined(String path, boolean defined) {
    ConstraintDecisionTableTools.DecisionCondition condition = new ConstraintDecisionTableTools.DecisionCondition();
    condition.attribute = path;
    condition.aggregate = "SUM";
    condition.defined = defined;
    return condition;
  }

  private ConstraintDecisionTableTools.DecisionCondition sumPlusDirectEquals(
      String path,
      String directAttribute,
      Object value) {
    ConstraintDecisionTableTools.DecisionCondition condition = new ConstraintDecisionTableTools.DecisionCondition();
    condition.attribute = path;
    condition.aggregate = "SUM";
    condition.addAttribute = directAttribute;
    condition.operator = "==";
    condition.value = value;
    return condition;
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
