package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.constraint.ConstraintAuthoringEngine;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.model.IliConstraintSpec;
import ch.so.agi.mcp.model.ModelHashes;
import ch.so.agi.mcp.model.TestXtfOutput;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Adds a single typed constraint to an existing model. */
@Component
public final class ConstraintAuthoringTools {
  private final ConstraintAuthoringEngine engine;

  public ConstraintAuthoringTools(ConstraintAuthoringEngine engine) {
    this.engine = engine;
  }

  @McpTool(
      name = "authorIliConstraint",
      description = "Ergänzt und prüft einen Constraint im bestehenden Modell quelltexttreu. "
          + "spec.kind wählt MANDATORY, UNIQUE, EXISTENCE, PLAUSIBILITY oder SET. "
          + "Offene Nachweise oder externe Funktionssemantik halten den Kandidaten zurück.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
  public IliAuthoringResult authorIliConstraint(
      @McpToolParam(description = "Vollständiger Modelltext", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Constraint-Kontext", required = true) String contextFqn,
      @McpToolParam(description = "Constraint mit kind und technischem Namen, z.B. Regel42. COMPARE erhält zwei geordnete children. OBJECT_COUNT als Ausdruck ist ein Zahlenoperand.", required = true) IliConstraintSpec spec,
      @McpToolParam(description = "Modellzweck", required = false) @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Regelprofil", required = false) @Nullable ModelingRuleProfile ruleProfile,
      @McpToolParam(description = TestXtfOutput.PARAMETER, required = false) @Nullable Boolean includeSuccessfulTestXtf) {
    var result = engine.author(modelText, contextFqn, spec, modelPurpose, ruleProfile);
    return TestXtfOutput.prepare(ModelHashes.attach(result, modelText), includeSuccessfulTestXtf);
  }
}
