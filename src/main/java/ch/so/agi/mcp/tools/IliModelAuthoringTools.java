package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.ModelHashes;
import ch.so.agi.mcp.model.TestXtfOutput;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.model.IliAuthoringResult.Status;
import ch.so.agi.mcp.model.SpecValidationException;
import ch.so.agi.mcp.model.IliModelSpec;
import ch.so.agi.mcp.model.IliSpecRenderer;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** One-call authoring of a complete, reviewed and constraint-proven INTERLIS model. */
@Component
public final class IliModelAuthoringTools {

  private final IliSpecRenderer renderer;
  private final IliCompilerService compilerService;
  private final ConstraintContextService constraintContextService;
  private final ConstraintCaseGenerationTools caseGenerationTools;
  private final ModelChangeReviewService reviewService;

  public IliModelAuthoringTools(
      IliSpecRenderer renderer,
      IliCompilerService compilerService,
      ConstraintContextService constraintContextService,
      ConstraintCaseGenerationTools caseGenerationTools,
      ModelChangeReviewService reviewService) {
    this.renderer = renderer;
    this.compilerService = compilerService;
    this.constraintContextService = constraintContextService;
    this.caseGenerationTools = caseGenerationTools;
    this.reviewService = reviewService;
  }

  @McpTool(
      name = "authorIliModel",
      description = ch.so.agi.mcp.model.ConstraintAuthoringGuidance.SCALAR_INPUT + " " + ch.so.agi.mcp.knowledge.ConstraintWorkflowGuidance.TOOL + " Erzeugt aus einer vollständigen typisierten IliModelSpec ein vollständiges INTERLIS-2-Modell. Name, URI, Modellversion und INTERLIS-Version sind explizit erforderlich; fehlende Fachsemantik wird nicht erfunden. Unterstützt Units, Domains, Topics, Klassen, Strukturen, Assoziationen, Attribute, strikte Geometrien sowie UNIQUE, MANDATORY, EXISTENCE, PLAUSIBILITY und SET. Das fertige Modell wird genau einmal mit ili2c kompiliert; AST-Generierung, alle Constraint-Proofs und afterReview verwenden diesen kompilierten Kontext weiter. Das Tool schreibt keine Datei.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(
          readOnlyHint = true,
          destructiveHint = false,
          idempotentHint = true,
          openWorldHint = true))
  public IliAuthoringResult authorIliModel(
      @McpToolParam(description = "Vollständige typisierte Modellspezifikation", required = true)
      IliModelSpec spec,
      @McpToolParam(description = "Modellzweck: CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false)
      @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Regelprofil: CORE, SO-GIS oder SO-DATA (Default CORE)", required = false)
      @Nullable ModelingRuleProfile ruleProfile,
      @McpToolParam(description = TestXtfOutput.PARAMETER, required = false) @org.jspecify.annotations.Nullable Boolean includeSuccessfulTestXtf) {
    return TestXtfOutput.prepare(ModelHashes.attach(authorIliModelFull(spec, modelPurpose, ruleProfile), null), includeSuccessfulTestXtf);
  }

  public IliAuthoringResult authorIliModel(IliModelSpec spec, @Nullable ModelPurpose modelPurpose, @Nullable ModelingRuleProfile ruleProfile) {
    return authorIliModel(spec, modelPurpose, ruleProfile, null);
  }

  @McpTool(name = "verifyIliModel", description = "Prüft unveränderten Modelltext: Compiler, Modellierungsregeln und automatische Nachweise aller deklarierten Constraints. Keine Ableitung, Änderung oder fachliche Freigabe.", generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
  public IliAuthoringResult verifyIliModel(
      @McpToolParam(description = "Unveränderter vollständiger Modelltext", required = true) String modelText,
      @McpToolParam(description = "Modellzweck", required = false) @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Regelprofil", required = false) @Nullable ModelingRuleProfile ruleProfile) {
    var compilation = compilerService.compile(modelText, null, "ili2c_verify_model_");
    var result = new IliAuthoringResult();
    result.modelHashes = ModelHashes.model(modelText);
    result.afterCompilation = IliAuthoringResult.Compilation.from(compilation);
    if (!result.afterCompilation.valid()) {
      return result.reject(Status.CANDIDATE_MODEL_INVALID, "Model did not compile.", modelText);
    }
    if (reviewAndProve(result, modelText, compilation, modelPurpose, ruleProfile, Set.of())) {
      result.status = Status.VERIFIED;
    }
    return result;
  }

  private IliAuthoringResult authorIliModelFull(IliModelSpec spec, @Nullable ModelPurpose modelPurpose, @Nullable ModelingRuleProfile ruleProfile) {
    IliSpecRenderer.RenderedModel rendered;
    try {
      rendered = renderer.forProfile(ruleProfile).renderModel(spec);
    } catch (IllegalArgumentException ex) {
      return SpecValidationException.attach(
          new IliAuthoringResult().reject(Status.INVALID_SPEC, ex.getMessage(), null), ex);
    }

    IliCompilerService.CompilationResult compilation = compilerService.compile(
        rendered.modelText(), null, "ili2c_author_model_");
    var result = new IliAuthoringResult();
    result.afterCompilation = IliAuthoringResult.Compilation.from(compilation);
    result.derivedImports = rendered.derivedImports();
    if (!result.afterCompilation.valid()) {
      return result.reject(Status.CANDIDATE_MODEL_INVALID, "The typed model candidate did not compile.", rendered.modelText());
    }

    String astText;
    try {
      astText = compilerService.generateModelsFromLastFile(compilation.transferDescription());
      if (astText == null || astText.isBlank() || !astText.contains("MODEL " + spec.name.trim())) {
        return result.reject(Status.AST_ROUND_TRIP_FAILED,
            "ili2c could not regenerate the submitted model from its AST.", rendered.modelText());
      }
    } catch (RuntimeException ex) {
      return result.reject(Status.AST_ROUND_TRIP_FAILED, ex.getMessage(), rendered.modelText());
    }

    result.semanticDiff = new IliAuthoringResult.SemanticDiff();
    result.semanticDiff.kind = "NEW_MODEL";
    result.semanticDiff.modelName = spec.name.trim();
    result.details = Map.of("astGeneratedModelText", astText);
    Set<String> externalConstraints = new HashSet<>();
    if (spec.topics != null) spec.topics.forEach(topic -> topic.collectExternalConstraints(spec.name.trim(), externalConstraints));
    if (!reviewAndProve(result, rendered.modelText(), compilation, modelPurpose, ruleProfile, externalConstraints)) return result;
    return result.release(Status.GENERATED, rendered.modelText());
  }

  private boolean reviewAndProve(IliAuthoringResult result, String modelText,
      IliCompilerService.CompilationResult compilation, @Nullable ModelPurpose purpose,
      @Nullable ModelingRuleProfile profile, Set<String> externalConstraints) {
    var constraints = ConstraintContextService.declaredConstraintNames(compilation.transferDescription());
    var review = reviewService.reviewCompiledModel(compilation, modelText,
        ModelPurpose.normalize(purpose), ModelingRuleProfile.normalize(profile));
    result.attachReview(review, true);
    if (new HashSet<>(constraints).size() != constraints.size()) {
      result.reasonCode = "DUPLICATE_CONSTRAINT_NAME";
      result.reject(Status.INVALID_SPEC, "Declared constraint names must be unique.", modelText);
      return false;
    }
    var proofs = constraints.stream().map(fqn -> caseGenerationTools.prove(fqn,
        constraintContextService.resolveCompiled(modelText, fqn, null, compilation), externalConstraints.contains(fqn))).toList();
    if (result.rejectFailedProof(proofs, modelText)) return false;
    if (!result.openQuestions.isEmpty()) {
      result.reasonCode = "MODEL_PURPOSE_REQUIRED";
      result.reject(Status.NEEDS_INPUT, "The model review requires an explicit model purpose.", modelText);
      return false;
    }
    if (!Boolean.TRUE.equals(result.afterReview.validForAutomatedRules)) {
      result.reasonCode = "MODELING_RULE_ERRORS";
      result.reject(Status.NEEDS_INPUT, "The model review has blocking automated rule findings.", modelText);
      return false;
    }
    return true;
  }
}
