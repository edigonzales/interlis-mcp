package ch.so.agi.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StandardFunctionCatalogCompatibilityTest {

  @Test
  void constraintCatalogExposesStableSemanticIdsFromRegistry() {
    ConstraintKnowledgeTools tools = new ConstraintKnowledgeTools(
        new ch.so.agi.mcp.service.IliCompilerService());
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

  private void assertSemantic(List<Map<String, Object>> functions, String name, String semanticId) {
    Map<String, Object> function = functions.stream()
        .filter(item -> name.equals(item.get("name")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing function " + name));
    assertEquals(semanticId, function.get("semanticId"));
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
