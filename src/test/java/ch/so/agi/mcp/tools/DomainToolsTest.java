package ch.so.agi.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DomainToolsTest {

  private final DomainTools domainTools = new DomainTools();

  @Test
  void createCoordDomainSnippet_defaultsTo2dAndMillimeter() {
    Map<String, Object> result = domainTools.createCoordDomainSnippet("Coord2", null, null);

    assertEquals(
        """
            DOMAIN
              Coord2 = COORD
                460000.000 .. 870000.000 [INTERLIS.m],
                45000.000 .. 310000.000 [INTERLIS.m],
                ROTATION 2 -> 1;""",
        result.get("iliSnippet"));
    assertEquals(1, ((Map<?, ?>) result.get("cursorHint")).get("line"));
    assertEquals(2, ((Map<?, ?>) result.get("cursorHint")).get("col"));
  }

  @Test
  void createCoordDomainSnippet_infers3dFromNameAndRespectsDecimals() {
    Map<String, Object> result = domainTools.createCoordDomainSnippet("Coord3", null, 1);

    assertEquals(
        """
            DOMAIN
              Coord3 = COORD
                460000.0 .. 870000.0 [INTERLIS.m],
                45000.0 .. 310000.0 [INTERLIS.m],
                -200.0 .. 5000.0 [INTERLIS.m]
                ROTATION 2 -> 1;""",
        result.get("iliSnippet"));
  }

  @Test
  void createCoordDomainSnippet_rejectsInvalidDimension() {
    assertThrows(
        IllegalArgumentException.class,
        () -> domainTools.createCoordDomainSnippet("CoordX", 4, null));
  }
}
