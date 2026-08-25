package ch.so.agi.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Stable, typed output contract shared by high-level authoring and change tools. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IliAuthoringResult {
  public enum Status {
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

  public Status status;
  public boolean complete;
  public @Nullable Boolean generated;
  public @Nullable Boolean applied;
  public @Nullable String updatedModelText;
  public @Nullable String candidateModelText;
  public List<CompilerDiagnostic> compilerDiagnostics = List.of();
  public List<CompilerDiagnostic> beforeDiagnostics = List.of();
  public List<CompilerDiagnostic> afterDiagnostics = List.of();
  public List<SourceEdit> sourceEdits = List.of();
  public List<String> derivedImports = List.of();
  public List<SemanticChange> added = List.of();
  public List<SemanticChange> removed = List.of();
  public List<SemanticChange> changed = List.of();
  public List<SemanticChange> potentiallyBreakingChanges = List.of();
  public @Nullable String impact;
  public @Nullable SemanticDiff semanticDiff;
  public @Nullable ModelReview afterReview;
  public @Nullable Boolean proofVerified;
  public List<ConstraintProof> constraintProofs = List.of();
  public List<OpenQuestion> openQuestions = List.of();
  public boolean requiresUserDecision;
  public @Nullable String reasonCode;
  public @Nullable String reason;
  public @Nullable Map<String, Object> details;

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

    /** Internal compatibility accessor used by the semantic diff guard. */
    public @Nullable Object get(String key) {
      return switch (key) {
        case "kind" -> kind;
        case "name" -> name;
        case "scopedName" -> scopedName;
        case "owner" -> owner;
        case "model" -> model;
        case "mandatory" -> mandatory;
        case "reason" -> reason;
        case "changedFields" -> changedFields;
        case "before" -> before;
        case "after" -> after;
        default -> properties.get(key);
      };
    }

    public Object getOrDefault(String key, Object fallback) {
      Object value = get(key);
      return value == null ? fallback : value;
    }
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
    public @Nullable Boolean astRoundTripVerified;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class ModelReview {
    public @Nullable Boolean available;
    public @Nullable Boolean valid;
    public @Nullable Boolean compilerValid;
    public @Nullable Boolean validForAutomatedRules;
    public @Nullable String modelPurpose;
    public @Nullable String ruleProfile;
    public List<ReviewFinding> ruleFindings = List.of();
    public List<ReviewFinding> manualChecks = List.of();
    public List<OpenQuestion> openQuestions = List.of();
    public @Nullable String reason;
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

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class ConstraintProof {
    public String constraintFqn;
    public boolean proofVerified;
    public @Nullable Boolean automaticCasesAvailable;
    public @Nullable Boolean automaticCasesGenerated;
    public @Nullable Boolean generationVerified;
    public @Nullable String pattern;
    public @Nullable Integer coverageGoalCount;
    public @Nullable Integer coverageSolvedCount;
    public @Nullable Boolean coverageComplete;
    public List<CoverageGap> coverageGaps = List.of();
    public List<ProofCase> generatedCases = List.of();
    public @Nullable ProofVerification verification;
    public @Nullable String reasonCode;
    public @Nullable String reason;
    public List<String> limitations = List.of();
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class CoverageGap {
    public @Nullable String goal;
    public @Nullable String reason;
    public @Nullable String expression;
    public @Nullable String reasonCode;
    public @Nullable String solverReason;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class ProofCase {
    public @Nullable String name;
    public @Nullable String purpose;
    public @Nullable String reason;
    public @Nullable String outcome;
    public @Nullable Boolean expectedValid;
    public @Nullable Boolean expectedConstraintValid;
    public @Nullable Integer expectedViolationCount;
    public @Nullable Integer objectCount;
    public @Nullable Integer associationLinkCount;
    public @Nullable String basketId;
    public @Nullable String routeTargetFqn;
    public Map<String, Object> values = Map.of();
    public List<Map<String, Object>> objects = List.of();

    public @Nullable Object get(String key) {
      return switch (key) {
        case "name" -> name;
        case "purpose" -> purpose;
        case "reason" -> reason;
        case "outcome" -> outcome;
        case "expectedValid" -> expectedValid;
        case "expectedConstraintValid" -> expectedConstraintValid;
        case "expectedViolationCount" -> expectedViolationCount;
        case "objectCount" -> objectCount;
        case "associationLinkCount" -> associationLinkCount;
        case "basketId" -> basketId;
        case "routeTargetFqn" -> routeTargetFqn;
        case "values" -> values;
        case "objects" -> objects;
        default -> null;
      };
    }

    @Override
    public String toString() {
      return "ProofCase{name=" + name + ", purpose=" + purpose
          + ", expected=" + expectedConstraintValid + ", values=" + values + "}";
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class ProofVerification {
    public @Nullable Boolean allPassed;
    public @Nullable Integer passedCount;
    public @Nullable Integer caseCount;
    public List<ProofCaseVerification> cases = List.of();
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class ProofCaseVerification {
    public @Nullable String name;
    public @Nullable Boolean passed;
    public @Nullable Boolean expectedValid;
    public @Nullable Boolean actualValid;
    public @Nullable Integer expectedViolationCount;
    public @Nullable Integer actualViolationCount;
    public @Nullable String reason;
    public @Nullable String xtfText;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class OpenQuestion {
    public String question;
    public @Nullable String reason;
    public @Nullable String decisionImpact;
  }

  public static List<CompilerDiagnostic> diagnostics(List<Map<String, Object>> input) {
    if (input == null) return List.of();
    return input.stream().map(IliAuthoringResult::diagnostic).toList();
  }

  public static List<SourceEdit> sourceEdits(List<Map<String, Object>> input) {
    if (input == null) return List.of();
    return input.stream().map(IliAuthoringResult::sourceEdit).toList();
  }

  public static SourceEdit sourceEdit(Map<String, Object> input) {
    SourceEdit result = new SourceEdit();
    result.startOffset = integer(input.get("startOffset"), 0);
    result.endOffset = integer(input.get("endOffset"), result.startOffset);
    result.startLine = integer(input.get("startLine"), 1);
    result.endLine = integer(input.get("endLine"), result.startLine);
    result.before = string(input.get("before"), "");
    result.after = string(input.get("after"), "");
    result.description = string(input.get("description"), "");
    return result;
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

  public static List<SemanticChange> semanticChanges(@Nullable Object value) {
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

  public static @Nullable ModelReview modelReview(@Nullable Object value) {
    if (!(value instanceof Map<?, ?> raw)) return null;
    Map<String, Object> map = stringMap(raw);
    ModelReview result = new ModelReview();
    result.available = bool(map.get("available"));
    result.valid = bool(map.get("valid"));
    result.compilerValid = bool(map.get("compilerValid"));
    result.validForAutomatedRules = bool(map.get("validForAutomatedRules"));
    result.modelPurpose = nullableString(map.get("modelPurpose"));
    result.ruleProfile = nullableString(map.get("ruleProfile"));
    result.ruleFindings = reviewFindings(map.get("ruleFindings"));
    result.manualChecks = reviewFindings(map.get("manualChecks"));
    result.openQuestions = openQuestions(map.get("openQuestions"));
    result.reason = nullableString(map.get("reason"));
    return result;
  }

  public static ConstraintProof constraintProof(
      String constraintFqn, boolean proofVerified, @Nullable Map<String, Object> proof) {
    ConstraintProof result = new ConstraintProof();
    result.constraintFqn = constraintFqn;
    result.proofVerified = proofVerified;
    if (proof == null) return result;
    result.automaticCasesAvailable = bool(proof.get("automaticCasesAvailable"));
    result.automaticCasesGenerated = bool(proof.get("automaticCasesGenerated"));
    result.generationVerified = bool(proof.get("generationVerified"));
    result.pattern = nullableString(proof.get("pattern"));
    result.coverageGoalCount = nullableInteger(proof.get("coverageGoalCount"));
    result.coverageSolvedCount = nullableInteger(proof.get("coverageSolvedCount"));
    result.coverageComplete = bool(proof.get("coverageComplete"));
    result.coverageGaps = coverageGaps(proof.get("coverageUnsolved"));
    result.generatedCases = proofCases(proof.get("generatedCases"));
    result.verification = proofVerification(proof.get("verification"));
    result.reasonCode = nullableString(proof.get("reasonCode"));
    result.reason = nullableString(proof.get("reason"));
    result.limitations = strings(proof.get("limitations"));
    return result;
  }

  public static List<OpenQuestion> openQuestions(@Nullable Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    List<OpenQuestion> result = new ArrayList<>();
    for (Object item : list) {
      OpenQuestion question = new OpenQuestion();
      if (item instanceof Map<?, ?> raw) {
        Map<String, Object> map = stringMap(raw);
        question.question = string(map.get("question"), "");
        question.reason = nullableString(map.get("reason"));
        question.decisionImpact = nullableString(map.get("decisionImpact"));
      } else if (item != null) {
        question.question = String.valueOf(item);
      } else {
        continue;
      }
      result.add(question);
    }
    return List.copyOf(result);
  }

  public static OpenQuestion openQuestion(String question) {
    OpenQuestion result = new OpenQuestion();
    result.question = question;
    return result;
  }

  @Override
  public String toString() {
    return "IliAuthoringResult{status=" + status
        + ", complete=" + complete
        + ", proofVerified=" + proofVerified
        + ", proofs=" + constraintProofs.stream().map(proof ->
            proof.constraintFqn + ":complete=" + proof.coverageComplete
                + ":gaps=" + proof.coverageGaps.stream()
                    .map(gap -> gap.reasonCode + "/" + gap.reason).toList()).toList()
        + ", reasonCode=" + reasonCode
        + ", reason=" + reason + "}";
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

  private static List<CoverageGap> coverageGaps(@Nullable Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    List<CoverageGap> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> raw)) continue;
      Map<String, Object> map = stringMap(raw);
      CoverageGap gap = new CoverageGap();
      gap.goal = nullableString(map.get("goal"));
      gap.reason = nullableString(map.get("reason"));
      gap.expression = nullableString(map.get("expression"));
      gap.reasonCode = nullableString(map.get("reasonCode"));
      gap.solverReason = nullableString(map.get("solverReason"));
      result.add(gap);
    }
    return List.copyOf(result);
  }

  private static List<ProofCase> proofCases(@Nullable Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    List<ProofCase> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> raw)) continue;
      Map<String, Object> map = stringMap(raw);
      ProofCase proofCase = new ProofCase();
      proofCase.name = nullableString(map.get("name"));
      proofCase.purpose = nullableString(map.get("purpose"));
      proofCase.reason = nullableString(map.get("reason"));
      proofCase.outcome = nullableString(map.get("outcome"));
      proofCase.expectedValid = bool(map.get("expectedValid"));
      proofCase.expectedConstraintValid = bool(map.get("expectedConstraintValid"));
      proofCase.expectedViolationCount = nullableInteger(map.get("expectedViolationCount"));
      proofCase.objectCount = nullableInteger(map.get("objectCount"));
      proofCase.associationLinkCount = nullableInteger(map.get("associationLinkCount"));
      proofCase.basketId = nullableString(map.get("basketId"));
      proofCase.routeTargetFqn = nullableString(map.get("routeTargetFqn"));
      Map<String, Object> values = mapValue(map.get("values"));
      proofCase.values = values == null ? Map.of() : values;
      proofCase.objects = mapList(map.get("objects"));
      result.add(proofCase);
    }
    return List.copyOf(result);
  }

  private static @Nullable ProofVerification proofVerification(@Nullable Object value) {
    if (!(value instanceof Map<?, ?> raw)) return null;
    Map<String, Object> map = stringMap(raw);
    ProofVerification result = new ProofVerification();
    result.allPassed = bool(map.get("allPassed"));
    result.passedCount = nullableInteger(map.get("passedCount"));
    result.caseCount = nullableInteger(map.get("caseCount"));
    Object caseValue = map.containsKey("cases") ? map.get("cases") : map.get("results");
    if (caseValue instanceof List<?> list) {
      List<ProofCaseVerification> cases = new ArrayList<>();
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> caseRaw)) continue;
        Map<String, Object> entry = stringMap(caseRaw);
        ProofCaseVerification checked = new ProofCaseVerification();
        checked.name = nullableString(entry.get("name"));
        checked.passed = bool(entry.get("passed"));
        checked.expectedValid = bool(entry.get("expectedValid"));
        checked.actualValid = bool(entry.get("actualValid"));
        checked.expectedViolationCount = nullableInteger(entry.get("expectedViolationCount"));
        checked.actualViolationCount = nullableInteger(entry.get("actualViolationCount"));
        checked.reason = nullableString(entry.get("reason"));
        checked.xtfText = nullableString(entry.get("xtfText"));
        cases.add(checked);
      }
      result.cases = List.copyOf(cases);
    }
    return result;
  }

  private static List<Map<String, Object>> mapList(@Nullable Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) result.add(Map.copyOf(stringMap(map)));
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

  private static int integer(@Nullable Object value, int fallback) {
    Integer result = nullableInteger(value);
    return result == null ? fallback : result;
  }

  private static @Nullable String nullableString(@Nullable Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static String string(@Nullable Object value, String fallback) {
    return value == null ? fallback : String.valueOf(value);
  }
}
