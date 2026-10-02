package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.util.AnnotationRenderer;
import ch.so.agi.mcp.util.NameValidator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class StructureTools {

  public Map<String, Object> createStructure(
      @McpToolParam(description = "Strukturname", required = true) String name,
      @McpToolParam(description = "Abstrakt?", required = false) @Nullable Boolean isAbstract,
      @McpToolParam(description = "EXTENDS (vollqualifiziert)", required = false) @Nullable String extendsFqn,
      @McpToolParam(description = "Attribut-Zeilen (roher ILI-Text)", required = false) @Nullable List<String> attrLines,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der STRUCTURE", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der STRUCTURE", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Structure name is required.");
    }
    String structureName = name.trim();
    var validator = NameValidator.ascii();
    validator.validateIdent(structureName, "Structure name");
    if (extendsFqn != null && !extendsFqn.isBlank()) {
      validator.validateFqn(extendsFqn.trim(), "Structure EXTENDS");
    }
    boolean abs = isAbstract != null && isAbstract;
    String header = "STRUCTURE " + structureName
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
    }

    sb.append("END ").append(structureName).append(";");
    return Map.of("iliSnippet", sb.toString());
  }
}
