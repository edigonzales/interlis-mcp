package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintPlausibilityCaseGenerationTest {

  private static final String MODEL = """
      INTERLIS 2.4;

      MODEL PlausibilityProof (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Item =
            value : MANDATORY 0 .. 10;
            CONSTRAINT AtLeast80: >= 80% value >= 5;
            CONSTRAINT AtMost20: <= 20% value >= 5;
          END Item;

          CLASS OptionalItem =
            value : 0 .. 10;
            CONSTRAINT AllDefinedOrSkipped: >= 100% value >= 5;
          END OptionalItem;
        END Data;
      END PlausibilityProof.
      """;

  @Test
  void provesAtLeastBoundaryWithRealPopulationFixtures() {
    Map<String, Object> result = tools().generateIliConstraintCases(
        MODEL,
        "AtLeast80");

    assertThat(result.get("generationVerified")).isEqualTo(true);
    assertThat(result.get("pattern")).isEqualTo("PLAUSIBILITY_POPULATION_PROOF");
    assertThat(result.get("coverageComplete")).isEqualTo(true);

    List<Map<String, Object>> cases = generatedCases(result);
    assertBoundary(cases, "BELOW", false);
    Map<String, Object> exact = assertBoundary(cases, "EXACT", true);
    assertThat(exact.get("thresholdPercentage")).isEqualTo("80");
    assertThat(exact.get("actualPercentage")).isEqualTo("80");
    assertThat(exact.get("successfulCount")).isEqualTo(4);
    assertThat(exact.get("failedCount")).isEqualTo(1);
    assertThat(exact.get("populationSize")).isEqualTo(5);
    assertBoundary(cases, "ABOVE", true);

    assertThat(((Map<?, ?>) result.get("verification")).get("allPassed")).isEqualTo(true);
  }

  @Test
  void provesAtMostBoundaryWithOppositeValidity() {
    Map<String, Object> result = tools().generateIliConstraintCases(
        MODEL,
        "AtMost20");

    assertThat(result.get("generationVerified")).isEqualTo(true);
    List<Map<String, Object>> cases = generatedCases(result);
    assertBoundary(cases, "BELOW", true);
    Map<String, Object> exact = assertBoundary(cases, "EXACT", true);
    assertThat(exact.get("actualPercentage")).isEqualTo("20");
    assertThat(exact.get("successfulCount")).isEqualTo(1);
    assertThat(exact.get("populationSize")).isEqualTo(5);
    assertBoundary(cases, "ABOVE", false);
  }

  @Test
  void provesValidatorSkipEvaluationCountsAsSuccess() {
    Map<String, Object> result = tools().generateIliConstraintCases(
        MODEL,
        "AllDefinedOrSkipped");

    assertThat(result.get("generationVerified")).isEqualTo(true);
    Map<String, Object> undefined = generatedCases(result).stream()
        .filter(candidate -> "UNDEFINED_COUNTS_AS_SUCCESS".equals(candidate.get("boundary")))
        .findFirst()
        .orElseThrow();

    assertThat(undefined.get("actualPercentage")).isEqualTo("100");
    assertThat(undefined.get("successfulCount")).isEqualTo(1);
    assertThat(undefined.get("expectedConstraintValid")).isEqualTo(true);
    assertThat(undefined.get("validatorSemantics")).isEqualTo("skipEvaluation counts as successful");
  }

  private ConstraintCaseGenerationTools tools() {
    IliCompilerService compiler = new IliCompilerService();
    ConstraintKnowledgeTools knowledge = new ConstraintKnowledgeTools(compiler);
    ConstraintReviewTools review = new ConstraintReviewTools(compiler, knowledge);
    ConstraintTestTools tests = new ConstraintTestTools(compiler);
    return new ConstraintCaseGenerationTools(new ConstraintContextService(compiler), tests);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> generatedCases(Map<String, Object> result) {
    return (List<Map<String, Object>>) result.get("generatedCases");
  }

  private Map<String, Object> assertBoundary(
      List<Map<String, Object>> cases,
      String boundary,
      boolean expectedValid) {
    Map<String, Object> match = cases.stream()
        .filter(candidate -> boundary.equals(candidate.get("boundary")))
        .findFirst()
        .orElseThrow();
    assertThat(match.get("expectedConstraintValid")).isEqualTo(expectedValid);
    return match;
  }
}
