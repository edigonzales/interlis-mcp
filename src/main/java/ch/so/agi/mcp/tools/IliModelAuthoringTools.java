package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.model.IliConstraintSpec;
import ch.so.agi.mcp.model.IliModelSpec;
import ch.so.agi.mcp.model.IliSpecRenderer;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
      description = "Erzeugt aus einer vollständigen typisierten IliModelSpec ein vollständiges INTERLIS-2-Modell. Name, URI, Modellversion und INTERLIS-Version sind explizit erforderlich; fehlende Fachsemantik wird nicht erfunden. Unterstützt Units, Domains, Topics, Klassen, Strukturen, Assoziationen, Attribute, strikte Geometrien sowie UNIQUE, MANDATORY, EXISTENCE, PLAUSIBILITY und SET. Das fertige Modell wird genau einmal mit ili2c kompiliert; AST-Generierung, alle Constraint-Proofs und afterReview verwenden diesen kompilierten Kontext weiter. Das Tool schreibt keine Datei.",
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
      @McpToolParam(description = "Regelprofil: CORE oder SO (Default CORE)", required = false)
      @Nullable ModelingRuleProfile ruleProfile) {
    IliSpecRenderer.RenderedModel rendered;
    try {
      rendered = renderer.renderModel(spec);
    } catch (IllegalArgumentException ex) {
      return failure("INVALID_SPEC", ex.getMessage(), null, List.of());
    }

    IliCompilerService.CompilationResult compilation = compilerService.compile(
        rendered.modelText(), null, "ili2c_author_model_");
    if (!compilation.valid() || compilation.transferDescription() == null) {
      return failure(
          "CANDIDATE_MODEL_INVALID",
          "The typed model candidate did not compile.",
          rendered.modelText(),
          compilation.messages(),
          rendered.derivedImports());
    }

    String astText;
    try {
      astText = compilerService.generateModelsFromLastFile(compilation.transferDescription());
      if (astText == null || astText.isBlank() || !astText.contains("MODEL " + spec.name.trim())) {
        return failure(
            "AST_ROUND_TRIP_FAILED",
            "ili2c could not regenerate the submitted model from its AST.",
            rendered.modelText(), compilation.messages(), rendered.derivedImports());
      }
    } catch (RuntimeException ex) {
      return failure(
          "AST_ROUND_TRIP_FAILED", ex.getMessage(), rendered.modelText(),
          compilation.messages(), rendered.derivedImports());
    }

    Map<String, IliConstraintSpec> authoredConstraints = constraintSpecs(spec);
    boolean hasExternalFunctions = authoredConstraints.values().stream()
        .anyMatch(IliConstraintSpec::hasExternalFunctionSemantics);
    List<IliAuthoringResult.ConstraintProof> proofs = new ArrayList<>();
    boolean proofsVerified = true;
    for (String constraintFqn : constraintFqns(spec)) {
      if (authoredConstraints.get(constraintFqn).hasExternalFunctionSemantics()) {
        IliAuthoringResult.ConstraintProof proofEntry = IliAuthoringResult.constraintProof(
            constraintFqn, false, null);
        proofEntry.reasonCode = "EXTERNAL_FUNCTION_SEMANTICS_REQUIRED";
        proofEntry.reason = "External function semantics cannot be proved from an INTERLIS declaration.";
        proofs.add(proofEntry);
        proofsVerified = false;
        continue;
      }
      ConstraintContextService.Resolution resolution = constraintContextService.resolveCompiled(
          rendered.modelText(), constraintFqn, null, compilation);
      if (!resolution.available()) {
        IliAuthoringResult.ConstraintProof proofEntry = IliAuthoringResult.constraintProof(
            constraintFqn, false, null);
        proofEntry.reasonCode = resolution.reasonCode();
        proofEntry.reason = resolution.reason();
        proofs.add(proofEntry);
        proofsVerified = false;
      } else {
        Map<String, Object> proof = caseGenerationTools.generateCompiledConstraintCases(
            resolution.context());
        boolean verified = Boolean.TRUE.equals(proof.get("generationVerified"))
            && Boolean.TRUE.equals(proof.get("coverageComplete"));
        proofs.add(IliAuthoringResult.constraintProof(constraintFqn, verified, proof));
        proofsVerified &= verified;
      }
    }

    ModelPurpose purpose = ModelPurpose.normalize(modelPurpose);
    ModelingRuleProfile profile = ModelingRuleProfile.normalize(ruleProfile);
    Map<String, Object> afterReview = reviewService.reviewCompiledModel(
        compilation, rendered.modelText(), purpose, profile);

    IliAuthoringResult result = new IliAuthoringResult();
    result.generated = proofsVerified;
    result.complete = proofsVerified;
    result.status = proofsVerified
        ? IliAuthoringResult.Status.GENERATED
        : IliAuthoringResult.Status.PROOF_FAILED;
    result.compilerDiagnostics = IliAuthoringResult.diagnostics(compilation.messages());
    result.afterDiagnostics = IliAuthoringResult.diagnostics(compilation.messages());
    result.derivedImports = rendered.derivedImports();
    result.constraintProofs = List.copyOf(proofs);
    result.proofVerified = proofsVerified;
    result.afterReview = IliAuthoringResult.modelReview(afterReview);
    result.openQuestions = result.afterReview == null
        ? List.of() : result.afterReview.openQuestions;
    result.requiresUserDecision = !result.openQuestions.isEmpty();
    result.semanticDiff = IliAuthoringResult.semanticDiff(Map.of(
        "kind", "NEW_MODEL",
        "modelName", spec.name.trim(),
        "astRoundTripVerified", true));
    result.details = Map.of(
        "compileCount", 1,
        "astRoundTripVerified", true,
        "astGeneratedModelText", astText);
    boolean reviewValid = !Boolean.FALSE.equals(afterReview.get("validForAutomatedRules"));
    if (proofsVerified && reviewValid && !result.requiresUserDecision) {
      result.updatedModelText = rendered.modelText();
    } else {
      result.candidateModelText = rendered.modelText();
      result.generated = false;
      result.complete = false;
      if (hasExternalFunctions) {
        result.status = IliAuthoringResult.Status.EXTERNAL_FUNCTION_SEMANTICS_REQUIRED;
        result.reasonCode = "EXTERNAL_FUNCTION_SEMANTICS_REQUIRED";
        result.reason = "At least one constraint uses a model-defined or validator-extension function whose executable semantics are unavailable.";
        result.requiresUserDecision = true;
        result.openQuestions = List.of(IliAuthoringResult.openQuestion(
            "Provide independently verified validator cases for every external function."));
      } else if (!proofsVerified) {
        boolean incomplete = proofs.stream().anyMatch(
            proof -> !Boolean.TRUE.equals(proof.coverageComplete));
        result.status = incomplete
            ? IliAuthoringResult.Status.PROOF_INCOMPLETE
            : IliAuthoringResult.Status.PROOF_FAILED;
        result.reasonCode = result.status.name();
        result.reason = "At least one compiled constraint could not be fully proved.";
      } else if (!reviewValid) {
        result.status = IliAuthoringResult.Status.NEEDS_INPUT;
        result.reasonCode = "NEEDS_INPUT";
        result.reason = "The final automated modeling review contains blocking findings.";
      } else {
        result.status = IliAuthoringResult.Status.NEEDS_INPUT;
        result.reasonCode = "NEEDS_INPUT";
        result.reason = "The final review requires an explicit user decision.";
      }
    }
    return result;
  }

  private List<String> constraintFqns(IliModelSpec spec) {
    List<String> result = new ArrayList<>();
    for (IliModelSpec.TopicSpec topic : safe(spec.topics)) {
      String topicFqn = spec.name.trim() + "." + topic.name.trim();
      for (IliModelSpec.StructureSpec structure : safe(topic.structures)) {
        addConstraintFqns(result, topicFqn + "." + structure.name.trim(), structure.constraints);
      }
      for (IliModelSpec.ClassSpec clazz : safe(topic.classes)) {
        addConstraintFqns(result, topicFqn + "." + clazz.name.trim(), clazz.constraints);
      }
      for (IliModelSpec.AssociationSpec association : safe(topic.associations)) {
        addConstraintFqns(result, topicFqn + "." + association.name.trim(), association.constraints);
      }
    }
    return List.copyOf(result);
  }

  private Map<String, IliConstraintSpec> constraintSpecs(IliModelSpec spec) {
    Map<String, IliConstraintSpec> result = new LinkedHashMap<>();
    for (IliModelSpec.TopicSpec topic : safe(spec.topics)) {
      String topicFqn = spec.name.trim() + "." + topic.name.trim();
      for (IliModelSpec.StructureSpec structure : safe(topic.structures)) {
        addConstraintSpecs(result, topicFqn + "." + structure.name.trim(), structure.constraints);
      }
      for (IliModelSpec.ClassSpec clazz : safe(topic.classes)) {
        addConstraintSpecs(result, topicFqn + "." + clazz.name.trim(), clazz.constraints);
      }
      for (IliModelSpec.AssociationSpec association : safe(topic.associations)) {
        addConstraintSpecs(result, topicFqn + "." + association.name.trim(), association.constraints);
      }
    }
    return Map.copyOf(result);
  }

  private void addConstraintSpecs(
      Map<String, IliConstraintSpec> result,
      String context,
      @Nullable List<IliConstraintSpec> constraints) {
    for (IliConstraintSpec constraint : safe(constraints)) {
      result.put(context + "." + constraint.name.trim(), constraint);
    }
  }

  private void addConstraintFqns(
      List<String> result,
      String context,
      @Nullable List<IliConstraintSpec> constraints) {
    for (IliConstraintSpec constraint : safe(constraints)) {
      result.add(context + "." + constraint.name.trim());
    }
  }

  private <T> List<T> safe(@Nullable List<T> value) {
    return value == null ? List.of() : value;
  }

  private List<String> reviewQuestions(@Nullable Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    List<String> result = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map && map.get("question") != null) {
        result.add(String.valueOf(map.get("question")));
      } else if (item != null) {
        result.add(String.valueOf(item));
      }
    }
    return List.copyOf(result);
  }

  private IliAuthoringResult failure(
      String status,
      @Nullable String reason,
      @Nullable String candidate,
      List<Map<String, Object>> diagnostics) {
    return failure(status, reason, candidate, diagnostics, List.of());
  }

  private IliAuthoringResult failure(
      String status,
      @Nullable String reason,
      @Nullable String candidate,
      List<Map<String, Object>> diagnostics,
      List<String> derivedImports) {
    IliAuthoringResult result = new IliAuthoringResult();
    result.status = IliAuthoringResult.Status.valueOf(status);
    result.complete = false;
    result.generated = false;
    result.proofVerified = false;
    result.reasonCode = status;
    result.reason = reason == null ? "" : reason;
    result.candidateModelText = candidate;
    result.compilerDiagnostics = IliAuthoringResult.diagnostics(diagnostics);
    result.afterDiagnostics = IliAuthoringResult.diagnostics(diagnostics);
    result.derivedImports = derivedImports;
    return result;
  }
}
