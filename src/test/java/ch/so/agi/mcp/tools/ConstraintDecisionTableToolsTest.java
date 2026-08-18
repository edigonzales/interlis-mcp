package ch.so.agi.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  private final ConstraintKnowledgeTools knowledgeTools = new ConstraintKnowledgeTools(
      new MathTools(), new TextTools(), compilerService);
  private final ConstraintReviewTools reviewTools = new ConstraintReviewTools(compilerService, knowledgeTools);
  private final ConstraintTestTools testTools = new ConstraintTestTools(compilerService);
  private final ConstraintDecisionTableTools tools = new ConstraintDecisionTableTools(reviewTools, testTools);

  @Test
  void generatesRangeConstraintAndProvesFourBoundaryValues() {
    ConstraintDecisionTableTools.DecisionRow allowed = row(
        "allowed range",
        condition("value", ">=", 10),
        condition("value", "<=", 20));

    Map<String, Object> result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionTableModel.Data.Item",
        "ValueBetween10And20",
        List.of(allowed),
        null);

    assertEquals(true, result.get("generated"));
    assertEquals(true, result.get("proofVerified"));
    assertEquals("(value >= 10 AND value <= 20)", result.get("constraintExpression"));
    assertTrue(String.valueOf(result.get("constraintBlock")).contains("ValueBetween10And20"));

    List<Map<String, Object>> boundaryCases = list(result.get("boundaryCases"));
    assertEquals(4, boundaryCases.size());
    assertCase(boundaryCases, "value", "9", false);
    assertCase(boundaryCases, "value", "10", true);
    assertCase(boundaryCases, "value", "20", true);
    assertCase(boundaryCases, "value", "21", false);

    Map<String, Object> verification = map(result.get("verification"));
    assertEquals(4, verification.get("caseCount"));
    assertEquals(4, verification.get("passedCount"));
    assertEquals(true, verification.get("allPassed"));
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

    Map<String, Object> result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionTableModel.Data.Item",
        "LowOrHigh",
        List.of(low, high),
        null);

    assertEquals(true, result.get("generated"));
    assertEquals(true, result.get("proofVerified"));
    assertTrue(String.valueOf(result.get("constraintExpression")).contains(" OR "));

    List<Map<String, Object>> boundaryCases = list(result.get("boundaryCases"));
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

    Map<String, Object> result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionTableModel.Data.Item",
        "DraftOrActive",
        List.of(draft, active),
        null);

    assertEquals(true, result.get("generated"));
    assertEquals(true, result.get("proofVerified"));
    String expression = String.valueOf(result.get("constraintExpression"));
    assertTrue(expression.contains("status == #draft"));
    assertTrue(expression.contains("status == #active"));

    List<Map<String, Object>> cases = list(result.get("boundaryCases"));
    assertCase(cases, "status", "draft", true);
    assertCase(cases, "status", "active", true);
    assertCase(cases, "status", "archived", false);

    Map<String, Object> verification = map(result.get("verification"));
    assertEquals(true, verification.get("allPassed"));
  }

  @Test
  void provesBothBooleanValues() {
    Map<String, Object> result = tools.generateIliConstraintFromDecisionTable(
        MODEL,
        "DecisionTableModel.Data.Item",
        "EnabledOnly",
        List.of(row("enabled", condition("enabled", "==", true))),
        null);

    assertEquals(true, result.get("generated"));
    assertEquals(true, result.get("proofVerified"));
    assertEquals("enabled == #true", result.get("constraintExpression"));

    List<Map<String, Object>> cases = list(result.get("boundaryCases"));
    assertCase(cases, "enabled", "true", true);
    assertCase(cases, "enabled", "false", false);

    Map<String, Object> verification = map(result.get("verification"));
    assertEquals(true, verification.get("allPassed"));
  }

  @Test
  void rejectsTextDecisionAttributeWithoutGuessing() {
    String textModel = MODEL.replace(
        "value : MANDATORY 0 .. 100;",
        "value : MANDATORY TEXT*20;");

    Map<String, Object> result = tools.generateIliConstraintFromDecisionTable(
        textModel,
        "DecisionTableModel.Data.Item",
        "NumericOnly",
        List.of(row("row", condition("value", ">=", 10))),
        null);

    assertEquals(false, result.get("generated"));
    assertEquals(false, result.get("proofVerified"));
    assertTrue(List.of("GENERATED_CONSTRAINT_NOT_COMPILABLE", "UNSUPPORTED_ATTRIBUTE_TYPE")
        .contains(String.valueOf(result.get("reasonCode"))));
    assertFalse(result.containsKey("verification"));
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
      String attribute,
      String expectedValue,
      boolean expectedValid) {
    Map<String, Object> match = cases.stream()
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
