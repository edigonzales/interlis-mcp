package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.util.NameValidator;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class GeometryAttributeTools {

  public Map<String, Object> ensureGeometryDependencies(
      @McpToolParam(description = "Attributname", required = true) String attributeName,
      @McpToolParam(description = "Kreisbogen erlaubt (true = WITH ARCS)", required = false) @Nullable Boolean arcs,
      @McpToolParam(description = "Erlaubte Überlappung in Millimeter (Default 1)", required = false) @Nullable BigDecimal overlapMm,
      @McpToolParam(description = "CHBase-Geometrien verwenden", required = false) @Nullable Boolean chbase,
      @McpToolParam(description = "INTERLIS Sprachversion (2.3 oder 2.4)", required = false) @Nullable String iliVersion,
      @McpToolParam(description = "Geometrietyp, z. B. SURFACE (Default)", required = false) @Nullable String geometryType,
      @McpToolParam(description = "Explizite Koordinatendomain für INTERLIS-Geometrien; bei chbase=false erforderlich", required = false) @Nullable String coordDomainFqn,
      @McpToolParam(description = "Linienzug ist DIRECTED (nur Polyline/MultiPolyline)", required = false) @Nullable Boolean directed,
      @McpToolParam(description = "Attribut ist MANDATORY", required = false) @Nullable Boolean mandatory,
      @McpToolParam(description = "Collection: LIST OF oder BAG OF", required = false) @Nullable String collection
  ) {
    var nv = NameValidator.ascii();
    nv.validateIdent(attributeName, "Attribute name");

    boolean useArcs = Boolean.TRUE.equals(arcs);
    BigDecimal overlapMeters = (overlapMm == null ? BigDecimal.ONE : overlapMm).movePointLeft(3);
    if (overlapMeters.compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException("overlap must be >= 0.");
    }

    boolean useChBase = Boolean.TRUE.equals(chbase);
    String ili = (iliVersion == null || iliVersion.isBlank()) ? "2.4" : iliVersion.trim();
    if (!"2.3".equals(ili) && !"2.4".equals(ili)) {
      throw new IllegalArgumentException("iliVersion must be '2.3' oder '2.4'.");
    }

    String geom = (geometryType == null || geometryType.isBlank())
        ? (useChBase ? "Surface" : "SURFACE")
        : geometryType.trim();
    if (!useChBase) {
      geom = geom.toUpperCase(Locale.ROOT);
      if (!interlisGeometryTypes(ili).contains(geom)) {
        throw new IllegalArgumentException("Unsupported INTERLIS geometry type '" + geom + "' for version " + ili + ".");
      }
    }

    boolean isDirected = Boolean.TRUE.equals(directed);
    if (isDirected && !isLineGeometry(geom, useChBase)) {
      throw new IllegalArgumentException("DIRECTED ist nur für Linien-Typen erlaubt.");
    }

    List<String> imports = new ArrayList<>();
    List<String> domains = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    String attributeLine;
    String attributePrefix = buildAttributePrefix(attributeName.trim(), mandatory, collection);

    if (useChBase) {
      String modelName = "2.3".equals(ili) ? "GeometryCHLV95_V1" : "GeometryCHLV95_V2";
      imports.add(modelName);
      String qualifiedType = qualifyChBaseGeometry(geom, modelName, ili);
      attributeLine = attributePrefix + qualifiedType + ";";
    } else {
      if (coordDomainFqn == null || coordDomainFqn.isBlank()) {
        throw new IllegalArgumentException("coordDomainFqn is required when chbase=false.");
      }
      String coordDomain = coordDomainFqn.trim();
      nv.validateFqn(coordDomain, "Coordinate domain");
      imports.add("INTERLIS");
      notes.add("Verwendet die explizit angegebene Koordinatendomain " + coordDomain + ".");
      notes.add("Tolerance ist in Metern interpretiert");

      attributeLine = buildGeometryAttribute(attributePrefix, geom, coordDomain, useArcs, overlapMeters, isDirected);
    }

    List<String> importLines = new ArrayList<>();
    for (String model : imports) {
      importLines.add("IMPORTS " + model + ";");
    }

    return Map.of(
        "importLinesToAdd", importLines,
        "domainsToAdd", domains,
        "attributeLine", attributeLine,
        "notes", notes
    );
  }

  @McpTool(
      name = "listGeometryTypes",
      description = "Liste alle unterstützten Geometrietypen für die gewünschte INTERLIS-Sprachversion und Modell (INTERLIS/CHBase).",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false)
  )
  public Map<String, Object> listGeometryTypes(
      @McpToolParam(description = "INTERLIS Sprachversion (2.3 oder 2.4)", required = false) @Nullable String iliVersion
  ) {
    String ili = normalizeIliVersion(iliVersion);
    String modelName = "2.3".equals(ili) ? "GeometryCHLV95_V1" : "GeometryCHLV95_V2";
    List<Map<String, String>> entries = new ArrayList<>();
    interlisGeometryTypes(ili).forEach(type -> entries.add(Map.of(
        "name", type,
        "model", "INTERLIS"
    )));
    chBaseGeometryTypes(ili).forEach(type -> entries.add(Map.of(
        "name", modelName + "." + type,
        "model", "CHBase"
    )));
    return Map.of(
        "iliVersion", ili,
        "types", entries
    );
  }

  private String normalizeIliVersion(@Nullable String iliVersion) {
    String ili = (iliVersion == null || iliVersion.isBlank()) ? "2.4" : iliVersion.trim();
    if (!"2.3".equals(ili) && !"2.4".equals(ili)) {
      throw new IllegalArgumentException("iliVersion must be '2.3' oder '2.4'.");
    }
    return ili;
  }

  private List<String> interlisGeometryTypes(String ili) {
    if ("2.3".equals(ili)) {
      return List.of("POLYLINE", "SURFACE", "AREA");
    }
    if ("2.4".equals(ili)) {
      return List.of("POLYLINE", "SURFACE", "AREA", "MULTIPOLYLINE", "MULTISURFACE", "MULTIAREA");
    }
    throw new IllegalArgumentException("iliVersion must be '2.3' oder '2.4'.");
  }

  private String buildGeometryAttribute(String attributePrefix, String geom, String coordDomainName, boolean useArcs, BigDecimal overlapMeters, boolean directed) {
    String lineForm = useArcs ? "WITH (STRAIGHTS, ARCS)" : "WITH (STRAIGHTS)";
    String overlap = overlapMeters.stripTrailingZeros().toPlainString();
    String directedPrefix = (directed && isLineGeometry(geom, false)) ? "DIRECTED " : "";

    return attributePrefix + directedPrefix + geom + " " + lineForm + "\n        VERTEX " + coordDomainName + "\n        WITHOUT OVERLAPS > " + overlap + ";";
  }

  private String qualifyChBaseGeometry(String geom, String modelName, String ili) {
    String trimmed = geom.trim();
    String baseName = trimmed.contains(".") ? trimmed.substring(trimmed.lastIndexOf('.') + 1) : trimmed;
    if ("COORD".equalsIgnoreCase(baseName)) {
      throw new IllegalArgumentException("CHBase bietet nur Coord2 oder Coord3.");
    }
    if (trimmed.contains(".") && !trimmed.startsWith(modelName + ".")) {
      throw new IllegalArgumentException("CHBase geometry must belong to " + modelName + ".");
    }
    String canonical = ("2.3".equals(ili) ? chBase23() : chBase24())
        .get(baseName.toUpperCase(Locale.ROOT));
    if (canonical == null) {
      throw new IllegalArgumentException("Unsupported CHBase geometry type '" + baseName + "' for version " + ili + ".");
    }
    return modelName + "." + canonical;
  }

  private String buildAttributePrefix(String attributeName, @Nullable Boolean mandatory, @Nullable String collectionRaw) {
    String mandatoryPrefix = Boolean.TRUE.equals(mandatory) ? "MANDATORY " : "";
    String collectionPrefix = normalizeCollection(collectionRaw);
    return attributeName + " : " + mandatoryPrefix + collectionPrefix;
  }

  private String normalizeCollection(@Nullable String collectionRaw) {
    if (collectionRaw == null || collectionRaw.isBlank()) return "";
    String key = collectionRaw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    return switch (key) {
      case "LIST", "LIST_OF" -> "LIST OF ";
      case "BAG", "BAG_OF" -> "BAG OF ";
      case "", "NONE" -> "";
      default -> throw new IllegalArgumentException("collection must be LIST OF, BAG OF oder leer.");
    };
  }

  private boolean isLineGeometry(String geom, boolean chbase) {
    if (chbase) {
      String baseName = geom.contains(".") ? geom.substring(geom.lastIndexOf('.') + 1) : geom;
      String upper = baseName.toUpperCase(Locale.ROOT);
      return upper.contains("LINE");
    }
    return geom.equals("POLYLINE") || geom.equals("MULTIPOLYLINE") || geom.equals("LINE") || geom.equals("MULTILINE");
  }

  private Set<String> chBaseGeometryTypes(String ili) {
    return new LinkedHashSet<>(new GeometryTypeRenderer().supportedChBaseTypes(ili));
  }

  private Map<String, String> chBase23() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("SURFACE", "Surface");
    map.put("AREA", "Area");
    map.put("LINE", "Line");
    map.put("DIRECTEDLINE", "DirectedLine");
    map.put("LINEWITHALTITUDE", "LineWithAltitude");
    map.put("DIRECTEDLINEWITHALTITUDE", "DirectedLineWithAltitude");
    map.put("SURFACEWITHOVERLAPS2MM", "SurfaceWithOverlaps2mm");
    map.put("AREAWITHOVERLAPS2MM", "AreaWithOverlaps2mm");
    map.put("POLYLINE", "Line");
    return map;
  }

  private Map<String, String> chBase24() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("COORD2", "Coord2");
    map.put("COORD3", "Coord3");
    map.put("MULTIPOINT", "MultiPoint");
    map.put("MULTIPOINT2", "MultiPoint");
    map.put("MULTIPOINT3", "MultiPoint3D");
    map.put("MULTIPOINT3D", "MultiPoint3D");
    map.put("SURFACE", "Surface");
    map.put("AREA", "Area");
    map.put("LINE", "Line");
    map.put("DIRECTEDLINE", "DirectedLine");
    map.put("LINEWITHALTITUDE", "LineWithAltitude");
    map.put("DIRECTEDLINEWITHALTITUDE", "DirectedLineWithAltitude");
    map.put("MULTILINE", "MultiLine");
    map.put("MULTIDIRECTEDLINE", "MultiDirectedLine");
    map.put("SURFACEWITHOUTARCS", "SurfaceWithoutArcs");
    map.put("AREAWITHOUTARCS", "AreaWithoutArcs");
    map.put("LINEWITHOUTARCS", "LineWithoutArcs");
    map.put("DIRECTEDLINEWITHOUTARCS", "DirectedLineWithoutArcs");
    map.put("LINEWITHALTITUDEWITHOUTARCS", "LineWithAltitudeWithoutArcs");
    map.put("DIRECTEDLINEWITHALTITUDEWITHOUTARCS", "DirectedLineWithAltitudeWithoutArcs");
    map.put("MULTILINEWITHOUTARCS", "MultiLineWithoutArcs");
    map.put("MULTIDIRECTEDLINEWITHOUTARCS", "MultiDirectedLineWithoutArcs");
    map.put("MULTISURFACEWITHOUTARCS", "MultiSurfaceWithoutArcs");
    map.put("MULTISURFACE", "MultiSurface");
    map.put("MULTIAREA", "Area");
    map.put("MULTIPOLYLINE", "MultiLine");
    map.put("POLYLINE", "Line");
    return map;
  }
}
