package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.util.AnnotationRenderer;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ConstraintTools {

  @McpTool(name = "createUniqueConstraint",
        description = "Low-Level-Snippet-Helper fuer einen einfachen UNIQUE-Attributschluessel. Er erzeugt nur einen CONSTRAINTS-Block und bietet weder source-preserving Einfuegung noch semantischen Validator-Proof. Fuer UNIQUE gibt es noch kein typed High-Level-Authoring; nach der gezielten Integration in ein bestehendes Modell den resultierenden Constraint mit generateIliConstraintCases beweisen und die Modellaenderung mit reviewIliChange abschliessen. WHERE/(BASKET)/LOCAL nicht aus diesem einfachen attrs-Schema ableiten.")
  public Map<String,Object> unique(
      @McpToolParam(description = "Attribute (z. B. ['bezeich','lage'])", required = true) List<String> attrs,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der Constraint", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der Constraint", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    String inner = attrs.stream().map(String::trim).collect(Collectors.joining(", "));
    return constraintSnippet("  UNIQUE " + inner + ";", iliDoc, metaAttributes);
  }

  private Map<String, Object> constraintSnippet(
      String statement,
      @Nullable String iliDoc,
      @Nullable List<MetaAttributeSpec> metaAttributes) {
    StringBuilder sb = new StringBuilder("CONSTRAINTS\n");
    String annotations = AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes);
    if (!annotations.isEmpty()) {
      sb.append(AnnotationRenderer.indentBlock(annotations.stripTrailing(), "  ")).append("\n");
    }
    sb.append(statement);
    return Map.of("iliSnippet", sb.toString());
  }
}
