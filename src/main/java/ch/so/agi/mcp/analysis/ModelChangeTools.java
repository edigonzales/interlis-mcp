package ch.so.agi.mcp.analysis;

import ch.so.agi.mcp.model.ModelHashes;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ModelChangeTools {

  private final IliCompilerService compilerService;
  private final ModelChangeReviewService reviewService;

  @Autowired
  public ModelChangeTools(
      IliCompilerService compilerService,
      ModelChangeReviewService reviewService) {
    this.compilerService = compilerService;
    this.reviewService = reviewService;
  }

  public ModelChangeTools(
      IliCompilerService compilerService,
      ModelAnalysisTools analysisTools,
      ModelingRuleTools ruleTools) {
    this(compilerService, new ModelChangeReviewService(analysisTools, ruleTools));
  }

  @McpTool(
      name = "reviewIliChange",
      description = "Vergleicht zwei Modellstände semantisch und prüft den Nachher-Stand. Meldet potenziell brechende Änderungen.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true)
  )
  public Map<String, Object> reviewIliChange(
      @McpToolParam(description = "INTERLIS-2 Modelltext vor der Aenderung", required = true) String beforeModelText,
      @McpToolParam(description = "INTERLIS-2 Modelltext nach der Aenderung", required = true) String afterModelText,
      @McpToolParam(description = "Modellzweck", required = false) @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Regelprofil, Standard CORE", required = false) @Nullable ModelingRuleProfile ruleProfile
  ) {
    return ModelHashes.attach(reviewIliChangeFull(beforeModelText, afterModelText, modelPurpose, ruleProfile), ModelHashes.change(beforeModelText, afterModelText));
  }

  private Map<String, Object> reviewIliChangeFull(String beforeModelText, String afterModelText, @Nullable ModelPurpose modelPurpose, @Nullable ModelingRuleProfile ruleProfile) {
    ModelPurpose purpose = ModelPurpose.normalize(modelPurpose);
    ModelingRuleProfile profile = ModelingRuleProfile.normalize(ruleProfile);

    IliCompilerService.CompilationResult beforeCompilation =
        compilerService.compile(beforeModelText, null, "ili2c_change_before_");
    IliCompilerService.CompilationResult afterCompilation =
        compilerService.compile(afterModelText, null, "ili2c_change_after_");

    return reviewService.reviewCompiledChange(
        beforeCompilation,
        afterCompilation,
        beforeModelText,
        afterModelText,
        purpose,
        profile);
  }
}
