package ch.so.agi.mcp.model;

import ch.so.agi.mcp.model.IliAuthoringResult.ProofCaseVerification;
import ch.so.agi.mcp.model.IliAuthoringResult.ProofVerification;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Output-only projection after validation. Only known verification case lists are touched. */
public final class TestXtfOutput {
  private TestXtfOutput() {}
  public static final String PARAMETER = "true liefert auch Test-XTFs bestandener, ausgeübter Fälle mit gültiger Fixture und ohne Warnungen. Standard false.";

  public static boolean canOmit(ProofCaseVerification test) {
    return Boolean.TRUE.equals(test.passed) && Boolean.TRUE.equals(test.fixtureValid)
        && Boolean.TRUE.equals(test.constraintExercised) && Integer.valueOf(0).equals(test.warningCount);
  }

  /** The typed result is a fresh public response; internal validator results are not modified. */
  public static IliAuthoringResult prepare(IliAuthoringResult result, @Nullable Boolean include) {
    if (Boolean.TRUE.equals(include) || result.constraintProofs == null) return result;
    int omitted = 0;
    for (var proof : result.constraintProofs) {
      if (proof.verification == null) continue;
      for (var test : proof.verification.cases) {
        if (test.xtfText != null && canOmit(test)) {
          test.xtfText = null;
          omitted++;
        }
      }
    }
    if (omitted > 0) result.omittedSuccessfulTestXtfCount = omitted;
    return result;
  }

  public static void addOmittedCount(Map<String, Object> response, ProofVerification verification, boolean include) {
    if (include) return;
    int omitted = (int) verification.cases.stream().filter(TestXtfOutput::canOmit).count();
    if (omitted > 0) response.put("omittedSuccessfulTestXtfCount", omitted);
  }

  public static Map<String, Object> verificationResponse(ProofVerification verification, boolean include) {
    var response = new LinkedHashMap<String, Object>();
    response.put("cases", verification.cases.stream().map(test -> test.toMap(include || !canOmit(test))).toList());
    response.put("caseCount", verification.cases.size());
    response.put("passedCount", verification.passedCount());
    response.put("allPassed", verification.allPassed());
    return response;
  }
}
