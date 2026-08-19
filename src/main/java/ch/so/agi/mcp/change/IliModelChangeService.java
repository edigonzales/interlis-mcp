package ch.so.agi.mcp.change;

import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public class IliModelChangeService {

  private final IliCompilerService compilerService;
  private final ModelChangeReviewService reviewService;

  public IliModelChangeService(
      IliCompilerService compilerService,
      ModelChangeReviewService reviewService) {
    this.compilerService = compilerService;
    this.reviewService = reviewService;
  }

  public Map<String, Object> apply(
      String modelText,
      IliModelChangeRequest request,
      @Nullable String modelRepositories,
      @Nullable ModelPurpose modelPurpose,
      @Nullable ModelingRuleProfile ruleProfile) {
    requireModelText(modelText);
    Objects.requireNonNull(request, "request");
    IliModelChangeOperation operation = request.requireOperation();

    return unsupportedOperationResponse(operation);
  }

  IliCompilerService.CompilationResult compileBefore(
      String modelText,
      @Nullable String modelRepositories) {
    requireModelText(modelText);
    return compilerService.compile(modelText, modelRepositories, "ili2c_model_change_before_");
  }

  PreparedChange preparePatchedChange(
      IliSourceDocument document,
      List<IliTextPatch> patches) {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(patches, "patches");

    List<IliTextPatch> ordered = new ArrayList<>(patches);
    ordered.sort(Comparator.comparingInt(patch -> patch.span().startOffset()));

    List<Map<String, Object>> sourceEdits = new ArrayList<>();
    for (IliTextPatch patch : ordered) {
      Objects.requireNonNull(patch, "patch");
      Map<String, Object> edit = new LinkedHashMap<>();
      edit.put("startOffset", patch.span().startOffset());
      edit.put("endOffset", patch.span().endOffset());
      edit.put("startLine", patch.span().startLine());
      edit.put("endLine", patch.span().endLine());
      edit.put("before", document.slice(patch.span()));
      edit.put("after", patch.replacement());
      edit.put("description", patch.description());
      sourceEdits.add(edit);
    }

    return new PreparedChange(
        IliPatchApplier.apply(document, ordered),
        List.copyOf(sourceEdits));
  }

  Map<String, Object> finalizePreparedChange(
      String beforeModelText,
      IliModelChangeOperation operation,
      IliCompilerService.CompilationResult beforeCompilation,
      PreparedChange preparedChange,
      @Nullable String modelRepositories,
      @Nullable ModelPurpose modelPurpose,
      @Nullable ModelingRuleProfile ruleProfile) {
    requireModelText(beforeModelText);
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(beforeCompilation, "beforeCompilation");
    Objects.requireNonNull(preparedChange, "preparedChange");

    ModelPurpose purpose = ModelPurpose.normalize(modelPurpose);
    ModelingRuleProfile profile = ModelingRuleProfile.normalize(ruleProfile);

    if (!beforeCompilation.valid()) {
      return beforeModelInvalidResponse(operation, beforeCompilation);
    }

    if (beforeModelText.equals(preparedChange.updatedModelText())) {
      return noChangeResponse(operation, beforeCompilation, preparedChange.sourceEdits());
    }

    IliCompilerService.CompilationResult afterCompilation =
        compilerService.compile(
            preparedChange.updatedModelText(),
            modelRepositories,
            "ili2c_model_change_after_");

    Map<String, Object> review = reviewService.reviewCompiledChange(
        beforeCompilation,
        afterCompilation,
        beforeModelText,
        preparedChange.updatedModelText(),
        purpose,
        profile);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("applied", afterCompilation.valid());
    response.put(
        "status",
        afterCompilation.valid()
            ? IliModelChangeStatus.APPLIED.name()
            : IliModelChangeStatus.AFTER_MODEL_INVALID.name());
    response.put("operation", operation.name());
    response.put("sourceEdits", preparedChange.sourceEdits());
    if (afterCompilation.valid()) {
      response.put("updatedModelText", preparedChange.updatedModelText());
    } else {
      response.put("candidateModelText", preparedChange.updatedModelText());
    }
    response.putAll(review);
    return response;
  }

  private Map<String, Object> unsupportedOperationResponse(IliModelChangeOperation operation) {
    return Map.of(
        "applied", false,
        "status", IliModelChangeStatus.UNSUPPORTED_OPERATION.name(),
        "operation", operation.name(),
        "message", "The change operation is defined in the change IR but is not implemented yet.");
  }

  private Map<String, Object> beforeModelInvalidResponse(
      IliModelChangeOperation operation,
      IliCompilerService.CompilationResult beforeCompilation) {
    return Map.of(
        "applied", false,
        "status", IliModelChangeStatus.BEFORE_MODEL_INVALID.name(),
        "operation", operation.name(),
        "beforeCompilerValid", false,
        "beforeDiagnostics", beforeCompilation.messages());
  }

  private Map<String, Object> noChangeResponse(
      IliModelChangeOperation operation,
      IliCompilerService.CompilationResult beforeCompilation,
      List<Map<String, Object>> sourceEdits) {
    return Map.ofEntries(
        Map.entry("applied", false),
        Map.entry("status", IliModelChangeStatus.NO_CHANGE.name()),
        Map.entry("operation", operation.name()),
        Map.entry("beforeCompilerValid", true),
        Map.entry("beforeDiagnostics", beforeCompilation.messages()),
        Map.entry("hasChanges", false),
        Map.entry("added", List.of()),
        Map.entry("removed", List.of()),
        Map.entry("changed", List.of()),
        Map.entry("potentiallyBreakingChanges", List.of()),
        Map.entry("impact", "NONE"),
        Map.entry("sourceEdits", sourceEdits));
  }

  private void requireModelText(String modelText) {
    if (modelText == null || modelText.isBlank()) {
      throw new IllegalArgumentException("Model text is required.");
    }
  }

  record PreparedChange(
      String updatedModelText,
      List<Map<String, Object>> sourceEdits) {
    PreparedChange {
      if (updatedModelText == null || updatedModelText.isBlank()) {
        throw new IllegalArgumentException("updatedModelText is required.");
      }
      sourceEdits = sourceEdits == null ? List.of() : List.copyOf(sourceEdits);
    }
  }
}
