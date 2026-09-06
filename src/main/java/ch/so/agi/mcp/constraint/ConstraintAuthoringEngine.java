package ch.so.agi.mcp.constraint;

import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.model.IliAuthoringResult;
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

/** Shared two-compile implementation used by all five constraint authoring tools. */
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
    String block;
    try {
      context = requireFqn(contextFqn, "contextFqn");
      if (spec == null) throw new IllegalArgumentException("spec is required.");
      constraintName = requireIdent(spec.name, "spec.name");
      version = iliVersion(modelText);
      String modelName = context.substring(0, context.indexOf('.'));
      block = renderer.renderExternalConstraintBlock(
          context, spec, version, modelName, imports);
    } catch (IllegalArgumentException ex) {
      return failure("INVALID_SPEC", ex.getMessage(), null, List.of());
    }

    IliCompilerService.CompilationResult before = workflow.compileBefore(
        modelText, "ili2c_constraint_authoring_before_");
    if (!before.valid() || before.transferDescription() == null) {
      IliAuthoringResult result = failure(
          "BEFORE_MODEL_INVALID", "The supplied before model must compile.", null,
          before.messages());
      result.beforeDiagnostics = IliAuthoringResult.diagnostics(before.messages());
      return result;
    }

    ConstraintAuthoringWorkflow.PreparedConstraint prepared;
    try {
      prepared = workflow.insertAndResolve(
          modelText,
          before,
          context,
          block,
          context + "." + constraintName,
          "ili2c_constraint_authoring_after_",
          imports);
    } catch (IllegalArgumentException ex) {
      IliAuthoringResult result = failure(
          "INVALID_SPEC", ex.getMessage(), null, before.messages());
      result.beforeDiagnostics = IliAuthoringResult.diagnostics(before.messages());
      return result;
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
    IliAuthoringResult result = reviewResult(review);
    result.generated = false;
    result.complete = false;
    result.proofVerified = false;
    result.beforeDiagnostics = IliAuthoringResult.diagnostics(before.messages());
    result.afterDiagnostics = IliAuthoringResult.diagnostics(after.messages());
    result.compilerDiagnostics = IliAuthoringResult.diagnostics(after.messages());
    result.sourceEdits = prepared.insertion().sourceEdits().stream()
        .map(this::sourceEdit)
        .toList();
    result.derivedImports = List.copyOf(imports);

    if (!after.valid()) {
      result.status = IliAuthoringResult.Status.CANDIDATE_MODEL_INVALID;
      result.reasonCode = result.status.name();
      result.reason = "The rendered constraint candidate does not compile.";
      result.candidateModelText = candidate;
      return result;
    }
    if (containsExternalFunction(spec)) {
      result.status = IliAuthoringResult.Status.EXTERNAL_FUNCTION_SEMANTICS_REQUIRED;
      result.reasonCode = result.status.name();
      result.reason = "The constraint uses a model-defined or validator-extension function whose executable semantics are not part of INTERLIS.";
      result.candidateModelText = candidate;
      result.requiresUserDecision = true;
      result.openQuestions = List.of(IliAuthoringResult.openQuestion(
          "Provide an independently verified implementation and handcrafted validator cases for every external function."));
      result.proofVerified = false;
      return result;
    }
    if (!prepared.resolution().available()) {
      result.status = IliAuthoringResult.Status.AST_ROUND_TRIP_FAILED;
      result.reasonCode = result.status.name();
      result.reason = prepared.resolution().reason();
      result.candidateModelText = candidate;
      return result;
    }

    CompiledConstraintContext compiled = prepared.resolution().context();
    String mismatch = roundTripMismatch(
        spec, context, constraintName, compiled.semantics(), version,
        context.substring(0, context.indexOf('.')));
    if (mismatch != null) {
      result.status = IliAuthoringResult.Status.AST_ROUND_TRIP_FAILED;
      result.reasonCode = result.status.name();
      result.reason = mismatch;
      result.candidateModelText = candidate;
      return result;
    }

    Map<String, Object> proof = caseGenerationTools.generateCompiledConstraintCases(compiled);
    boolean verified = Boolean.TRUE.equals(proof.get("generationVerified"))
        && Boolean.TRUE.equals(proof.get("coverageComplete"));
    result.proofVerified = verified;
    result.constraintProofs = List.of(IliAuthoringResult.constraintProof(
        compiled.constraintFqn(), verified, proof));
    if (!verified) {
      boolean generated = Boolean.TRUE.equals(proof.get("automaticCasesGenerated"));
      boolean proofIncomplete = Boolean.TRUE.equals(proof.get("proofIncomplete"));
      result.status = proofIncomplete
          ? IliAuthoringResult.Status.PROOF_INCOMPLETE
          : generated
          ? IliAuthoringResult.Status.PROOF_FAILED
          : IliAuthoringResult.Status.PROOF_INCOMPLETE;
      if (generated && Boolean.TRUE.equals(proof.get("automaticCasesAvailable"))) {
        result.status = IliAuthoringResult.Status.PROOF_INCOMPLETE;
      }
      result.reasonCode = String.valueOf(
          proof.getOrDefault("reasonCode", result.status.name()));
      result.reason = String.valueOf(proof.getOrDefault(
          "reason", "The constraint proof did not verify every coverage goal."));
      result.candidateModelText = candidate;
      return result;
    }

    result.status = IliAuthoringResult.Status.GENERATED;
    result.complete = true;
    result.generated = true;
    result.updatedModelText = candidate;
    return result;
  }

  private @Nullable String roundTripMismatch(
      IliConstraintSpec requested,
      String context,
      String name,
      SemanticConstraint actual,
      String iliVersion,
      String currentModel) {
    if (!context.equals(actual.contextFqn())) return "Compiled constraint context differs.";
    if (!name.equals(actual.constraintName())) return "Compiled constraint name differs.";
    if (!requested.kind().name().equals(actual.kind().name())) {
      return "Compiled constraint kind differs: " + actual.kind();
    }
    return switch (requested) {
      case IliConstraintSpec.Mandatory mandatory -> mandatoryMismatch(
          mandatory, (SemanticConstraint.Mandatory) actual, iliVersion, currentModel);
      case IliConstraintSpec.Unique unique -> uniqueMismatch(
          unique, (SemanticConstraint.Unique) actual, iliVersion, currentModel);
      case IliConstraintSpec.Existence existence ->
          existenceMismatch(existence, (SemanticConstraint.Existence) actual);
      case IliConstraintSpec.Plausibility plausibility ->
          plausibilityMismatch(
              plausibility, (SemanticConstraint.Plausibility) actual, iliVersion, currentModel);
      case IliConstraintSpec.Set set -> setMismatch(
          set, (SemanticConstraint.Set) actual, iliVersion, currentModel);
    };
  }

  private @Nullable String mandatoryMismatch(
      IliConstraintSpec.Mandatory spec,
      SemanticConstraint.Mandatory actual,
      String iliVersion,
      String currentModel) {
    return expressionMatches(spec.condition, actual.condition(), iliVersion, currentModel)
        ? null : "Compiled MANDATORY condition differs.";
  }

  private @Nullable String uniqueMismatch(
      IliConstraintSpec.Unique spec,
      SemanticConstraint.Unique actual,
      String iliVersion,
      String currentModel) {
    if ((spec.scope == IliConstraintSpec.UniqueScope.LOCAL) != actual.local()) {
      return "Compiled UNIQUE LOCAL scope differs.";
    }
    if ((spec.scope == IliConstraintSpec.UniqueScope.BASKET) != actual.perBasket()) {
      return "Compiled UNIQUE BASKET scope differs.";
    }
    if ((spec.where != null) != (actual.preCondition() != null)) {
      return "Compiled UNIQUE WHERE presence differs.";
    }
    if (spec.where != null && !expressionMatches(
        spec.where, actual.preCondition(), iliVersion, currentModel)) {
      return "Compiled UNIQUE WHERE condition differs.";
    }
    if (actual.local() && (actual.prefix() == null
        || !spec.localPrefix.trim().equals(actual.prefix().path()))) {
      return "Compiled UNIQUE LOCAL prefix differs.";
    }
    List<String> keys = spec.keyPaths.stream().map(String::trim).toList();
    List<String> actualKeys = actual.elements().stream()
        .map(SemanticConstraint.ConstraintPath::path).toList();
    return keys.equals(actualKeys) ? null : "Compiled UNIQUE keys differ: " + actualKeys;
  }

  private @Nullable String existenceMismatch(
      IliConstraintSpec.Existence spec, SemanticConstraint.Existence actual) {
    if (!spec.restrictedPath.trim().equals(actual.restrictedAttribute().path())) {
      return "Compiled EXISTENCE restricted path differs.";
    }
    List<String> requested = spec.requiredIn.stream()
        .map(target -> target.viewableFqn.trim() + ":" + target.attributePath.trim()).toList();
    List<String> compiled = actual.requiredIn().stream()
        .map(target -> target.rootFqn() + ":" + target.path()).toList();
    return requested.equals(compiled) ? null : "Compiled EXISTENCE targets differ: " + compiled;
  }

  private @Nullable String plausibilityMismatch(
      IliConstraintSpec.Plausibility spec,
      SemanticConstraint.Plausibility actual,
      String iliVersion,
      String currentModel) {
    if (!spec.direction.name().equals(actual.direction().name())) {
      return "Compiled PLAUSIBILITY direction differs.";
    }
    if (spec.percentage.compareTo(actual.percentage()) != 0) {
      return "Compiled PLAUSIBILITY percentage differs.";
    }
    return expressionMatches(spec.condition, actual.condition(), iliVersion, currentModel)
        ? null : "Compiled PLAUSIBILITY condition differs.";
  }

  private @Nullable String setMismatch(
      IliConstraintSpec.Set spec,
      SemanticConstraint.Set actual,
      String iliVersion,
      String currentModel) {
    if ((spec.scope == IliConstraintSpec.SetScope.BASKET) != actual.perBasket()) {
      return "Compiled SET scope differs.";
    }
    if ((spec.where != null) != (actual.preCondition() != null)) {
      return "Compiled SET WHERE presence differs.";
    }
    if (spec.where != null && !expressionMatches(
        spec.where, actual.preCondition(), iliVersion, currentModel)) {
      return "Compiled SET WHERE condition differs.";
    }
    if (spec.condition instanceof IliConstraintSpec.ObjectCountSetConditionSpec count) {
      if (!(actual.condition() instanceof SemanticConstraint.ObjectCountSetCondition compiled)) {
        return "Compiled SET condition is not objectCount.";
      }
      if (!count.operator.trim().equals(operator(compiled.operator()))) {
        return "Compiled SET objectCount operator differs.";
      }
      if (count.threshold.compareTo(compiled.threshold()) != 0) {
        return "Compiled SET objectCount threshold differs.";
      }
      if (count.objects instanceof IliConstraintSpec.AllObjectsSpec
          && !(compiled.objects() instanceof SemanticConstraint.AllObjects)) {
        return "Compiled SET object set differs from ALL.";
      }
      if (count.objects instanceof IliConstraintSpec.PathObjectsSpec path) {
        if (!(compiled.objects() instanceof SemanticConstraint.NavigatedObjects navigated)
            || !path.path.trim().equals(navigated.path().path())) {
          return "Compiled SET navigated object path differs.";
        }
      }
    } else if (spec.condition instanceof IliConstraintSpec.BooleanSetConditionSpec bool) {
      if (!(actual.condition() instanceof SemanticConstraint.ValueSetCondition value)) {
        return "Compiled SET condition is not a typed boolean expression.";
      }
      if (!expressionMatches(bool.expression, value.expression(), iliVersion, currentModel)) {
        return "Compiled SET boolean condition differs.";
      }
    }
    return null;
  }

  private boolean expressionMatches(
      IliConstraintSpec.ExpressionSpec requested,
      ConstraintExpression actual,
      String iliVersion,
      String currentModel) {
    if (requested == null || actual == null) return requested == null && actual == null;
    String rendered = renderer.renderExpression(
        requested, iliVersion, currentModel, new LinkedHashSet<>());
    return expressionFingerprint(rendered).equals(expressionFingerprint(
        actual.toInterlis("2.4".equals(iliVersion)
            ? ConstraintExpression.IliVersion.ILI_24
            : ConstraintExpression.IliVersion.ILI_23)));
  }

  private String expressionFingerprint(String expression) {
    return expression.replaceAll("[\\s()]", "");
  }

  private String operator(ConstraintExpression.ComparisonOperator operator) {
    return switch (operator) {
      case EQ -> "==";
      case NE -> "!=";
      case LT -> "<";
      case LE -> "<=";
      case GT -> ">";
      case GE -> ">=";
    };
  }

  private boolean containsExternalFunction(IliConstraintSpec spec) {
    return spec.hasExternalFunctionSemantics();
  }

  private IliAuthoringResult reviewResult(Map<String, Object> review) {
    IliAuthoringResult result = new IliAuthoringResult();
    result.semanticDiff = IliAuthoringResult.semanticDiff(review);
    result.afterReview = IliAuthoringResult.modelReview(review.get("afterReview"));
    result.added = IliAuthoringResult.semanticChanges(review.get("added"));
    result.removed = IliAuthoringResult.semanticChanges(review.get("removed"));
    result.changed = IliAuthoringResult.semanticChanges(review.get("changed"));
    result.potentiallyBreakingChanges = IliAuthoringResult.semanticChanges(
        review.get("potentiallyBreakingChanges"));
    if (review.get("impact") != null) result.impact = String.valueOf(review.get("impact"));
    if (result.afterReview != null) {
      result.openQuestions = result.afterReview.openQuestions;
      result.requiresUserDecision = !result.openQuestions.isEmpty();
    }
    return result;
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

  private IliAuthoringResult failure(
      String status,
      @Nullable String reason,
      @Nullable String candidate,
      List<Map<String, Object>> diagnostics) {
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
    return result;
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
