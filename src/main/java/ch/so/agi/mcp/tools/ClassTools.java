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

//  @Tool(name = "createAttributeLine",
//        description = "Erzeugt eine einzelne Attribut-Zeile. Params: name (required), type (required), mandatory (default false), collection (NONE|LIST OF|BAG OF), domainFQN (optional).")
//  public Map<String,Object> createAttributeLine(
//      @ToolParam(description = "Attributname", required = true) String name,
//      @ToolParam(description = "Typ oder Domain-FQN", required = true) String type,
//      @ToolParam(description = "MANDATORY?") @Nullable Boolean mandatory,
//      @ToolParam(description = "Sammlungstyp: NONE|LIST OF|BAG OF") @Nullable String collection,
//      @ToolParam(description = "Alternative Domain-FQN (überschreibt 'type')") @Nullable String domainFqn
//  ) {
//
//    String col = (collection != null && !collection.isBlank() && !collection.equalsIgnoreCase("NONE"))
//        ? collection.trim() + " "
//        : "";
//    String base = (domainFqn != null && !domainFqn.isBlank()) ? domainFqn.trim() : type.trim();
//    String mand = (mandatory != null && mandatory) ? "MANDATORY " : "";
//    String line = name + " : " + mand + col + base + ";";
//    return Map.of("iliSnippet", line);
//  }
}
