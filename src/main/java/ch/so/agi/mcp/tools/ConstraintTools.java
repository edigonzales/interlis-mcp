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

  @McpTool(name = "createMandatoryConstraint",
        description = "Legacy-Low-Level-Helper fuer einen freien MANDATORY-CONSTRAINT-Ausdruck. Er erzeugt nur ein Snippet und bietet weder typed Expression-IR, source-preserving Einfuegung noch automatischen Validator-Proof. Fuer neue agentische Regeln bevorzuge authorIliMandatoryConstraint; dieses Tool nur fuer bewusst freie Legacy-/Snippet-Faelle verwenden.")
  public Map<String,Object> mandatory(
      @McpToolParam(description = "boolescher Ausdruck", required = true) String expr,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der Constraint", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der Constraint", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    return constraintSnippet("  MANDATORY CONSTRAINT " + expr.trim() + ";", iliDoc, metaAttributes);
  }

  @McpTool(name = "createSetConstraint",
        description = "Legacy-Low-Level-Helper fuer einen freien SET-Ausdruck. Er erzeugt nur ein Snippet und bietet weder typisierte OBJECTS-OF/ALL-Semantik noch source-preserving Einfuegung oder Validator-Proof. Fuer neue objectCount(ALL)-SET-Regeln bevorzuge authorIliSetConstraint mit operator/threshold, optionalem where und perBasket.")
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
        description = "Legacy-Low-Level-Helper fuer EXISTENCE. Das alte Schema refAttr + classFQNs kann die echte REQUIRED-IN-Semantik ViewableRef : AttributePath nicht vollstaendig ausdruecken und soll fuer neue Agent-Workflows nicht verwendet werden. Bevorzuge authorIliExistenceConstraint mit explizitem restrictedPath sowie viewableFqn + attributePath je Target.")
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
