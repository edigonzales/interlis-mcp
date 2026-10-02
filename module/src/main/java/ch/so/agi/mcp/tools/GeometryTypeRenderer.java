package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.GeometryTypeSpec;
import ch.so.agi.mcp.util.NameValidator;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Validates and renders geometry types without inventing geometry semantics. */
@Component
public final class GeometryTypeRenderer {

  public RenderedGeometry render(GeometryTypeSpec spec, String iliVersion) {
    if (spec == null || spec.getProvider() == null) {
      throw new IllegalArgumentException("geometryType.provider is required.");
    }
    String ili = requireIliVersion(iliVersion);
    return switch (spec.getProvider()) {
      case INTERLIS -> renderInterlis(spec, ili);
      case CHBASE -> renderChBase(spec, ili);
    };
  }

  private RenderedGeometry renderInterlis(GeometryTypeSpec spec, String iliVersion) {
    if (spec.getInterlisKind() == null) {
      throw new IllegalArgumentException("geometryType.interlisKind is required for INTERLIS geometry.");
    }
    if ("2.3".equals(iliVersion)
        && switch (spec.getInterlisKind()) {
          case MULTIPOLYLINE, MULTISURFACE, MULTIAREA -> true;
          default -> false;
        }) {
      throw new IllegalArgumentException(
          spec.getInterlisKind() + " requires INTERLIS 2.4.");
    }
    if (spec.getCoordDomainFqn() == null || spec.getCoordDomainFqn().isBlank()) {
      throw new IllegalArgumentException(
          "geometryType.coordDomainFqn is required for INTERLIS geometry.");
    }
    if (spec.getArcs() == null || spec.getOverlapMm() == null) {
      throw new IllegalArgumentException(
          "INTERLIS geometry requires explicit arcs and overlapMm values.");
    }
    if (spec.getOverlapMm().compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException("geometryType.overlapMm must be >= 0.");
    }
    boolean line = switch (spec.getInterlisKind()) {
      case POLYLINE, MULTIPOLYLINE -> true;
      default -> false;
    };
    if (line && spec.getDirected() == null) {
      throw new IllegalArgumentException(
          "POLYLINE/MULTIPOLYLINE requires an explicit directed value.");
    }
    if (!line && spec.getDirected() != null) {
      throw new IllegalArgumentException(
          "geometryType.directed is not applicable to surface/area geometry.");
    }
    String coord = spec.getCoordDomainFqn().trim();
    NameValidator.ascii().validateFqn(coord, "geometryType.coordDomainFqn");
    String lineForm = Boolean.TRUE.equals(spec.getArcs())
        ? "WITH (STRAIGHTS, ARCS)"
        : "WITH (STRAIGHTS)";
    String overlap = spec.getOverlapMm().movePointLeft(3).stripTrailingZeros().toPlainString();
    String rendered = (Boolean.TRUE.equals(spec.getDirected()) ? "DIRECTED " : "")
        + spec.getInterlisKind().name() + " " + lineForm
        + "\n    VERTEX " + coord
        + "\n    WITHOUT OVERLAPS > " + overlap;
    return new RenderedGeometry(rendered, List.of());
  }

  private RenderedGeometry renderChBase(GeometryTypeSpec spec, String iliVersion) {
    if (spec.getChBaseType() == null || spec.getChBaseType().isBlank()) {
      throw new IllegalArgumentException("geometryType.chBaseType is required for CHBASE geometry.");
    }
    if (spec.getInterlisKind() != null
        || spec.getCoordDomainFqn() != null
        || spec.getArcs() != null
        || spec.getOverlapMm() != null
        || spec.getDirected() != null) {
      throw new IllegalArgumentException(
          "CHBASE geometry must not define interlisKind, coordDomainFqn, arcs, overlapMm or directed.");
    }
    String model = "2.3".equals(iliVersion) ? "GeometryCHLV95_V1" : "GeometryCHLV95_V2";
    String supplied = spec.getChBaseType().trim();
    String local = supplied.contains(".")
        ? supplied.substring(supplied.lastIndexOf('.') + 1)
        : supplied;
    if (supplied.contains(".") && !supplied.startsWith(model + ".")) {
      throw new IllegalArgumentException("CHBASE geometry must belong to " + model + ".");
    }
    String canonical = chBaseTypes(iliVersion).get(local.toUpperCase(Locale.ROOT));
    if (canonical == null) {
      throw new IllegalArgumentException(
          "Unsupported CHBASE geometry type '" + local + "' for INTERLIS " + iliVersion + ".");
    }
    return new RenderedGeometry(model + "." + canonical, List.of(model));
  }

  private Map<String, String> chBaseTypes(String iliVersion) {
    Map<String, String> result = new LinkedHashMap<>();
    if ("2.4".equals(iliVersion)) {
      result.put("COORD2", "Coord2");
      result.put("COORD3", "Coord3");
      result.put("MULTIPOINT", "MultiPoint");
      result.put("MULTIPOINT3D", "MultiPoint3D");
      result.put("MULTILINE", "MultiLine");
      result.put("MULTIDIRECTEDLINE", "MultiDirectedLine");
      result.put("SURFACEWITHOUTARCS", "SurfaceWithoutArcs");
      result.put("AREAWITHOUTARCS", "AreaWithoutArcs");
      result.put("LINEWITHOUTARCS", "LineWithoutArcs");
      result.put("DIRECTEDLINEWITHOUTARCS", "DirectedLineWithoutArcs");
      result.put("LINEWITHALTITUDEWITHOUTARCS", "LineWithAltitudeWithoutArcs");
      result.put("DIRECTEDLINEWITHALTITUDEWITHOUTARCS", "DirectedLineWithAltitudeWithoutArcs");
      result.put("MULTILINEWITHOUTARCS", "MultiLineWithoutArcs");
      result.put("MULTIDIRECTEDLINEWITHOUTARCS", "MultiDirectedLineWithoutArcs");
      result.put("MULTISURFACE", "MultiSurface");
      result.put("MULTISURFACEWITHOUTARCS", "MultiSurfaceWithoutArcs");
    }
    result.put("SURFACE", "Surface");
    result.put("AREA", "Area");
    result.put("LINE", "Line");
    result.put("DIRECTEDLINE", "DirectedLine");
    result.put("LINEWITHALTITUDE", "LineWithAltitude");
    result.put("DIRECTEDLINEWITHALTITUDE", "DirectedLineWithAltitude");
    result.put("SURFACEWITHOVERLAPS2MM", "SurfaceWithOverlaps2mm");
    result.put("AREAWITHOVERLAPS2MM", "AreaWithOverlaps2mm");
    return result;
  }

  public List<String> supportedChBaseTypes(String iliVersion) {
    return List.copyOf(new java.util.LinkedHashSet<>(
        chBaseTypes(requireIliVersion(iliVersion)).values()));
  }

  private String requireIliVersion(String iliVersion) {
    if (!"2.3".equals(iliVersion) && !"2.4".equals(iliVersion)) {
      throw new IllegalArgumentException("iliVersion must be '2.3' or '2.4'.");
    }
    return iliVersion;
  }

  public record RenderedGeometry(String typeText, List<String> requiredImports) {}
}
