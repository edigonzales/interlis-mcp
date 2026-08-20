package ch.so.agi.mcp.analysis;

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
      description = "Standard-Tool, wenn ein Vorher- und ein Nachher-Stand eines vollstaendigen INTERLIS-Modells vorliegen. Vergleicht beide semantisch, kompiliert jede Version genau einmal und prueft das After-Modell gegen die Modellierungsregeln. Rueckgabe: added, removed, changed, potentiallyBreakingChanges und afterReview. Nicht fuer einen einzelnen Modellstand verwenden; dafuer reviewIliModel."
  )
  public Map<String, Object> reviewIliChange(
      @McpToolParam(description = "INTERLIS-2 Modelltext vor der Aenderung", required = true) String beforeModelText,
      @McpToolParam(description = "INTERLIS-2 Modelltext nach der Aenderung", required = true) String afterModelText,
      @McpToolParam(description = "Modellzweck fuer das Review des After-Modells: CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false) @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Regelprofil fuer das Review des After-Modells: CORE oder SO (Default CORE)", required = false) @Nullable ModelingRuleProfile ruleProfile
  ) {
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
