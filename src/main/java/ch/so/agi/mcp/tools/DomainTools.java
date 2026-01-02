package ch.so.agi.mcp.tools;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DomainTools {

  @McpTool(name = "createEnumDomainSnippet",
        description = "Erzeugt eine Aufzählungs-DOMAIN. Params: name (required), items (required: list of enum items).")
  public Map<String,Object> createEnumDomain(
      @McpToolParam(description = "Domain-Name", required = true) String name,
      @McpToolParam(description = "Enum-Items in Reihenfolge", required = true) List<String> items
  ) {
    String inner = items.stream().map(String::trim).collect(Collectors.joining(", "));
    String snippet = "DOMAIN\n  " + name + " = (" + inner + ");";
    return Map.of("iliSnippet", snippet, "cursorHint", Map.of("line", 1, "col", 2));
  }

  @McpTool(name = "createNumericDomainSnippet",
        description = "Erzeugt eine numerische DOMAIN. Params: name (required), min, max (required), unitFQN (optional).")
  public Map<String,Object> createNumericDomain(
      @McpToolParam(description = "Domain-Name", required = true) String name,
      @McpToolParam(description = "Minimum", required = true) String min,
      @McpToolParam(description = "Maximum", required = true) String max,
      @McpToolParam(description = "Einheits-FQN, z. B. 'INTERLIS.m'") @Nullable String unitFqn
  ) {
    String range = min.trim() + " .. " + max.trim();
    String unit = (unitFqn != null && !unitFqn.isBlank()) ? " [" + unitFqn.trim() + "]" : "";
    String snippet = "DOMAIN\n  " + name + " = " + range + unit + ";";
    return Map.of("iliSnippet", snippet, "cursorHint", Map.of("line", 1, "col", 2));
  }

  @McpTool(name = "createUnitSnippet",
        description = "Erzeugt eine UNIT-Definition. Params: name (required), kind (e.g. LENGTH), base (e.g. INTERLIS.m).")
  public Map<String,Object> createUnit(
      @McpToolParam(description = "Einheiten-Name", required = true) String name,
      @McpToolParam(description = "Einheitsart, z. B. LENGTH, AREA", required = true) String kind,
      @McpToolParam(description = "Basis-Einheit, z. B. INTERLIS.m", required = true) String base
  ) {
    String snippet = "UNIT\n  " + name + " = " + kind.trim() + " [" + base.trim() + "];";
    return Map.of("iliSnippet", snippet, "cursorHint", Map.of("line", 1, "col", 2));
  }

  @McpTool(name = "createCoordDomainSnippet",
        description = "Erzeugt eine COORD-DOMAIN (2D/3D). Params: name (required), dimension (optional, default 2 oder anhand Name=Coord3), decimals (optional, Default 3 = Millimeter).")
  public Map<String, Object> createCoordDomainSnippet(
      @McpToolParam(description = "Domain-Name", required = true) String name,
      @McpToolParam(description = "Koordinatendimension (2 oder 3)") @Nullable Integer dimension,
      @McpToolParam(description = "Nachkommastellen (Default 3 = Millimeter)") @Nullable Integer decimals
  ) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Domain name is required.");
    }

    int fractionDigits = decimals == null ? 3 : decimals;
    if (fractionDigits < 0 || fractionDigits > 9) {
      throw new IllegalArgumentException("decimals must be between 0 and 9.");
    }

    String trimmedName = name.trim();
    int coordDimension = dimension != null ? dimension : (trimmedName.endsWith("3") ? 3 : 2);
    if (coordDimension != 2 && coordDimension != 3) {
      throw new IllegalArgumentException("dimension must be 2 or 3.");
    }

    String snippet = buildCoordDomain(trimmedName, coordDimension, fractionDigits);
    return Map.of("iliSnippet", snippet, "cursorHint", Map.of("line", 1, "col", 2));
  }

  private String buildCoordDomain(String name, int dimension, int decimals) {
    StringBuilder sb = new StringBuilder();
    sb.append("DOMAIN\n  ").append(name).append(" = COORD\n");
    sb.append("    ")
        .append(formatValue(460000, decimals))
        .append(" .. ")
        .append(formatValue(870000, decimals))
        .append(" [INTERLIS.m],\n");
    sb.append("    ")
        .append(formatValue(45000, decimals))
        .append(" .. ")
        .append(formatValue(310000, decimals))
        .append(" [INTERLIS.m]");

    if (dimension == 3) {
      sb.append(",\n    ")
          .append(formatValue(-200, decimals))
          .append(" .. ")
          .append(formatValue(5000, decimals))
          .append(" [INTERLIS.m]\n");
    } else {
      sb.append(",\n");
    }

    sb.append("    ROTATION 2 -> 1;");
    return sb.toString();
  }

  private String formatValue(double value, int decimals) {
    String format = "%." + decimals + "f";
    return String.format(Locale.ROOT, format, value);
  }

}
