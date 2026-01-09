package ch.so.agi.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathToolsTest {

  private final MathTools mathTools = new MathTools();

  @Test
  void listMathFunctions_defaultsToLatestVersion() {
    Map<String, Object> result = mathTools.listMathFunctions(null);

    assertEquals("2.4", result.get("iliVersion"));

    @SuppressWarnings("unchecked")
    var functions = (Iterable<Map<String, String>>) result.get("functions");

    long count = countEntries(functions);
    assertEquals(30, count);
    assertTrue(streamHasFunction(functions, "avg(attributePath: TEXT)", "NUMERIC"));
  }

  @Test
  void listMathFunctions_supportsExplicitVersion() {
    Map<String, Object> result = mathTools.listMathFunctions("2.3");

    assertEquals("2.3", result.get("iliVersion"));

    @SuppressWarnings("unchecked")
    var functions = (Iterable<Map<String, String>>) result.get("functions");

    long count = countEntries(functions);
    assertEquals(30, count);
    assertTrue(streamHasFunction(functions, "atan2(ordinate: NUMERIC; abscissa: NUMERIC)", "NUMERIC"));
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
