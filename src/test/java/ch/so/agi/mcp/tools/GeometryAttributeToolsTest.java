package ch.so.agi.mcp.tools;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeometryAttributeToolsTest {

  private final GeometryAttributeTools geometryAttributeTools = new GeometryAttributeTools(new DomainTools());

  @Test
  void ensureGeometryDependencies_buildsSurfaceAttributeAndDomain() {
    Map<String, Object> result = geometryAttributeTools.ensureGeometryDependencies(
        "attr2", 2, true, new BigDecimal("2"), false, "2.4", "SURFACE", false);

    assertEquals("attr2 : SURFACE WITH (STRAIGHTS, ARCS)\n        VERTEX Coord2\n        WITHOUT OVERLAPS > 0.002;", result.get("attributeLine"));
    assertEquals(1, ((java.util.List<?>) result.get("importsToAdd")).size());
    assertTrue(((java.util.List<?>) result.get("importsToAdd")).contains("INTERLIS"));
    assertEquals(1, ((java.util.List<?>) result.get("domainsToAdd")).size());
    assertEquals(2, ((java.util.List<?>) result.get("notes")).size());
  }

  @Test
  void ensureGeometryDependencies_usesChbaseModels() {
    Map<String, Object> result = geometryAttributeTools.ensureGeometryDependencies(
        "geom", 2, false, null, true, "2.4", "SURFACE", false);

    assertEquals("geom :GeometryCHLV95_V2.MultiSurfaceWithoutArcs;", result.get("attributeLine"));
    assertTrue(((java.util.List<?>) result.get("importsToAdd")).contains("GeometryCHLV95_V2"));
    assertTrue(((java.util.List<?>) result.get("domainsToAdd")).isEmpty());
  }

  @Test
  void ensureGeometryDependencies_supportsDirectedPolyLine() {
    Map<String, Object> result = geometryAttributeTools.ensureGeometryDependencies(
        "linie", 2, false, new BigDecimal("1.5"), false, "2.4", "POLYLINE", true);

    assertEquals("linie : DIRECTED POLYLINE WITH (STRAIGHTS)\n        VERTEX Coord2\n        WITHOUT OVERLAPS > 0.0015;", result.get("attributeLine"));
  }

  @Test
  void ensureGeometryDependencies_supportsChbaseCoord() {
    Map<String, Object> result = geometryAttributeTools.ensureGeometryDependencies(
        "pos", 3, false, null, true, "2.4", "COORD", false);

    assertEquals("pos :GeometryCHLV95_V2.Coord3;", result.get("attributeLine"));
    assertTrue(((java.util.List<?>) result.get("domainsToAdd")).isEmpty());
  }
}
