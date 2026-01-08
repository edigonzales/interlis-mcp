package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.util.NameValidator;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.lang.Nullable;
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

  private final DomainTools domainTools;

  public GeometryAttributeTools(DomainTools domainTools) {
    this.domainTools = domainTools;
  }

  @McpTool(
      name = "ensureGeometryDependencies",
      description = """
          Stellt sicher, dass für ein Geometrieattribut alle benötigten Imports/Domains vorhanden sind.
          Params: attributeName (required), dimension (default 2), arcs (default false), overlapMm (default 1),
          chbase (default false), iliVersion ('2.3' oder '2.4', default '2.4'), geometryType (default SURFACE), directed (default false).
          """
  )
  public Map<String, Object> ensureGeometryDependencies(
      @McpToolParam(description = "Attributname", required = true) String attributeName,
      @McpToolParam(description = "Koordinatendimension (2 oder 3)", required = false) @Nullable Integer dimension,
      @McpToolParam(description = "Kreisbogen erlaubt (true = WITH ARCS)", required = false) @Nullable Boolean arcs,
      @McpToolParam(description = "Erlaubte Überlappung in Millimeter (Default 1)", required = false) @Nullable BigDecimal overlapMm,
      @McpToolParam(description = "CHBase-Geometrien verwenden", required = false) @Nullable Boolean chbase,
      @McpToolParam(description = "INTERLIS Sprachversion (2.3 oder 2.4)", required = false) @Nullable String iliVersion,
      @McpToolParam(description = "Geometrietyp, z. B. SURFACE (Default)", required = false) @Nullable String geometryType,
      @McpToolParam(description = "Linienzug ist DIRECTED (nur Polyline/MultiPolyline)", required = false) @Nullable Boolean directed,
      @McpToolParam(description = "Attribut ist MANDATORY", required = false) @Nullable Boolean mandatory,
      @McpToolParam(description = "Collection: LIST OF oder BAG OF", required = false) @Nullable String collection
  ) {
    var nv = NameValidator.ascii();
    nv.validateIdent(attributeName, "Attribute name");

    int dim = dimension == null ? 2 : dimension;
    if (dim != 2 && dim != 3) {
      throw new IllegalArgumentException("dimension must be 2 or 3.");
    }

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

    String geomInput = (geometryType == null || geometryType.isBlank()) ? "SURFACE" : geometryType.trim();
    String geomKey = geomInput.toUpperCase(Locale.ROOT);
    String geom = useChBase ? normalizeGeometryTypeForChBase(geomKey, ili) : geomKey;
    if (!useChBase) {
      validateGeometryType(geom, ili);
    }

    boolean isDirected = Boolean.TRUE.equals(directed);
    if (isDirected && !isLineGeometry(geom, useChBase)) {
      throw new IllegalArgumentException("DIRECTED ist nur für Linien-Typen erlaubt.");
    }

    if (useChBase && dim == 3 && "2.3".equals(ili)) {
      throw new IllegalArgumentException("CHBase 2.3 bietet keine 3D-Surface-Domains.");
    }

    List<String> imports = new ArrayList<>();
    List<String> domains = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    String attributeLine;
    String attributePrefix = buildAttributePrefix(attributeName.trim(), mandatory, collection);

    if (useChBase) {
      String modelName = "2.3".equals(ili) ? "GeometryCHLV95_V1" : "GeometryCHLV95_V2";
      imports.add(modelName);
      attributeLine = attributePrefix + buildChBaseGeometry(modelName, geom, dim, useArcs, overlapMeters, ili, isDirected) + ";";
    } else {
      imports.add("INTERLIS");
      String coordDomainName = "Coord" + dim;
      nv.validateIdent(coordDomainName, "Coord domain name");
      String coordSnippet = (String) domainTools.createCoordDomainSnippet(coordDomainName, dim, null).get("iliSnippet");
      domains.add(coordSnippet);
      notes.add("Domain nach IMPORTS einfügen");
      notes.add("Tolerance ist in Metern interpretiert");

      attributeLine = buildGeometryAttribute(attributePrefix, geom, coordDomainName, useArcs, overlapMeters, isDirected);
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
      description = "Liste alle unterstützten Geometrietypen pro INTERLIS-Sprachversion und Modell (Standard/CHBase)."
  )
  public Map<String, Object> listGeometryTypes() {
    Map<String, Object> interlis = Map.of(
        "2.3", new ArrayList<>(interlisGeometryTypes("2.3")),
        "2.4", new ArrayList<>(interlisGeometryTypes("2.4"))
    );

    Map<String, Object> chBase = Map.of(
        "2.3", new ArrayList<>(chBaseGeometryTypes("2.3")),
        "2.4", new ArrayList<>(chBaseGeometryTypes("2.4"))
    );

    return Map.of(
        "INTERLIS", interlis,
        "CHBase", chBase
    );
  }

  private void validateGeometryType(String geom, String ili) {
    List<String> allowed = interlisGeometryTypes(ili);
    if (!allowed.contains(geom)) {
      throw new IllegalArgumentException("Geometrietyp nicht erlaubt für INTERLIS " + ili + ": " + geom);
    }
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

  private String normalizeGeometryTypeForChBase(String geomKey, String ili) {
    Map<String, String> allowed = "2.3".equals(ili) ? chBase23() : chBase24();
    String canonical = allowed.get(geomKey);
    if (canonical == null) {
      throw new IllegalArgumentException("Geometrietyp für CHBase nicht erlaubt: " + geomKey);
    }
    return canonical;
  }

  private String buildGeometryAttribute(String attributePrefix, String geom, String coordDomainName, boolean useArcs, BigDecimal overlapMeters, boolean directed) {
    String lineForm = useArcs ? "WITH (STRAIGHTS, ARCS)" : "WITH (STRAIGHTS)";
    String overlap = overlapMeters.stripTrailingZeros().toPlainString();
    String directedPrefix = (directed && isLineGeometry(geom, false)) ? "DIRECTED " : "";

    return attributePrefix + directedPrefix + geom + " " + lineForm + "\n        VERTEX " + coordDomainName + "\n        WITHOUT OVERLAPS > " + overlap + ";";
  }

  private String buildChBaseGeometry(String modelName, String geom, int dimension, boolean useArcs, BigDecimal overlapMeters, String iliVersion, boolean directed) {
    String upperGeom = geom.toUpperCase(Locale.ROOT);

    if ("COORD".equals(upperGeom)) {
      return dimension == 3 ? modelName + ".Coord3" : modelName + ".Coord2";
    }
    if ("MULTIPOINT".equals(upperGeom)) {
      return dimension == 3 ? modelName + ".MultiPoint3D" : modelName + ".MultiPoint";
    }

    if ("2.3".equals(iliVersion)) {
      if ("AREA".equals(upperGeom) && overlapMeters.compareTo(new BigDecimal("0.002")) == 0) {
        return modelName + ".AreaWithOverlaps2mm";
      }
      if ("SURFACE".equals(upperGeom) && overlapMeters.compareTo(new BigDecimal("0.002")) == 0) {
        return modelName + ".SurfaceWithOverlaps2mm";
      }
      return switch (upperGeom) {
        case "SURFACE" -> modelName + ".Surface";
        case "AREA" -> modelName + ".Area";
        case "LINE" -> modelName + ".Line";
        case "DIRECTEDLINE" -> modelName + ".DirectedLine";
        case "LINEWITHALTITUDE" -> modelName + ".LineWithAltitude";
        case "DIRECTEDLINEWITHALTITUDE" -> modelName + ".DirectedLineWithAltitude";
        default -> throw new IllegalArgumentException("Geometrietyp nicht unterstützt für CHBase 2.3: " + geom);
      };
    }

    boolean arcsAllowed = useArcs;
    return switch (upperGeom) {
      case "SURFACE" -> arcsAllowed ? modelName + ".MultiSurface" : modelName + ".MultiSurfaceWithoutArcs";
      case "AREA" -> arcsAllowed ? modelName + ".Area" : modelName + ".AreaWithoutArcs";
      case "LINE", "POLYLINE" -> selectLine(modelName, dimension, useArcs, directed);
      case "MULTIPOLYLINE", "MULTILINE" -> selectMultiLine(modelName, useArcs, directed);
      case "MULTISURFACE" -> arcsAllowed ? modelName + ".MultiSurface" : modelName + ".MultiSurfaceWithoutArcs";
      case "MULTIAREA" -> arcsAllowed ? modelName + ".Area" : modelName + ".AreaWithoutArcs";
      case "MULTIDIRECTEDLINE" -> selectMultiLine(modelName, useArcs, true);
      default -> throw new IllegalArgumentException("Geometrietyp nicht unterstützt: " + geom);
    };
  }

  private String selectLine(String modelName, int dimension, boolean useArcs, boolean directed) {
    String suffix;
    if (dimension == 3) {
      suffix = useArcs ? "LineWithAltitude" : "LineWithAltitudeWithoutArcs";
      if (directed) {
        suffix = useArcs ? "DirectedLineWithAltitude" : "DirectedLineWithAltitudeWithoutArcs";
      }
    } else {
      suffix = useArcs ? "Line" : "LineWithoutArcs";
      if (directed) {
        suffix = useArcs ? "DirectedLine" : "DirectedLineWithoutArcs";
      }
    }
    return modelName + "." + suffix;
  }

  private String selectMultiLine(String modelName, boolean useArcs, boolean directed) {
    String suffix = useArcs ? (directed ? "MultiDirectedLine" : "MultiLine")
        : (directed ? "MultiDirectedLineWithoutArcs" : "MultiLineWithoutArcs");
    return modelName + "." + suffix;
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
      String upper = geom.toUpperCase(Locale.ROOT);
      return upper.contains("LINE");
    }
    return geom.equals("POLYLINE") || geom.equals("MULTIPOLYLINE") || geom.equals("LINE") || geom.equals("MULTILINE");
  }

  private Set<String> chBaseGeometryTypes(String ili) {
    Map<String, String> raw = "2.3".equals(ili) ? chBase23() : chBase24();
    return new LinkedHashSet<>(raw.values());
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
    map.put("COORD", "Coord");
    map.put("COORD2", "Coord");
    map.put("COORD3", "Coord");
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
