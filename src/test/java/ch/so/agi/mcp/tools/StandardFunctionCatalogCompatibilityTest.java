package ch.so.agi.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StandardFunctionCatalogCompatibilityTest {

  @Test
  void mathCatalogKeepsExisting23And24Signatures() {
    MathTools tools = new MathTools();
    List<Map<String, String>> math23 = functions(tools.listMathFunctions("2.3"));
    List<Map<String, String>> math24 = functions(tools.listMathFunctions("2.4"));

    assertEquals(30, math23.size());
    assertEquals(30, math24.size());
    assertFunction(math23, "Math.add(a: NUMERIC; b: NUMERIC)", "NUMERIC");
    assertFunction(math24, "Math_V2.add(a: NUMERIC; b: NUMERIC)", "NUMERIC");
    assertFunction(math23, "Math.atan2(ordinate: NUMERIC; abscissa: NUMERIC)", "NUMERIC");
    assertFunction(math24, "Math_V2.sum(attributePath: TEXT)", "NUMERIC");
  }

  @Test
  void textCatalogKeepsExistingParameterNamesAndTypes() {
    TextTools tools = new TextTools();
    List<Map<String, String>> text23 = functions(tools.listTextFunctions("2.3"));
    List<Map<String, String>> text24 = functions(tools.listTextFunctions("2.4"));

    assertEquals(24, text23.size());
    assertEquals(24, text24.size());
    assertFunction(text23, "Text.endsWith(val: TEXT; suffix: TEXT)", "BOOLEAN");
    assertFunction(text24, "Text_V2.equalsIgnoreCase(val: TEXT; anotherVal: TEXT)", "BOOLEAN");
    assertFunction(text23, "Text.substring(val: TEXT; beginIndex: NUMERIC; endIndex: NUMERIC)", "TEXT");
    assertFunction(text24, "Text_V2.replaceM(val: MTEXT; old: MTEXT; new: MTEXT)", "MTEXT");
  }

  @Test
  void constraintCatalogExposesStableSemanticIdsFromRegistry() {
    ConstraintKnowledgeTools tools = new ConstraintKnowledgeTools(
        new MathTools(), new TextTools(), new ch.so.agi.mcp.service.IliCompilerService());
    List<Map<String, Object>> functions = objectFunctions(tools.listConstraintFunctions("2.4"));

    assertEquals(54, functions.size());
    assertSemantic(functions, "Math_V2.add", "NUMERIC_ADD");
    assertSemantic(functions, "Math_V2.sum", "COLLECTION_SUM");
    assertSemantic(functions, "Text_V2.startsWith", "TEXT_STARTS_WITH");
    assertSemantic(functions, "Text_V2.startsWithM", "MTEXT_STARTS_WITH");

    Map<String, Object> sum = functions.stream()
        .filter(function -> "Math_V2.sum".equals(function.get("name")))
        .findFirst()
        .orElseThrow();
    List<Map<String, Object>> parameters = objectList(sum.get("parameters"));
    assertEquals("ATTRIBUTE_PATH", parameters.getFirst().get("semanticType"));
  }

  private void assertFunction(List<Map<String, String>> functions, String signature, String returns) {
    assertTrue(functions.stream().anyMatch(function ->
        signature.equals(function.get("function")) && returns.equals(function.get("returns"))),
        () -> "Missing function " + signature + " in " + functions);
  }

  private void assertSemantic(List<Map<String, Object>> functions, String name, String semanticId) {
    Map<String, Object> function = functions.stream()
        .filter(item -> name.equals(item.get("name")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing function " + name));
    assertEquals(semanticId, function.get("semanticId"));
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, String>> functions(Map<String, Object> result) {
    return (List<Map<String, String>>) (List<?>) result.get("functions");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> objectFunctions(Map<String, Object> result) {
    return (List<Map<String, Object>>) result.get("functions");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> objectList(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
