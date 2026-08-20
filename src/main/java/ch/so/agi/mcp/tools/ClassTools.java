package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.util.AnnotationRenderer;
import ch.so.agi.mcp.util.NameValidator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ClassTools {

  @McpTool(name = "createClassSnippet",
        description = "Erzeugt eine CLASS-Definition. Params: name (required), isAbstract, extendsFQN, oidDecl, attrLines (list of attribute lines), iliDoc, metaAttributes.")
  public Map<String,Object> createClass(
      @McpToolParam(description = "Klassenname", required = true) String name,
      @McpToolParam(description = "Abstrakt?", required = false) @Nullable Boolean isAbstract,
      @McpToolParam(description = "EXTENDS (vollqualifiziert)", required = false) @Nullable String extendsFqn,
      @McpToolParam(description = "OID-Definition, z. B. 'OID AS INTERLIS.UUIDOID'", required = false) @Nullable String oidDecl,
      @McpToolParam(description = "Attribut-Zeilen (roher ILI-Text)", required = false) @Nullable List<String> attrLines,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der CLASS", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der CLASS", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
      var nv = NameValidator.ascii(); 
      nv.validateIdent(name, "Class name");
      if (extendsFqn != null && !extendsFqn.isBlank()) {
          nv.validateFqn(extendsFqn, "EXTENDS FQN");
        }
      
    boolean abs = isAbstract != null && isAbstract;
    String header = "CLASS " + name + (abs ? " (ABSTRACT)" : "") +
        (extendsFqn != null && !extendsFqn.isBlank() ? " EXTENDS " + extendsFqn.trim() : "") + " =";
    StringBuilder sb = new StringBuilder();
    sb.append(AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes));
    sb.append(header).append("\n");
    if (oidDecl != null && !oidDecl.isBlank()) {
      sb.append("  ").append(oidDecl.trim()).append(";\n");
    }
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
