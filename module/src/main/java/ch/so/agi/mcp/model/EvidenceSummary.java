package ch.so.agi.mcp.model;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Describes observed checks, never authorizes a model or replaces the original results. */
public record EvidenceSummary(Check compiler, Check modelingRules, Check constraintTests,
    Check constraintInteractions, Check businessAcceptance) {
  public enum Status { PASSED, FAILED, HAS_WARNINGS, INCOMPLETE, NOT_RUN, NOT_APPLICABLE }
  public record Check(Status status, String scope, String basis, int checkedCount,
      int errorCount, int warningCount, List<String> reasonCodes) {}

  public static Check check(Status status, String scope, String basis, int count,
      int errors, int warnings, String... reasons) {
    return new Check(status, scope, basis, count, errors, warnings, List.of(reasons));
  }
  public static Check notRun(String scope) {
    return check(Status.NOT_RUN, scope, "NONE", 0, 0, 0);
  }
  public static EvidenceSummary empty() {
    return new EvidenceSummary(notRun("MODEL"), notRun("MODEL"), notRun("SELECTED_CONSTRAINTS"),
        notRun("SUPPORTED_SCALAR_MANDATORY_RULES"), notRun("INDEPENDENT_BUSINESS_REQUIREMENTS"));
  }
  public static Check compiler(@Nullable Boolean valid, List<?> diagnostics) {
    int errors = severity(diagnostics, "ERROR"), warnings = severity(diagnostics, "WARNING");
    return check(valid == null ? Status.NOT_RUN : valid ? Status.PASSED : Status.FAILED,
        "SUBMITTED_OR_CANDIDATE_MODEL", "ILI2C", valid == null ? 0 : 1, errors, warnings);
  }
  public static EvidenceSummary review(boolean compiled, List<?> diagnostics, List<?> findings, String profile) {
    int errors = compiled ? severity(findings, "ERROR") : 0;
    int warnings = compiled ? severity(findings, "WARNING") : 0;
    Check rules = check(!compiled ? Status.NOT_RUN : errors > 0 ? Status.FAILED
        : warnings > 0 ? Status.HAS_WARNINGS : Status.PASSED,
        "AUTOMATED_RULES_" + profile, "CURATED_RULES", 0, errors, warnings,
        "MANUAL_CHECKS_AND_BUSINESS_CORRECTNESS_NOT_VERIFIED");
    return new EvidenceSummary(compiler(compiled, diagnostics), rules, notRun("ALL_DECLARED_CONSTRAINTS"),
        empty().constraintInteractions(), empty().businessAcceptance());
  }
  public static EvidenceSummary changeReview(boolean beforeValid, List<?> beforeDiagnostics,
      boolean afterValid, List<?> afterDiagnostics, EvidenceSummary after) {
    Check compile = check(beforeValid && afterValid ? Status.PASSED : Status.FAILED,
        "BEFORE_AND_AFTER_MODELS", "ILI2C", 2,
        severity(beforeDiagnostics, "ERROR") + severity(afterDiagnostics, "ERROR"),
        severity(beforeDiagnostics, "WARNING") + severity(afterDiagnostics, "WARNING"));
    return new EvidenceSummary(compile, after.modelingRules, after.constraintTests,
        after.constraintInteractions, after.businessAcceptance);
  }

  public EvidenceSummary withRuleCount(int count) {
    Check r = modelingRules;
    return new EvidenceSummary(compiler, new Check(r.status, r.scope, r.basis, count,
        r.errorCount, r.warningCount, r.reasonCodes), constraintTests, constraintInteractions, businessAcceptance);
  }
  public EvidenceSummary withInteractions(Check interactions) {
    return new EvidenceSummary(compiler, modelingRules, constraintTests, interactions, businessAcceptance);
  }
  public static EvidenceSummary authoring(IliAuthoringResult result, String scope) {
    EvidenceSummary base = result.afterReview != null && result.afterReview.evidence != null
        ? result.afterReview.evidence : empty();
    if (base.compiler.status() == Status.NOT_RUN && result.status != null) {
      Boolean valid = switch (result.status) {
        case BEFORE_MODEL_INVALID, CANDIDATE_MODEL_INVALID -> false;
        case AST_ROUND_TRIP_FAILED -> result.compilerDiagnostics.stream()
            .anyMatch(d -> "ERROR".equals(d.severity)) ? false : true;
        default -> null;
      };
      base = new EvidenceSummary(compiler(valid, result.compilerDiagnostics), base.modelingRules,
          base.constraintTests, base.constraintInteractions, base.businessAcceptance);
    }
    if (result.compilerEvidence != null) {
      base = new EvidenceSummary(result.compilerEvidence, base.modelingRules, base.constraintTests,
          base.constraintInteractions, base.businessAcceptance);
    }
    List<IliAuthoringResult.ConstraintProof> proofs = result.constraintProofs;
    boolean attempted = !proofs.isEmpty();
    boolean allPassed = attempted && proofs.stream().allMatch(p -> p.proofVerified);
    boolean incomplete = proofs.stream().anyMatch(p -> !p.proofVerified && (!Boolean.TRUE.equals(p.coverageComplete) || p.verification == null));
    int failures = (int) proofs.stream().filter(p -> p.verification != null)
        .flatMap(p -> p.verification.cases.stream()).filter(c -> !Boolean.TRUE.equals(c.passed)).count();
    int warnings = proofs.stream().filter(p -> p.verification != null)
        .flatMap(p -> p.verification.cases.stream()).mapToInt(c -> c.warningCount == null ? 0 : c.warningCount).sum();
    Status status = allPassed ? Status.PASSED : failures > 0 ? Status.FAILED : incomplete ? Status.INCOMPLETE : attempted ? Status.FAILED
        : Boolean.TRUE.equals(result.proofVerified) || Boolean.TRUE.equals(result.applied)
                && result.added.stream().noneMatch(c -> c.kind != null && c.kind.endsWith("_CONSTRAINT"))
            ? Status.NOT_APPLICABLE : Status.NOT_RUN;
    Check tests = check(status, scope, "AUTOMATIC_CONSTRAINT_DERIVED_CASES", (int) proofs.stream()
            .filter(p -> p.verification != null && !p.verification.cases.isEmpty()).count(),
        failures, warnings,
        "FINITE_COVERAGE_NOT_A_BUSINESS_PROOF");
    return new EvidenceSummary(base.compiler, base.modelingRules, tests, base.constraintInteractions, base.businessAcceptance);
  }
  public static Map<String, Object> generated(Map<String, Object> response, boolean compilerValid, List<?> compilerDiagnostics) {
    var copy = new java.util.LinkedHashMap<>(response);
    boolean verified = Boolean.TRUE.equals(response.get("generationVerified"));
    boolean generated = Boolean.TRUE.equals(response.get("automaticCasesGenerated"));
    boolean complete = Boolean.TRUE.equals(response.get("coverageComplete"));
    Status status = !compilerValid ? Status.NOT_RUN : verified && complete ? Status.PASSED
        : !complete || !generated ? Status.INCOMPLETE : Status.FAILED;
    var base = empty();
    Map<?,?> verification = response.get("verification") instanceof Map<?,?> map ? map : Map.of();
    List<?> cases = list(verification.get("cases"));
    int failures = failedCases(cases);
    if (failures > 0) status = Status.FAILED;
    copy.put("evidence", new EvidenceSummary(compiler(compilerValid, compilerDiagnostics),
        base.modelingRules, check(status, "SELECTED_CONSTRAINTS", "AUTOMATIC_CONSTRAINT_DERIVED_CASES",
            cases.isEmpty() ? 0 : 1, failures, caseWarnings(cases), "FINITE_COVERAGE_NOT_A_BUSINESS_PROOF",
            String.valueOf(response.getOrDefault("reasonCode", "NO_ADDITIONAL_DIAGNOSTIC"))),
        base.constraintInteractions, base.businessAcceptance));
    return copy;
  }
  public static EvidenceSummary explicit(boolean compiled, Map<String, Object> response, List<?> compilerDiagnostics) {
    var base = empty();
    List<?> cases = list(response.get("cases"));
    return new EvidenceSummary(compiler(compiled, compilerDiagnostics), base.modelingRules,
        check(!compiled ? Status.NOT_RUN : Boolean.TRUE.equals(response.get("allPassed")) ? Status.PASSED : Status.FAILED,
            "SELECTED_CONSTRAINTS", "CALLER_SUPPLIED_EXPECTATIONS", cases.size(),
            failedCases(cases), caseWarnings(cases),
            "EXPECTATION_PROVENANCE_NOT_VERIFIED"), base.constraintInteractions, base.businessAcceptance);
  }
  private static int failedCases(List<?> cases) {
    return (int) cases.stream().filter(c -> c instanceof Map<?,?> m && !Boolean.TRUE.equals(m.get("passed"))).count();
  }
  private static int caseWarnings(List<?> cases) {
    return cases.stream().mapToInt(c -> c instanceof Map<?,?> m && m.get("warningCount") instanceof Number n ? n.intValue() : 0).sum();
  }
  private static List<?> list(Object value) { return value instanceof List<?> list ? list : List.of(); }
  private static int severity(List<?> values, String severity) {
    return (int) values.stream().filter(v -> v instanceof Map<?, ?> m ? severity.equals(m.get("severity"))
        : v instanceof IliAuthoringResult.CompilerDiagnostic d && severity.equals(d.severity)).count();
  }
}
