package ch.so.agi.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathToolsTest {

  private final MathTools mathTools = new MathTools();

  @Test
  void listMathFunctions_returnsFunctionsForBothVersions() {
    Map<String, Object> result = mathTools.listMathFunctions();

    assertTrue(result.containsKey("2.3"));
    assertTrue(result.containsKey("2.4"));

    @SuppressWarnings("unchecked")
    List<String> v23 = (List<String>) result.get("2.3");
    @SuppressWarnings("unchecked")
    List<String> v24 = (List<String>) result.get("2.4");

    assertEquals(30, v23.size());
    assertEquals(30, v24.size());
    assertTrue(v23.contains("atan2(ordinate: NUMERIC; abscissa: NUMERIC): NUMERIC"));
    assertTrue(v24.contains("avg(attributePath: TEXT): NUMERIC"));
  }
}
