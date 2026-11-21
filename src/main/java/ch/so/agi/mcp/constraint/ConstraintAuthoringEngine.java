package ch.so.agi.mcp.constraint;

import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.model.IliAuthoringResult.Status;
import ch.so.agi.mcp.model.SpecValidationException;
import ch.so.agi.mcp.model.ConstraintSpecContract;
import ch.so.agi.mcp.model.IliConstraintSpec;
import ch.so.agi.mcp.model.IliSpecRenderer;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.ConstraintCaseGenerationTools;
import ch.so.agi.mcp.util.NameValidator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Authors all five constraint kinds using a before and after compilation. */
@Component
public final class ConstraintAuthoringEngine {
  private static final Pattern ILI_VERSION =
      Pattern.compile("(?m)^\\s*INTERLIS\\s+(2\\.3|2\\.4)\\s*;");

  private final ConstraintAuthoringWorkflow workflow;
  private final IliSpecRenderer renderer;
  private final ConstraintCaseGenerationTools caseGenerationTools;
  private final ModelChangeReviewService reviewService;

  public ConstraintAuthoringEngine(
      ConstraintAuthoringWorkflow workflow,
      IliSpecRenderer renderer,
      ConstraintCaseGenerationTools caseGenerationTools,
      ModelChangeReviewService reviewService) {
    this.workflow = workflow;
    this.renderer = renderer;
    this.caseGenerationTools = caseGenerationTools;
    this.reviewService = reviewService;
  }

  public IliAuthoringResult author(
      String modelText,
      String contextFqn,
      IliConstraintSpec spec,
      @Nullable ModelPurpose modelPurpose,
      @Nullable ModelingRuleProfile ruleProfile) {
    String context;
    String constraintName;
    String version;
    Set<String> imports = new LinkedHashSet<>();
    String constraintText;
    try {
      context = requireFqn(contextFqn, "contextFqn");
      if (spec == null) throw new SpecValidationException("MISSING_FIELD", "/spec", "spec is required.", null);
      constraintName = ConstraintSpecContract.technicalName(spec.name, "/spec/name");
      version = iliVersion(modelText);
      String modelName = context.substring(0, context.indexOf('.'));
      constraintText = renderer.forProfile(ruleProfile).renderConstraint(spec, version, modelName, imports);
    } catch (IllegalArgumentException ex) {
      return SpecValidationException.attach(
          new IliAuthoringResult().reject(Status.INVALID_SPEC, ex.getMessage(), null), ex);
    }

    IliCompilerService.CompilationResult before = workflow.compileBefore(
        modelText, "ili2c_constraint_authoring_before_");
    if (!before.valid() || before.transferDescription() == null) {
      var result = new IliAuthoringResult().reject(
          Status.BEFORE_MODEL_INVALID, "The supplied before model must compile.", null);
      result.beforeCompilation = IliAuthoringResult.Compilation.from(before);
      return result;
    }

    ConstraintAuthoringWorkflow.PreparedConstraint prepared;
    try {
      prepared = workflow.insertAndResolve(
          modelText,
          before,
          context,
          constraintText,
          context + "." + constraintName,
          "ili2c_constraint_authoring_after_",
          imports);
    } catch (IllegalArgumentException ex) {
      var result = new IliAuthoringResult().reject(Status.INVALID_SPEC, ex.getMessage(), null);
      result.beforeCompilation = IliAuthoringResult.Compilation.from(before);
      return SpecValidationException.attach(result, ex);
    }

    String candidate = prepared.insertion().updatedModelText();
    IliCompilerService.CompilationResult after = prepared.resolution().compilation();
    Map<String, Object> review = reviewService.reviewCompiledChange(
        before,
        after,
        modelText,
        candidate,
        ModelPurpose.normalize(modelPurpose),
        ModelingRuleProfile.normalize(ruleProfile));
    IliAuthoringResult result = IliAuthoringResult.fromChangeReview(review, before, after);
    result.sourceEdits = prepared.insertion().sourceEdits().stream()
        .map(this::sourceEdit)
        .toList();
    result.derivedImports = List.copyOf(imports);

    if (!result.afterCompilation.valid()) {
      return result.reject(Status.CANDIDATE_MODEL_INVALID, "The rendered constraint candidate does not compile.", candidate);
    }
    String constraintFqn = context + "." + constraintName;
    var resolution = prepared.resolution();
    if (!ConstraintAstComparison.verify(Map.of(constraintFqn, spec), ignored -> resolution, result, candidate)) {
      return result;
    }
    if (result.rejectFailedProof(List.of(caseGenerationTools.prove(
        constraintFqn, resolution, spec.hasExternalFunctionSemantics())), candidate)) return result;
    return result.release(Status.GENERATED, candidate);
  }

  private IliAuthoringResult.SourceEdit sourceEdit(
      ConstraintSourceEditService.SourceEdit edit) {
    return IliAuthoringResult.sourceEdit(
        edit.startOffset(),
        edit.endOffset(),
        edit.startLine(),
        edit.endLine(),
        edit.before(),
        edit.after(),
        edit.description());
  }

  private String iliVersion(String text) {
    Matcher matcher = ILI_VERSION.matcher(text == null ? "" : text);
    if (!matcher.find()) throw new IllegalArgumentException(
        "modelText must declare INTERLIS 2.3 or 2.4.");
    return matcher.group(1);
  }

  private String requireFqn(String value, String label) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required.");
    String normalized = value.trim();
    NameValidator.ascii().validateFqn(normalized, label);
    if (normalized.chars().filter(character -> character == '.').count() < 2) {
      throw new IllegalArgumentException(
        label + " must contain model, topic and viewable names.");
    }
    return normalized;
  }

  private String requireIdent(String value, String label) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required.");
    String normalized = value.trim();
    NameValidator.ascii().validateIdent(normalized, label);
    return normalized;
  }

}
