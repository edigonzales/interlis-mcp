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
        description = "UNIQUE-Constraint: Params: attrs (required list), iliDoc, metaAttributes. Returns a CONSTRAINTS block (append inside class).")
  public Map<String,Object> unique(
      @McpToolParam(description = "Attribute (z. B. ['bezeich','lage'])", required = true) List<String> attrs,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der Constraint", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der Constraint", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    String inner = attrs.stream().map(String::trim).collect(Collectors.joining(", "));
    return constraintSnippet("  UNIQUE (" + inner + ");", iliDoc, metaAttributes);
  }

  @McpTool(name = "createMandatoryConstraint",
        description = "MANDATORY CONSTRAINT: Params: expr (required), iliDoc, metaAttributes.")
  public Map<String,Object> mandatory(
      @McpToolParam(description = "boolescher Ausdruck", required = true) String expr,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der Constraint", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der Constraint", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    return constraintSnippet("  MANDATORY CONSTRAINT " + expr.trim() + ";", iliDoc, metaAttributes);
  }

  @McpTool(name = "createSetConstraint",
        description = "SET CONSTRAINT: Params: expr (required), iliDoc, metaAttributes.")
  public Map<String,Object> setConstraint(
      @McpToolParam(description = "Mengen-Ausdruck", required = true) String expr,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der Constraint", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der Constraint", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    return constraintSnippet("  SET CONSTRAINT\n    " + expr.trim() + ";", iliDoc, metaAttributes);
  }

  @McpTool(name = "createPresentIfConstraint",
        description = "PRESENT ... IF ...: Params: attr (required), cond (required), iliDoc, metaAttributes.")
  public Map<String,Object> presentIf(
      @McpToolParam(description = "Attribut", required = true) String attr,
      @McpToolParam(description = "Bedingung", required = true) String cond,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der Constraint", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der Constraint", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    return constraintSnippet("  PRESENT " + attr.trim() + " IF " + cond.trim() + ";", iliDoc, metaAttributes);
  }

  @McpTool(name = "createValueRangeConstraint",
        description = "VALUE ... IN ...: Params: attr (required), range (required), iliDoc, metaAttributes.")
  public Map<String,Object> valueIn(
      @McpToolParam(description = "Attribut", required = true) String attr,
      @McpToolParam(description = "Range, z. B. '0.0 .. 4000.0'", required = true) String range,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der Constraint", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der Constraint", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    return constraintSnippet("  VALUE " + attr.trim() + " IN " + range.trim() + ";", iliDoc, metaAttributes);
  }

  @McpTool(name = "createExistenceConstraint",
        description = "EXISTENCE CONSTRAINT ... REQUIRED IN ... : Params: refAttr (required), classFQNs (required list), iliDoc, metaAttributes.")
  public Map<String,Object> existence(
      @McpToolParam(description = "Referenzattribut", required = true) String refAttr,
      @McpToolParam(description = "Erlaubte Klassen (FQNs)", required = true) List<String> classFqns,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der Constraint", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der Constraint", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    String inner = classFqns.stream().map(String::trim).collect(Collectors.joining(", "));
    return constraintSnippet("  EXISTENCE CONSTRAINT " + refAttr.trim() + " REQUIRED IN " + inner + ";", iliDoc, metaAttributes);
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
