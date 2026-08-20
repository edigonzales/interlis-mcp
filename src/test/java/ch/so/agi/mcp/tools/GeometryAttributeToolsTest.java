package ch.so.agi.mcp.tools;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeometryAttributeToolsTest {

  private final GeometryAttributeTools geometryAttributeTools = new GeometryAttributeTools();

  @Test
  void ensureGeometryDependencies_buildsSurfaceAttributeAndDomain() {
    Map<String, Object> result = geometryAttributeTools.ensureGeometryDependencies(
        "attr2", true, new BigDecimal("2"), false, "2.4", "SURFACE", "Demo.Coord2", false, null, null);

    assertEquals("attr2 : SURFACE WITH (STRAIGHTS, ARCS)\n        VERTEX Demo.Coord2\n        WITHOUT OVERLAPS > 0.002;", result.get("attributeLine"));
    assertEquals(1, ((java.util.List<?>) result.get("importLinesToAdd")).size());
    assertTrue(((java.util.List<?>) result.get("importLinesToAdd")).contains("IMPORTS INTERLIS;"));
    assertTrue(((java.util.List<?>) result.get("domainsToAdd")).isEmpty());
    assertEquals(2, ((java.util.List<?>) result.get("notes")).size());
  }

  @Test
  void ensureGeometryDependencies_usesChbaseModels() {
    Map<String, Object> result = geometryAttributeTools.ensureGeometryDependencies(
        "geom", false, null, true, "2.4", "GeometryCHLV95_V2.MultiSurfaceWithoutArcs", null, false, null, null);

    assertEquals("geom : GeometryCHLV95_V2.MultiSurfaceWithoutArcs;", result.get("attributeLine"));
    assertTrue(((java.util.List<?>) result.get("importLinesToAdd")).contains("IMPORTS GeometryCHLV95_V2;"));
    assertTrue(((java.util.List<?>) result.get("domainsToAdd")).isEmpty());
  }

  @Test
  void ensureGeometryDependencies_supportsDirectedPolyLine() {
    Map<String, Object> result = geometryAttributeTools.ensureGeometryDependencies(
        "linie", false, new BigDecimal("1.5"), false, "2.4", "POLYLINE", "Coord2", true, null, null);

    assertEquals("linie : DIRECTED POLYLINE WITH (STRAIGHTS)\n        VERTEX Coord2\n        WITHOUT OVERLAPS > 0.0015;", result.get("attributeLine"));
  }

  @Test
  void ensureGeometryDependencies_supportsChbaseCoord() {
    Map<String, Object> result = geometryAttributeTools.ensureGeometryDependencies(
        "pos", false, null, true, "2.4", "GeometryCHLV95_V2.Coord3", null, false, null, null);

    assertEquals("pos : GeometryCHLV95_V2.Coord3;", result.get("attributeLine"));
    assertTrue(((java.util.List<?>) result.get("domainsToAdd")).isEmpty());
  }

  @Test
  void ensureGeometryDependencies_supportsMandatoryAndCollection() {
    Map<String, Object> result = geometryAttributeTools.ensureGeometryDependencies(
        "lage", false, new BigDecimal("1"), false, "2.4", "MULTISURFACE", "Coord2", false, true, "LIST OF");

    assertEquals("lage : MANDATORY LIST OF MULTISURFACE WITH (STRAIGHTS)\n        VERTEX Coord2\n        WITHOUT OVERLAPS > 0.001;", result.get("attributeLine"));
  }
}
