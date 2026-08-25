package ch.so.agi.mcp.change;

import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.model.IliAuthoringResult;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class IliModelChangeTools {

  private final IliModelChangesService changesService;

  public IliModelChangeTools(IliModelChangesService changesService) {
    this.changesService = changesService;
  }

  @McpTool(
      name = "applyIliModelChanges",
      description = "Wendet einen typisierten Batch atomar und source-preserving auf ein vollständiges INTERLIS-Modell an. Unterstützt ADD_IMPORT, ADD_TOPIC, ADD_DOMAIN, ADD_UNIT, ADD_CLASS, ADD_STRUCTURE, ADD_ASSOCIATION, ADD_ATTRIBUTE, UPDATE_ATTRIBUTE, REMOVE_ATTRIBUTE und ADD_CONSTRAINT. Der gesamte Batch nutzt genau einen Before- und einen After-Compile; Diff und afterReview werden daraus wiederverwendet. Das Breaking-Change-Gate liefert bei potenziell brechenden Änderungen ohne explizites allowPotentiallyBreaking=true nur einen geprüften candidateModelText. Das Tool schreibt keine Datei.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(
          readOnlyHint = true,
          destructiveHint = false,
          idempotentHint = true,
          openWorldHint = true))
  public IliAuthoringResult applyIliModelChanges(
      @McpToolParam(description = "Vollständiger INTERLIS-2 Modelltext vor dem Batch", required = true)
      String modelText,
      @McpToolParam(description = "Typisierter atomarer Batch und Breaking-Change-Freigabe", required = true)
      IliModelChangesRequest request,
      @McpToolParam(description = "Modellzweck: CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false)
      @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Regelprofil: CORE oder SO (Default CORE)", required = false)
      @Nullable ModelingRuleProfile ruleProfile) {
    return changesService.apply(modelText, request, null, modelPurpose, ruleProfile);
  }
}
