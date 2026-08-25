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
public final class SetConstraintAuthoringTools {
  private final ConstraintAuthoringEngine engine;

  public SetConstraintAuthoringTools(ConstraintAuthoringEngine engine) {
    this.engine = engine;
  }

  @McpTool(
      name = "authorIliSetConstraint",
      description = "Erzeugt einen typisierten SET CONSTRAINT mit perBasket/global-vs-Basket-Scope, optionalem where und diskriminierter OBJECT_COUNT- oder BOOLEAN_EXPRESSION-Condition; OBJECT_COUNT unterstützt objectCount(ALL) und navigierte Objektmengen. Die Einfügung ist source-preserving; Before/After je genau einmal kompiliert; constraint-level SET-IR und ilivalidator übernehmen den Proof. Geometry-aware Funktionen werden nur bei implementierter Semantik freigegeben. Diff und afterReview verwenden dieselben Compilations. Das Tool schreibt keine Datei.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
  public IliAuthoringResult authorIliSetConstraint(
      @McpToolParam(description = "Vollständiger INTERLIS-Modelltext vor der Ergänzung", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Constraint-Kontext", required = true) String contextFqn,
      @McpToolParam(description = "Typisierte SET-Spezifikation", required = true) IliConstraintSpec.Set spec,
      @McpToolParam(description = "Modellzweck", required = false) @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Regelprofil CORE oder SO", required = false) @Nullable ModelingRuleProfile ruleProfile) {
    return engine.author(modelText, contextFqn, spec, modelPurpose, ruleProfile);
  }
}
