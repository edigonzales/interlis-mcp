package ch.so.agi.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnitsToolsTest {

  private final UnitsTools unitsTools = new UnitsTools();

  @Test
  void listUnits_returnsUnitsForBothVersions() {
    Map<String, Object> result = unitsTools.listUnits();

    assertTrue(result.containsKey("2.3"));
    assertTrue(result.containsKey("2.4"));

    @SuppressWarnings("unchecked")
    List<String> v23 = (List<String>) result.get("2.3");
    @SuppressWarnings("unchecked")
    List<String> v24 = (List<String>) result.get("2.4");

    assertEquals(62, v23.size());
    assertEquals(62, v24.size());
    assertTrue(v23.contains("Bar [bar] = 100000 [Pa];"));
    assertTrue(v24.contains("Torr = 133.3224 [Pa]; !! Torr = [mmHg]"));
    assertTrue(v24.contains("Degree_Fahrenheit [oF] = FUNCTION // (oF+459.67)/1.8 // [INTERLIS.K];"));
  }
}
