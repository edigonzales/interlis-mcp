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

/** Typed MCP entry points for expression-based constraint kinds. */
@Component
public final class ConstraintAuthoringTools {
  private final ConstraintAuthoringEngine engine;

  public ConstraintAuthoringTools(ConstraintAuthoringEngine engine) {
    this.engine = engine;
  }

  @McpTool(
      name = "authorIliMandatoryConstraint",
      description = "Erzeugt einen Mandatory Constraint aus einer rekursiven typisierten semantischen Node-Liste (ATTRIBUTE, PATH, FUNCTION, COMPARE usw.). Die Einfügung ist source-preserving; Before und After kompiliert die Pipeline je genau einmal und beweist per AST->IR-Roundtrip, Coverage-Planer, Solver, Object-Graph-Synthese und ilivalidator. Diff und afterReview stammen aus denselben Compilations. Externe Funktionen ohne ausführbare Semantik halten den Kandidaten zurück. Das Tool schreibt keine Datei.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
  public IliAuthoringResult authorIliMandatoryConstraint(
      @McpToolParam(description = "Vollständiger INTERLIS-Modelltext vor der Ergänzung", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Constraint-Kontext", required = true) String contextFqn,
      @McpToolParam(description = "Typisierte MANDATORY-Spezifikation", required = true) IliConstraintSpec.Mandatory spec,
      @McpToolParam(description = "Modellzweck", required = false) @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Regelprofil CORE oder SO", required = false) @Nullable ModelingRuleProfile ruleProfile) {
    return engine.author(modelText, contextFqn, spec, modelPurpose, ruleProfile);
  }

  @McpTool(
      name = "authorIliPlausibilityConstraint",
      description = "Erzeugt einen PLAUSIBILITY Constraint mit direction, percentage und rekursiver typisierter semantischen Node-Liste. Die Einfügung ist source-preserving; Before/After je genau einmal kompiliert; der Proof verwendet constraint-level IR, echte Mehrfachobjekt-Populationen an der Prozentgrenze und ilivalidator. Diff und afterReview verwenden dieselben Compilations. Das Tool schreibt keine Datei.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
  public IliAuthoringResult authorIliPlausibilityConstraint(
      @McpToolParam(description = "Vollständiger INTERLIS-Modelltext vor der Ergänzung", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Constraint-Kontext", required = true) String contextFqn,
      @McpToolParam(description = "Typisierte PLAUSIBILITY-Spezifikation", required = true) IliConstraintSpec.Plausibility spec,
      @McpToolParam(description = "Modellzweck", required = false) @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Regelprofil CORE oder SO", required = false) @Nullable ModelingRuleProfile ruleProfile) {
    return engine.author(modelText, contextFqn, spec, modelPurpose, ruleProfile);
  }
}
