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
public final class ExistenceConstraintAuthoringTools {
  private final ConstraintAuthoringEngine engine;

  public ExistenceConstraintAuthoringTools(ConstraintAuthoringEngine engine) {
    this.engine = engine;
  }

  @McpTool(
      name = "authorIliExistenceConstraint",
      description = "Erzeugt einen typisierten EXISTENCE CONSTRAINT mit explizitem restrictedPath und vollständigen REQUIRED-IN-Zielen aus viewableFqn und attributePath. Die Einfügung ist source-preserving; Before/After je genau einmal kompiliert; AST->constraint-level-IR sowie NUMERIC-, TEXT-, Struktur-, Referenz- und Geometrie-Fixtures werden mit ilivalidator geprüft. Nur ein vollständig bestätigter Proof wird freigegeben. Das Tool schreibt keine Datei.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
  public IliAuthoringResult authorIliExistenceConstraint(
      @McpToolParam(description = "Vollständiger INTERLIS-Modelltext vor der Ergänzung", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Constraint-Kontext", required = true) String contextFqn,
      @McpToolParam(description = "Typisierte EXISTENCE-Spezifikation", required = true) IliConstraintSpec.Existence spec,
      @McpToolParam(description = "Modellzweck", required = false) @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Regelprofil CORE oder SO", required = false) @Nullable ModelingRuleProfile ruleProfile) {
    return engine.author(modelText, contextFqn, spec, modelPurpose, ruleProfile);
  }
}
