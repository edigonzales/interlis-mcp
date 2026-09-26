package ch.so.agi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Output-only projection after evidence calculation. Only known verification case lists are touched. */
public final class TestXtfOutput {
  private TestXtfOutput() {}
  public static final String PARAMETER = "Standard true. false lässt nur XTF-Texte bestandener, ausgeübter Fälle mit gültiger Fixture und null Warnungen weg; alle Diagnosen bleiben erhalten.";

  private static boolean omit(Object passed, Object fixtureValid, Object exercised, Object warnings) {
    return Boolean.TRUE.equals(passed) && Boolean.TRUE.equals(fixtureValid) && Boolean.TRUE.equals(exercised)
        && warnings instanceof Number count && count.doubleValue() == 0;
  }

  /** The typed result is a fresh public response; internal validator results are not modified. */
  public static IliAuthoringResult prepare(IliAuthoringResult result, @Nullable Boolean include) {
    if (!Boolean.FALSE.equals(include)) return result;
    int omitted = 0;
    for (var proof : result.constraintProofs) {
      if (proof.verification == null) continue;
      for (var test : proof.verification.cases) {
        if (test.xtfText != null && omit(test.passed, test.fixtureValid, test.constraintExercised, test.warningCount)) {
          test.xtfText = null;
          omitted++;
        }
      }
    }
    if (omitted > 0) result.omittedSuccessfulTestXtfCount = omitted;
    return result;
  }

  public static Map<String, Object> prepare(Map<String, Object> result, @Nullable Boolean include) {
    if (!Boolean.FALSE.equals(include)) return result;
    var response = new LinkedHashMap<>(result);
    int omitted = projectCases(response);
    if (result.get("verification") instanceof Map<?, ?> raw) {
      var verification = new LinkedHashMap<String, Object>();
      raw.forEach((key, value) -> verification.put((String) key, value));
      omitted += projectCases(verification);
      response.put("verification", verification);
    }
    if (omitted > 0) response.put("omittedSuccessfulTestXtfCount", omitted);
    return response;
  }

  private static int projectCases(Map<String, Object> container) {
    if (!(container.get("cases") instanceof List<?> cases)) return 0;
    int omitted = 0;
    var projected = new ArrayList<Object>(cases.size());
    for (Object item : cases) {
      if (item instanceof Map<?, ?> test && test.get("xtfText") instanceof String
          && omit(test.get("passed"), test.get("fixtureValid"), test.get("constraintExercised"), test.get("warningCount"))) {
        var copy = new LinkedHashMap<>(test);
        copy.remove("xtfText");
        projected.add(copy);
        omitted++;
      } else projected.add(item);
    }
    container.put("cases", projected);
    return omitted;
  }
}
