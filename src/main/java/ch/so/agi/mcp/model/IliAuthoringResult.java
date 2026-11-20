package ch.so.agi.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import ch.so.agi.mcp.constraint.ConstraintInteractionAnalysis;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Stable, typed output contract shared by high-level authoring and change tools. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IliAuthoringResult {
  public enum Status {
    VERIFIED,
    GENERATED,
    APPLIED,
    BREAKING_CHANGE_REQUIRES_CONFIRMATION,
    NEEDS_INPUT,
    INVALID_SPEC,
    BEFORE_MODEL_INVALID,
    CANDIDATE_MODEL_INVALID,
    AST_ROUND_TRIP_FAILED,
    PROOF_INCOMPLETE,
    PROOF_FAILED,
    EXTERNAL_FUNCTION_SEMANTICS_REQUIRED,
    UNEXPECTED_SEMANTIC_CHANGE
  }

  /** Request-contract diagnostics; additive to status/reason and compiler diagnostics. */
  public List<SpecDiagnostic> specDiagnostics = List.of();

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SpecDiagnostic(String code, String path, String message, @Nullable String hint) {}

  public @Nullable ModelHashes modelHashes;
  public @Nullable Integer omittedSuccessfulTestXtfCount;

  public Status status;
  public @Nullable String updatedModelText;
  public @Nullable String candidateModelText;
  public @Nullable Compilation beforeCompilation;
  public @Nullable Compilation afterCompilation;
  public List<SourceEdit> sourceEdits = List.of();
  public List<String> derivedImports = List.of();
  public @Nullable SemanticDiff semanticDiff;
  public @Nullable ModelReview afterReview;
  /** Absent before the proof stage; empty when that stage identifies no required constraints. */
  public @Nullable List<ConstraintProof> constraintProofs;
  public List<OpenQuestion> openQuestions = List.of();
  public @Nullable String reasonCode;
  public @Nullable String reason;

  public record Compilation(boolean valid, List<CompilerDiagnostic> diagnostics) {
    public static Compilation from(IliCompilerService.CompilationResult compilation) {
      return new Compilation(compilation.valid() && compilation.transferDescription() != null,
          compilation.messages().stream().map(IliAuthoringResult::diagnostic).toList());
    }
  }

  public IliAuthoringResult reject(Status status, @Nullable String reason, @Nullable String candidate) {
    this.status = status;
    this.reason = reason;
    this.updatedModelText = null;
    this.candidateModelText = candidate;
    return this;
  }

  public IliAuthoringResult release(Status status, String modelText) {
    if ((status != Status.GENERATED && status != Status.APPLIED)
        || afterCompilation == null || !afterCompilation.valid()
        || constraintProofs == null || constraintProofs.stream().anyMatch(proof -> proof.status != ProofStatus.PASSED)
        || openQuestions.stream().anyMatch(question -> "BLOCKING".equals(question.decisionImpact))) {
      throw new IllegalStateException("A model can be released only after its required checks have passed.");
    }
    this.status = status;
    this.candidateModelText = null;
    this.updatedModelText = modelText;
    return this;
  }

  public boolean rejectFailedProof(List<ConstraintProof> proofs, String candidate) {
    constraintProofs = List.copyOf(proofs);
    for (var outcome : List.of(ProofStatus.EXTERNAL_FUNCTION_SEMANTICS_REQUIRED,
        ProofStatus.FAILED, ProofStatus.INCOMPLETE)) {
      for (var proof : proofs) {
        if (proof.status != outcome) continue;
        if (outcome == ProofStatus.EXTERNAL_FUNCTION_SEMANTICS_REQUIRED) {
          addQuestion("Provide an independently verified implementation and validator cases for external functions.");
        }
        Status failure = switch (outcome) {
          case EXTERNAL_FUNCTION_SEMANTICS_REQUIRED -> Status.EXTERNAL_FUNCTION_SEMANTICS_REQUIRED;
          case FAILED -> Status.PROOF_FAILED;
          case INCOMPLETE -> Status.PROOF_INCOMPLETE;
          case PASSED -> throw new IllegalStateException("A passed proof cannot block release");
        };
        reasonCode = failure.name().equals(proof.reasonCode) ? null : proof.reasonCode;
        reject(failure, proof.reason, candidate);
        return true;
      }
    }
    return false;
  }

  public void addQuestion(String question) {
    var questions = new ArrayList<>(openQuestions);
    OpenQuestion entry = new OpenQuestion();
    entry.question = question;
    entry.decisionImpact = "BLOCKING";
    questions.add(entry);
    openQuestions = List.copyOf(questions);
  }

  public static IliAuthoringResult fromChangeReview(Map<String, Object> review,
      IliCompilerService.CompilationResult before, IliCompilerService.CompilationResult after) {
    var result = new IliAuthoringResult();
    result.beforeCompilation = Compilation.from(before);
    result.afterCompilation = Compilation.from(after);
    if (result.afterCompilation.valid()) {
      result.semanticDiff = semanticDiff(review);
      if (review.get("afterReview") instanceof Map<?, ?> afterReview) {
        result.attachReview(afterReview, false);
      }
    }
    return result;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class CompilerDiagnostic {
    public @Nullable String severity;
    public @Nullable String message;
    public @Nullable String file;
    public @Nullable Integer line;
    public @Nullable Integer column;
    public @Nullable SourceExcerpt sourceExcerpt;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class SourceExcerpt {
    public @Nullable Integer startLine;
    public @Nullable Integer endLine;
    public @Nullable String text;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class SourceEdit {
    public int startOffset;
    public int endOffset;
    public int startLine;
    public int endLine;
    public String before;
    public String after;
    public String description;
  }

  /** A normalized semantic change. properties contains the typed model-analysis projection. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class SemanticChange {
    public @Nullable String kind;
    public @Nullable String name;
    public @Nullable String scopedName;
    public @Nullable String owner;
    public @Nullable String model;
    public @Nullable Boolean mandatory;
    public @Nullable String reason;
    public List<String> changedFields = List.of();
    public @Nullable Map<String, Object> before;
    public @Nullable Map<String, Object> after;
    public Map<String, Object> properties = Map.of();

  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class SemanticDiff {
    public List<SemanticChange> added = List.of();
    public List<SemanticChange> removed = List.of();
    public List<SemanticChange> changed = List.of();
    public List<SemanticChange> potentiallyBreakingChanges = List.of();
    public @Nullable String impact;
    public @Nullable String kind;
    public @Nullable String modelName;
    public @Nullable Boolean astGenerated;
    public @Nullable Boolean astRoundTripVerified;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class ModelReview {
    public ConstraintInteractionAnalysis.@Nullable Result constraintInteractions;
    public @Nullable Boolean validForAutomatedRules;
    public @Nullable String modelPurpose;
    public @Nullable String ruleProfile;
    public List<ReviewFinding> ruleFindings = List.of();
    public List<ReviewFinding> manualChecks = List.of();
    public @Nullable Integer automatedRuleCount;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class ReviewFinding {
    public @Nullable String id;
    public @Nullable String title;
    public @Nullable String severity;
    public @Nullable String message;
    public @Nullable String recommendation;
    public @Nullable String sourceUrl;
    public @Nullable String sourceSection;
    public @Nullable String location;
  }

  public enum ProofStatus { PASSED, FAILED, INCOMPLETE, EXTERNAL_FUNCTION_SEMANTICS_REQUIRED }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class ConstraintProof {
    public String constraintFqn;
    public ProofStatus status;
    public ch.so.agi.mcp.constraint.@Nullable ConstraintExplanation explanation;
    public @Nullable String pattern;
    public @Nullable Integer coverageGoalCount;
    public @Nullable Integer coverageSolvedCount;
    public @Nullable Integer coverageExcludedCount;
    public List<CoverageExclusion> coverageExcludedGoals = List.of();
    public @Nullable Boolean coverageComplete;
    public List<CoverageGap> coverageGaps = List.of();
    public List<ProofCase> generatedCases = List.of();
    public @Nullable ProofVerification verification;
    public @Nullable String reasonCode;
    public @Nullable String reason;
    public List<String> limitations = List.of();

    @JsonIgnore public boolean casesGenerated;
    @JsonIgnore public boolean reportIncomplete;
    @JsonIgnore public boolean multipleRoutes;
    @JsonIgnore public List<ProofCase> objectPathGoals = List.of();
    @JsonIgnore public List<CoverageGap> objectPathGaps = List.of();
    @JsonIgnore public List<CoverageExclusion> objectPathExcluded = List.of();
    @JsonIgnore public @Nullable String planningContextFqn;
    @JsonIgnore public Map<String, Object> generationDetails = new LinkedHashMap<>();

    @JsonIgnore
    public boolean generationVerified() {
      return casesGenerated && objectPathGaps.isEmpty() && verification != null && verification.allPassed();
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class CoverageGoal {
    public @Nullable String goal;
    public @Nullable String reason;
    public @Nullable String expression;

    public CoverageGoal(String goal, String reason, String expression) {
      this.goal = goal;
      this.reason = reason;
      this.expression = expression;
    }

    public Map<String, Object> toMap() {
      return Map.of("goal", goal, "reason", reason, "expression", expression);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class CoverageExclusion {
    public @Nullable String goal;
    public @Nullable String reason;
    public @Nullable String expression;
    public @Nullable String reasonCode;
    public @Nullable String justification;
    @JsonIgnore public @Nullable String name;
    @JsonIgnore public Map<String, Object> details = new LinkedHashMap<>();

    public CoverageExclusion(@Nullable String goal, @Nullable String reason, String reasonCode, @Nullable String justification) {
      this.goal = goal;
      this.reason = reason;
      this.reasonCode = reasonCode;
      this.justification = justification;
    }

    public Map<String, Object> toMap() {
      return withFields(details, "name", name, "goal", goal, "reason", reason, "reasonCode", reasonCode,
          "expression", expression, "justification", justification);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class CoverageGap {
    public @Nullable String goal;
    public @Nullable String reason;
    public @Nullable String expression;
    public @Nullable String reasonCode;
    public @Nullable String solverReason;
    @JsonIgnore public @Nullable String name;
    @JsonIgnore public Map<String, Object> details = new LinkedHashMap<>();

    public CoverageGap(String reasonCode, String reason, @Nullable String goal) {
      this.reasonCode = reasonCode;
      this.reason = reason;
      this.goal = goal;
    }

    public Map<String, Object> toMap() {
      return withFields(details, "name", name, "goal", goal, "reason", reason, "reasonCode", reasonCode,
          "expression", expression, "solverReason", solverReason);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class ProofCase {
    public List<CoverageGoal> coveredGoals = List.of();
    public @Nullable String name;
    public @Nullable String purpose;
    public @Nullable String reason;
    public @Nullable Boolean expectedConstraintValid;
    public @Nullable Integer expectedViolationCount;
    public @Nullable Integer objectCount;
    public @Nullable Integer associationLinkCount;
    public @Nullable String basketId;
    public @Nullable String routeTargetFqn;
    public @Nullable String ownerClassFqn;
    public @Nullable String structurePath;
    public Map<String, Object> values = Map.of();
    public List<Map<String, Object>> objects = List.of();
    @JsonIgnore public Map<String, Object> details = new LinkedHashMap<>();

    public ProofCase(String name, String purpose, boolean expectedConstraintValid) {
      this.name = name;
      this.purpose = purpose;
      this.expectedConstraintValid = expectedConstraintValid;
    }

    @JsonIgnore private boolean hasValues;

    @SuppressWarnings("unchecked")
    public void setSummaryValues(Object values) {
      if (values instanceof Map<?, ?> map) {
        this.values = (Map<String, Object>) map;
        hasValues = true;
      } else {
        details.put("values", values);
      }
    }

    public Map<String, Object> toMap() {
      return toMap(true);
    }

    public Map<String, Object> toMap(boolean includeOwner) {
      return withFields(details, "name", name, "purpose", purpose, "reason", reason,
          "expectedConstraintValid", expectedConstraintValid, "expectedViolationCount", expectedViolationCount,
          "objectCount", objectCount, "associationLinkCount", associationLinkCount, "basketId", basketId,
          "routeTargetFqn", routeTargetFqn, "ownerClassFqn", includeOwner ? ownerClassFqn : null, "structurePath", includeOwner ? structurePath : null,
          "values", hasValues ? values : null,
          "objects", objects.isEmpty() ? null : objects,
          "coveredGoals", coveredGoals.isEmpty() ? null : coveredGoals.stream().map(CoverageGoal::toMap).toList());
    }

    @Override
    public String toString() {
      return "ProofCase{name=" + name + ", purpose=" + purpose
          + ", expected=" + expectedConstraintValid + ", values=" + values + "}";
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class ProofVerification {
    public List<ProofCaseVerification> cases = List.of();
    @JsonIgnore public List<ProofCase> viewScopeGoals = List.of();
    @JsonIgnore public List<CoverageGap> viewScopeGaps = List.of();
    @JsonIgnore public List<CoverageExclusion> viewScopeExcluded = List.of();
    @JsonIgnore public boolean viewScopeChecked;

    public int passedCount() {
      return (int) cases.stream().filter(test -> Boolean.TRUE.equals(test.passed)).count();
    }

    public boolean allPassed() {
      return viewScopeGaps.isEmpty() && cases.stream().allMatch(test -> Boolean.TRUE.equals(test.passed));
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class ProofCaseVerification {
    public @Nullable Integer warningCount;
    public @Nullable String viewFqn;
    public @Nullable String baseClassFqn;
    public List<Map<String,Object>> objectCounts = List.of();
    public @Nullable Integer plannedSubjectCount;
    public @Nullable Integer baseSubjectCount;
    public @Nullable Integer excludedSubjectCount;
    public @Nullable Integer skippedFilterCount;
    public @Nullable Boolean setExecuted;
    public @Nullable Integer subjectCount;
    public @Nullable Boolean constraintExercised;
    public @Nullable Boolean fixtureValid;
    public @Nullable String fixturePreparationReasonCode;
    public List<Map<String, Object>> fixtureErrors = List.of();
    public @Nullable String name;
    public @Nullable Boolean passed;
    public @Nullable Boolean expectedConstraintValid;
    public @Nullable Boolean actualConstraintValid;
    public @Nullable Integer expectedViolationCount;
    public @Nullable Integer targetViolationCount;
    public @Nullable String expectationSource;
    public @Nullable Integer basketCount;
    public List<Map<String, Object>> baskets = List.of();
    public List<Map<String, Object>> messages = List.of();
    public @Nullable String reason;
    public @Nullable String xtfText;
    @JsonIgnore public boolean validatorRan;
    @JsonIgnore public int errorCount;
    @JsonIgnore public @Nullable Integer filterCount;

    public Map<String, Object> toMap(boolean includeXtf) {
      return withFields(Map.of(), "name", name, "expectedConstraintValid", expectedConstraintValid,
          "actualConstraintValid", actualConstraintValid, "passed", passed,
          "constraintExercised", constraintExercised, "subjectCount", subjectCount, "setExecuted", setExecuted,
          "viewFqn", viewFqn, "baseClassFqn", baseClassFqn, "filterCount", filterCount,
          "filterPolicy", filterCount == null ? null : "PINNED_VALIDATOR_SKIP_INCLUDES",
          "baseSubjectCount", baseSubjectCount, "excludedSubjectCount", excludedSubjectCount,
          "skippedFilterCount", skippedFilterCount, "objectCounts", validatorRan ? objectCounts : null,
          "plannedSubjectCount", plannedSubjectCount, "basketCount", basketCount, "baskets", baskets,
          "fixtureValid", fixtureValid, "validatorValid", errorCount == 0,
          "targetViolationCount", targetViolationCount, "errorCount", errorCount, "warningCount", warningCount,
          "messages", messages, "fixtureErrors", fixtureErrors, "xtfText", includeXtf ? xtfText : null,
          "fixturePreparationReasonCode", fixturePreparationReasonCode, "reason", reason,
          "expectationSource", expectationSource);
    }
  }

  private static Map<String, Object> withFields(Map<String, Object> details, Object... fields) {
    var result = new LinkedHashMap<>(details);
    for (int i = 0; i < fields.length; i += 2) {
      if (fields[i + 1] != null) result.put((String) fields[i], fields[i + 1]);
    }
    return result;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class OpenQuestion {
    public String question;
    public @Nullable String kind;
    public List<String> options = List.of();
    public @Nullable String reason;
    public @Nullable String decisionImpact;
  }

  public static SourceEdit sourceEdit(
      int startOffset,
      int endOffset,
      int startLine,
      int endLine,
      String before,
      String after,
      String description) {
    SourceEdit result = new SourceEdit();
    result.startOffset = startOffset;
    result.endOffset = endOffset;
    result.startLine = startLine;
    result.endLine = endLine;
    result.before = before;
    result.after = after;
    result.description = description;
    return result;
  }

  private static List<SemanticChange> semanticChanges(@Nullable Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    List<SemanticChange> result = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) result.add(semanticChange(stringMap(map)));
    }
    return List.copyOf(result);
  }

  public static SemanticDiff semanticDiff(Map<String, Object> review) {
    SemanticDiff result = new SemanticDiff();
    result.added = semanticChanges(review.get("added"));
    result.removed = semanticChanges(review.get("removed"));
    result.changed = semanticChanges(review.get("changed"));
    result.potentiallyBreakingChanges = semanticChanges(review.get("potentiallyBreakingChanges"));
    result.impact = nullableString(review.get("impact"));
    result.kind = nullableString(review.get("kind"));
    result.modelName = nullableString(review.get("modelName"));
    result.astRoundTripVerified = bool(review.get("astRoundTripVerified"));
    return result;
  }

  public void attachReview(Map<?, ?> map, boolean questionsBlock) {
    ModelReview review = new ModelReview();
    if (map.get("constraintInteractions") instanceof ConstraintInteractionAnalysis.Result interactions) {
      review.constraintInteractions = interactions;
    }
    review.validForAutomatedRules = bool(map.get("validForAutomatedRules"));
    review.modelPurpose = nullableString(map.get("modelPurpose"));
    review.ruleProfile = nullableString(map.get("ruleProfile"));
    review.automatedRuleCount = nullableInteger(map.get("automatedRuleCount"));
    review.ruleFindings = reviewFindings(map.get("ruleFindings"));
    review.manualChecks = reviewFindings(map.get("manualChecks"));
    afterReview = review;
    openQuestions = openQuestions(map.get("openQuestions"), questionsBlock ? "BLOCKING" : "ADVISORY");
  }

  private static List<OpenQuestion> openQuestions(@Nullable Object value, String impact) {
    if (!(value instanceof List<?> list)) return List.of();
    List<OpenQuestion> result = new ArrayList<>();
    for (Object item : list) {
      Map<?, ?> map = (Map<?, ?>) item;
      OpenQuestion question = new OpenQuestion();
      question.question = (String) map.get("question");
      question.kind = (String) map.get("kind");
      question.options = strings(map.get("options"));
      question.reason = (String) map.get("reason");
      question.decisionImpact = impact;
      result.add(question);
    }
    return List.copyOf(result);
  }

  @Override
  public String toString() {
    return "IliAuthoringResult{status=" + status + ", reasonCode=" + reasonCode
        + ", reason=" + reason + ", proofs=" + (constraintProofs == null ? null
              : constraintProofs.stream().map(p -> p.constraintFqn + ": " + p.status + " " + p.reasonCode).toList()) + "}";
  }

  private static CompilerDiagnostic diagnostic(Map<String, Object> map) {
    CompilerDiagnostic result = new CompilerDiagnostic();
    result.severity = nullableString(map.get("severity"));
    result.message = nullableString(map.get("message"));
    result.file = nullableString(map.get("file"));
    result.line = nullableInteger(map.get("line"));
    result.column = nullableInteger(map.get("column"));
    if (map.get("sourceExcerpt") instanceof Map<?, ?> raw) {
      Map<String, Object> excerpt = stringMap(raw);
      SourceExcerpt source = new SourceExcerpt();
      source.startLine = nullableInteger(excerpt.get("startLine"));
      source.endLine = nullableInteger(excerpt.get("endLine"));
      source.text = nullableString(excerpt.get("text"));
      result.sourceExcerpt = source;
    }
    return result;
  }

  private static SemanticChange semanticChange(Map<String, Object> map) {
    SemanticChange result = new SemanticChange();
    result.kind = nullableString(map.get("kind"));
    result.name = nullableString(map.get("name"));
    result.scopedName = nullableString(map.get("scopedName"));
    result.owner = nullableString(map.get("owner"));
    result.model = nullableString(map.get("model"));
    result.mandatory = bool(map.get("mandatory"));
    result.reason = nullableString(map.get("reason"));
    result.changedFields = strings(map.get("changedFields"));
    result.before = mapValue(map.get("before"));
    result.after = mapValue(map.get("after"));
    LinkedHashMap<String, Object> properties = new LinkedHashMap<>(map);
    for (String key : List.of(
        "kind", "name", "scopedName", "owner", "model", "mandatory", "reason",
        "changedFields", "before", "after")) properties.remove(key);
    result.properties = Map.copyOf(properties);
    return result;
  }

  private static List<ReviewFinding> reviewFindings(@Nullable Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    List<ReviewFinding> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> raw)) continue;
      Map<String, Object> map = stringMap(raw);
      ReviewFinding finding = new ReviewFinding();
      finding.id = nullableString(map.get("id"));
      finding.title = nullableString(map.get("title"));
      finding.severity = nullableString(map.get("severity"));
      finding.message = nullableString(map.get("message"));
      finding.recommendation = nullableString(map.get("recommendation"));
      finding.sourceUrl = nullableString(map.get("sourceUrl"));
      finding.sourceSection = nullableString(map.get("sourceSection"));
      finding.location = nullableString(map.get("location"));
      result.add(finding);
    }
    return List.copyOf(result);
  }

  private static @Nullable Map<String, Object> mapValue(@Nullable Object value) {
    return value instanceof Map<?, ?> map ? Map.copyOf(stringMap(map)) : null;
  }

  private static Map<String, Object> stringMap(Map<?, ?> map) {
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, value) -> result.put(String.valueOf(key), value));
    return result;
  }

  private static List<String> strings(@Nullable Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream().filter(item -> item != null).map(String::valueOf).toList();
  }

  private static @Nullable Boolean bool(@Nullable Object value) {
    return value instanceof Boolean bool ? bool : null;
  }

  private static @Nullable Integer nullableInteger(@Nullable Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private static @Nullable String nullableString(@Nullable Object value) {
    return value == null ? null : String.valueOf(value);
  }

}
