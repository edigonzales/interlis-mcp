package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.constraint.ConstraintAuthoringEngine;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.model.IliConstraintSpec;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public final class UniqueConstraintAuthoringTools {
  private final ConstraintAuthoringEngine engine;

  public UniqueConstraintAuthoringTools(ConstraintAuthoringEngine engine) {
    this.engine = engine;
  }

  @McpTool(
      name = "authorIliUniqueConstraint",
      description = "Erzeugt einen typisierten UNIQUE Constraint mit GLOBAL-, BASKET- oder LOCAL-Scope, mehreren Schlüsselpfaden, optionalem WHERE und explizitem LOCAL-Präfix. Proof, AST-Roundtrip, Diff und afterReview verwenden genau einen Before- und einen After-Compile; danach ist kein reviewIliChange erforderlich. Das Tool schreibt keine Datei.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
  public IliAuthoringResult authorIliUniqueConstraint(
      @McpToolParam(description = "Vollständiger INTERLIS-Modelltext vor der Ergänzung", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Constraint-Kontext", required = true) String contextFqn,
      @McpToolParam(description = "Typisierte UNIQUE-Spezifikation", required = true) IliConstraintSpec.Unique spec,
      @McpToolParam(description = "Modellzweck", required = false) @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Regelprofil CORE oder SO", required = false) @Nullable ModelingRuleProfile ruleProfile) {
    return engine.author(modelText, contextFqn, spec, modelPurpose, ruleProfile);
  }
}
