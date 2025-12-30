package ch.so.agi.mcp.tools;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class StructureTools {

  @McpTool(
      name = "createStructureSnippet",
      description = "Erzeugt eine STRUCTURE-Definition (keine OID/TID). Params: name (required), isAbstract?, extendsFqn?, attrLines?"
  )
  public Map<String, Object> createStructure(
      @McpToolParam(description = "Strukturname", required = true) String name,
      @McpToolParam(description = "Abstrakt?") @Nullable Boolean isAbstract,
      @McpToolParam(description = "EXTENDS (vollqualifiziert)") @Nullable String extendsFqn,
      @McpToolParam(description = "Attribut-Zeilen (roher ILI-Text)") @Nullable List<String> attrLines
  ) {
    boolean abs = isAbstract != null && isAbstract;
    String header = "STRUCTURE " + name
        + (abs ? " (ABSTRACT)" : "")
        + (extendsFqn != null && !extendsFqn.isBlank() ? " EXTENDS " + extendsFqn.trim() : "")
        + " =";

    StringBuilder sb = new StringBuilder();
    sb.append(header).append("\n");

    if (attrLines != null && !attrLines.isEmpty()) {
      for (String l : attrLines) {
        sb.append("  ").append(l).append("\n");
      }
    } else {
      sb.append("  !! Attribute hier\n");
    }

    sb.append("END ").append(name).append(";");
    return Map.of("iliSnippet", sb.toString(), "cursorHint", Map.of("line", 1, "col", 2));
  }
}
