package ch.so.agi.mcp.tools;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
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
}
