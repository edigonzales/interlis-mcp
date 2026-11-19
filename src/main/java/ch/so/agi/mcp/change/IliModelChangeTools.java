package ch.so.agi.mcp.change;

import ch.so.agi.mcp.model.ModelHashes;
import ch.so.agi.mcp.model.TestXtfOutput;
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
      description = "Wendet einen typisierten Batch atomar und quelltexttreu an. Potenziell brechende Änderungen benötigen ausdrückliche Freigabe. Neue Constraints werden automatisch geprüft.",
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
      @McpToolParam(description = "Modellzweck", required = false)
      @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Regelprofil, Standard CORE", required = false)
      @Nullable ModelingRuleProfile ruleProfile,
      @McpToolParam(description = TestXtfOutput.PARAMETER, required = false) @org.jspecify.annotations.Nullable Boolean includeSuccessfulTestXtf) {
    return TestXtfOutput.prepare(ModelHashes.attach(changesService.apply(modelText, request, null, modelPurpose, ruleProfile), modelText), includeSuccessfulTestXtf);
  }

  public IliAuthoringResult applyIliModelChanges(String modelText, IliModelChangesRequest request, @Nullable ModelPurpose modelPurpose, @Nullable ModelingRuleProfile ruleProfile) {
    return applyIliModelChanges(modelText, request, modelPurpose, ruleProfile, null);
  }

}
