package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.util.AnnotationRenderer;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class StructureTools {

  @McpTool(
      name = "createStructureSnippet",
      description = "Erzeugt eine STRUCTURE-Definition (keine OID/TID). Params: name (required), isAbstract?, extendsFqn?, attrLines?, iliDoc, metaAttributes."
  )
  public Map<String, Object> createStructure(
      @McpToolParam(description = "Strukturname", required = true) String name,
      @McpToolParam(description = "Abstrakt?", required = false) @Nullable Boolean isAbstract,
      @McpToolParam(description = "EXTENDS (vollqualifiziert)", required = false) @Nullable String extendsFqn,
      @McpToolParam(description = "Attribut-Zeilen (roher ILI-Text)", required = false) @Nullable List<String> attrLines,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der STRUCTURE", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der STRUCTURE", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    boolean abs = isAbstract != null && isAbstract;
    String header = "STRUCTURE " + name
        + (abs ? " (ABSTRACT)" : "")
        + (extendsFqn != null && !extendsFqn.isBlank() ? " EXTENDS " + extendsFqn.trim() : "")
        + " =";

    StringBuilder sb = new StringBuilder();
    sb.append(AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes));
    sb.append(header).append("\n");

    if (attrLines != null && !attrLines.isEmpty()) {
      for (String l : attrLines) {
        sb.append(AnnotationRenderer.indentBlock(l, "  ")).append("\n");
      }
    } else {
      sb.append("  /** Attribute hier */\n");
    }

    sb.append("END ").append(name).append(";");
    return Map.of("iliSnippet", sb.toString());
  }
}
