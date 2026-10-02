package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.util.AnnotationRenderer;
import ch.so.agi.mcp.util.NameValidator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ConstraintTools {

  public Map<String,Object> unique(
      @McpToolParam(description = "Attribute (z. B. ['bezeich','lage'])", required = true) List<String> attrs,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der Constraint", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der Constraint", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    if (attrs == null || attrs.isEmpty()) {
      throw new IllegalArgumentException("UNIQUE requires at least one key path.");
    }
    var validator = NameValidator.ascii();
    for (String attr : attrs) {
      if (attr == null || attr.isBlank()) {
        throw new IllegalArgumentException("UNIQUE key paths must not be blank.");
      }
      validator.validateFqn(attr.trim(), "UNIQUE key path");
    }
    String inner = attrs.stream().map(String::trim).collect(Collectors.joining(", "));
    return constraintSnippet("UNIQUE " + inner + ";", iliDoc, metaAttributes);
  }

  private Map<String, Object> constraintSnippet(
      String statement,
      @Nullable String iliDoc,
      @Nullable List<MetaAttributeSpec> metaAttributes) {
    StringBuilder sb = new StringBuilder();
    String annotations = AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes);
    if (!annotations.isEmpty()) {
      sb.append(annotations);
    }
    sb.append(statement);
    return Map.of("iliSnippet", sb.toString());
  }
}
