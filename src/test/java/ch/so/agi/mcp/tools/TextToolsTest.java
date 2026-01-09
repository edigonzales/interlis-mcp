package ch.so.agi.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextToolsTest {

  private final TextTools textTools = new TextTools();

  @Test
  void listTextFunctions_defaultsToLatestVersion() {
    Map<String, Object> result = textTools.listTextFunctions(null);

    assertEquals("2.4", result.get("iliVersion"));

    @SuppressWarnings("unchecked")
    var functions = (Iterable<Map<String, String>>) result.get("functions");

    long count = countEntries(functions);
    assertEquals(24, count);
    assertTrue(streamHasFunction(functions, "Text_V2.compareToIgnoreCaseM(a: MTEXT; b: MTEXT)", "NUMERIC"));
  }

  @Test
  void listTextFunctions_supportsExplicitVersion() {
    Map<String, Object> result = textTools.listTextFunctions("2.3");

    assertEquals("2.3", result.get("iliVersion"));

    @SuppressWarnings("unchecked")
    var functions = (Iterable<Map<String, String>>) result.get("functions");

    long count = countEntries(functions);
    assertEquals(24, count);
    assertTrue(streamHasFunction(functions, "Text.substring(val: TEXT; beginIndex: NUMERIC; endIndex: NUMERIC)", "TEXT"));
  }

  private long countEntries(Iterable<Map<String, String>> functions) {
    long count = 0;
    for (Map<String, String> entry : functions) {
      if (!entry.isEmpty()) {
        count++;
      }
    }
    return count;
  }

  private boolean streamHasFunction(Iterable<Map<String, String>> functions, String function, String returns) {
    for (Map<String, String> entry : functions) {
      if (Objects.equals(function, entry.get("function")) && Objects.equals(returns, entry.get("returns"))) {
        return true;
      }
    }
    return false;
  }
}
