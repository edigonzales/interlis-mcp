package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.constraint.ConstraintAuthoringWorkflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintDecisionTableToolsTest {

  private static final String MODEL = """
      INTERLIS 2.3;

      MODEL DecisionTableModel (en)
      AT "https://example.org"
      VERSION "2026-08-18" =
        TOPIC Data =
          CLASS Item =
            value : MANDATORY 0 .. 100;
            status : MANDATORY (draft, active, archived);
            enabled : MANDATORY BOOLEAN;
          END Item;
        END Data;
      END DecisionTableModel.
      """;

  private final IliCompilerService compilerService = new IliCompilerService();
  private final ConstraintKnowledgeTools knowledgeTools = new ConstraintKnowledgeTools(compilerService);
  private final ConstraintReviewTools reviewTools = new ConstraintReviewTools(compilerService, knowledgeTools);
  private final ConstraintTestTools testTools = new ConstraintTestTools(compilerService);
  private final ConstraintContextService contextService = new ConstraintContextService(compilerService);
  private final ConstraintCaseGenerationTools caseTools = new ConstraintCaseGenerationTools(contextService, testTools);
  private final ConstraintDecisionTableTools tools = new ConstraintDecisionTableTools(new ConstraintAuthoringWorkflow(compilerService), caseTools);

  @Test
  void generatesRangeConstraintAndProvesFourBoundaryValues() {
    ConstraintDecisionTableTools.DecisionRow allowed = row(
        "allowed range",
        condition("value", ">=", 10),
        condition("value", "<=", 20));

    IliAuthoringResult result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionTableModel.Data.Item",
        "ValueBetween10And20",
        List.of(allowed));

    assertEquals(true, result.generated, String.valueOf(result));
    assertEquals(true, result.proofVerified);
    assertEquals("(value >= 10 AND value <= 20)", result.details.get("constraintExpression"));
    assertTrue(result.updatedModelText.contains("ValueBetween10And20"));

    List<IliAuthoringResult.ProofCase> boundaryCases =
        result.constraintProofs.getFirst().generatedCases;
    assertEquals(4, boundaryCases.size());
    assertCase(boundaryCases, "value", "9", false);
    assertCase(boundaryCases, "value", "10", true);
    assertCase(boundaryCases, "value", "20", true);
    assertCase(boundaryCases, "value", "21", false);

    IliAuthoringResult.ProofVerification verification =
        result.constraintProofs.getFirst().verification;
    assertEquals(4, verification.caseCount);
    assertEquals(4, verification.passedCount);
    assertEquals(true, verification.allPassed);
  }

  @Test
  void joinsAllowedRowsWithOrAndValidatorProvesUnionBoundaries() {
    ConstraintDecisionTableTools.DecisionRow low = row(
        "low values",
        condition("value", ">=", 0),
        condition("value", "<=", 10));
    ConstraintDecisionTableTools.DecisionRow high = row(
        "high values",
        condition("value", ">=", 90),
        condition("value", "<=", 100));

    IliAuthoringResult result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionTableModel.Data.Item",
        "LowOrHigh",
        List.of(low, high));

    assertEquals(true, result.generated, String.valueOf(result));
    assertEquals(true, result.proofVerified);
    assertTrue(String.valueOf(result.details.get("constraintExpression")).contains(" OR "));

    List<IliAuthoringResult.ProofCase> boundaryCases =
        result.constraintProofs.getFirst().generatedCases;
    assertCase(boundaryCases, "value", "10", true);
    assertCase(boundaryCases, "value", "11", false);
    assertCase(boundaryCases, "value", "89", false);
    assertCase(boundaryCases, "value", "90", true);
  }

  @Test
  void provesAllDeclaredEnumValuesForAllowedEnumRows() {
    ConstraintDecisionTableTools.DecisionRow draft = row(
        "draft allowed",
        condition("status", "==", "draft"));
    ConstraintDecisionTableTools.DecisionRow active = row(
        "active allowed",
        condition("status", "==", "#active"));

    IliAuthoringResult result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionTableModel.Data.Item",
        "DraftOrActive",
        List.of(draft, active));

    assertEquals(true, result.generated);
    assertEquals(true, result.proofVerified);
    String expression = String.valueOf(result.details.get("constraintExpression"));
    assertTrue(expression.contains("status == #draft"));
    assertTrue(expression.contains("status == #active"));

    List<IliAuthoringResult.ProofCase> cases = result.constraintProofs.getFirst().generatedCases;
    assertCase(cases, "status", "draft", true);
    assertCase(cases, "status", "active", true);
    assertCase(cases, "status", "archived", false);

    assertEquals(true, result.constraintProofs.getFirst().verification.allPassed);
  }

  @Test
  void provesBothBooleanValues() {
    IliAuthoringResult result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionTableModel.Data.Item",
        "EnabledOnly",
        List.of(row("enabled", condition("enabled", "==", true))));

    assertEquals(true, result.generated, String.valueOf(result));
    assertEquals(true, result.proofVerified);
    assertEquals("enabled == #true", result.details.get("constraintExpression"));

    List<IliAuthoringResult.ProofCase> cases = result.constraintProofs.getFirst().generatedCases;
    assertCase(cases, "enabled", "true", true);
    assertCase(cases, "enabled", "false", false);

    assertEquals(true, result.constraintProofs.getFirst().verification.allPassed);
  }

  @Test
  void rejectsTextDecisionAttributeWithoutGuessing() {
    String textModel = MODEL.replace(
        "value : MANDATORY 0 .. 100;",
        "value : MANDATORY TEXT*20;");

    IliAuthoringResult result = tools.generateIliConstraintFromDecisionTable(
        textModel,
        "DecisionTableModel.Data.Item",
        "NumericOnly",
        List.of(row("row", condition("value", ">=", 10))));

    assertEquals(false, result.generated);
    assertEquals(false, result.proofVerified, String.valueOf(result));
    assertTrue(List.of("CANDIDATE_MODEL_INVALID", "UNSUPPORTED_ATTRIBUTE_TYPE", "PROOF_INCOMPLETE")
        .contains(String.valueOf(result.reasonCode)));
    assertTrue(result.constraintProofs.isEmpty()
        || result.constraintProofs.getFirst().verification == null);
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
      String attribute,
      String expectedValue,
      boolean expectedValid) {
    IliAuthoringResult.ProofCase match = cases.stream()
        .filter(item -> expectedValue.equals(String.valueOf(map(item.get("values")).get(attribute))))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "Missing decision-table value " + attribute + "=" + expectedValue + ": " + cases));
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
