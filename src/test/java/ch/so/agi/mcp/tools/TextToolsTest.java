package ch.so.agi.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextToolsTest {

  private final TextTools textTools = new TextTools();

  @Test
  void listTextFunctions_returnsFunctionsForBothVersions() {
    Map<String, Object> result = textTools.listTextFunctions();

    assertTrue(result.containsKey("2.3"));
    assertTrue(result.containsKey("2.4"));

    @SuppressWarnings("unchecked")
    List<String> v23 = (List<String>) result.get("2.3");
    @SuppressWarnings("unchecked")
    List<String> v24 = (List<String>) result.get("2.4");

    assertEquals(24, v23.size());
    assertEquals(24, v24.size());
    assertTrue(v23.contains("substring(val: TEXT; beginIndex: NUMERIC; endIndex: NUMERIC): TEXT"));
    assertTrue(v24.contains("compareToIgnoreCaseM(a: MTEXT; b: MTEXT): NUMERIC"));
  }
}
