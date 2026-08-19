package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.service.IliCompilerService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintCompiledPipelineTest {

  private static final String MODEL_WITH_CONSTRAINT = """
      INTERLIS 2.4;

      MODEL ReuseTest (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Item =
            value : MANDATORY 0 .. 100;
            !!@ name = "ValueAtLeast10"
            MANDATORY CONSTRAINT value >= 10;
          END Item;
        END Data;
      END ReuseTest.
      """;

  private static final String MODEL_WITH_PLAUSIBILITY = """
      INTERLIS 2.4;

      MODEL ReuseTest (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Item =
            value : MANDATORY 0 .. 100;
            CONSTRAINT ValueUsuallyHigh: >= 80% value >= 10;
          END Item;
        END Data;
      END ReuseTest.
      """;

  private static final String MODEL_WITHOUT_CONSTRAINT = """
      INTERLIS 2.4;

      MODEL ReuseTest (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Item =
            value : MANDATORY 0 .. 100;
          END Item;
        END Data;
      END ReuseTest.
      """;

  @Test
  void automaticCaseGenerationCompilesModelExactlyOnce() {
    CountingCompiler compiler = new CountingCompiler();
    ConstraintCaseGenerationTools tools = caseTools(compiler);

    Map<String, Object> result = tools.generateIliConstraintCases(
        MODEL_WITH_CONSTRAINT,
        "ValueAtLeast10",
        null);

    assertThat(result.get("generationVerified")).isEqualTo(true);
    assertThat(compiler.calls).isEqualTo(1);
  }

  @Test
  void plausibilityCaseGenerationCompilesModelExactlyOnce() {
    CountingCompiler compiler = new CountingCompiler();
    ConstraintCaseGenerationTools tools = caseTools(compiler);

    Map<String, Object> result = tools.generateIliConstraintCases(
        MODEL_WITH_PLAUSIBILITY,
        "ValueUsuallyHigh",
        null);

    assertThat(result.get("generationVerified")).isEqualTo(true);
    assertThat(result.get("pattern")).isEqualTo("PLAUSIBILITY_POPULATION_PROOF");
    assertThat(compiler.calls).isEqualTo(1);
  }

  @Test
  void mandatoryAuthoringCompilesBeforeAndAfterExactlyOnce() {
    CountingCompiler compiler = new CountingCompiler();
    ConstraintCaseGenerationTools cases = caseTools(compiler);
    ConstraintAuthoringTools tools = new ConstraintAuthoringTools(compiler, cases);

    ConstraintAuthoringTools.ExpressionNode attribute = node("value", "ATTRIBUTE");
    attribute.name = "value";
    ConstraintAuthoringTools.ExpressionNode threshold = node("threshold", "NUMERIC");
    threshold.value = 10;
    ConstraintAuthoringTools.ExpressionNode comparison = node("root", "COMPARE");
    comparison.operator = ">=";
    comparison.children = List.of("value", "threshold");

    Map<String, Object> result = tools.authorIliMandatoryConstraint(
        MODEL_WITHOUT_CONSTRAINT,
        "ReuseTest.Data.Item",
        "ValueAtLeast10",
        "root",
        List.of(attribute, threshold, comparison),
        null);

    assertThat(result.get("generated")).isEqualTo(true);
    assertThat(result.get("proofVerified")).isEqualTo(true);
    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.get("updatedModelText").toString())
        .contains("CONSTRAINTS OF ReuseTest.Data.Item =")
        .contains("!!@ name = \"ValueAtLeast10\"")
        .contains("MANDATORY CONSTRAINT")
        .contains("END Data;");
    assertThat(result.get("sourceEdit")).isNotNull();
  }

  @Test
  void plausibilityAuthoringCompilesBeforeAndAfterExactlyOnce() {
    CountingCompiler compiler = new CountingCompiler();
    ConstraintCaseGenerationTools cases = caseTools(compiler);
    ConstraintAuthoringTools tools = new ConstraintAuthoringTools(compiler, cases);

    ConstraintAuthoringTools.ExpressionNode attribute = node("value", "ATTRIBUTE");
    attribute.name = "value";
    ConstraintAuthoringTools.ExpressionNode threshold = node("threshold", "NUMERIC");
    threshold.value = 10;
    ConstraintAuthoringTools.ExpressionNode comparison = node("root", "COMPARE");
    comparison.operator = ">=";
    comparison.children = List.of("value", "threshold");

    Map<String, Object> result = tools.authorIliPlausibilityConstraint(
        MODEL_WITHOUT_CONSTRAINT,
        "ReuseTest.Data.Item",
        "ValueUsuallyHigh",
        "AT_LEAST",
        new BigDecimal("80"),
        "root",
        List.of(attribute, threshold, comparison),
        null);

    assertThat(result.get("generated")).isEqualTo(true);
    assertThat(result.get("proofVerified")).isEqualTo(true);
    assertThat(result.get("direction")).isEqualTo("AT_LEAST");
    assertThat(result.get("percentage")).isEqualTo("80");
    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.get("updatedModelText").toString())
        .contains("CONSTRAINTS OF ReuseTest.Data.Item =")
        .contains("!!@ name = \"ValueUsuallyHigh\"")
        .contains("CONSTRAINT")
        .contains(">= 80%")
        .contains("END Data;");
    assertThat(result.get("sourceEdit")).isNotNull();
  }

  @Test
  void authoringPreservesCrLfAndExistingSourceOutsideInsertion() {
    CountingCompiler compiler = new CountingCompiler();
    ConstraintCaseGenerationTools cases = caseTools(compiler);
    ConstraintAuthoringTools tools = new ConstraintAuthoringTools(compiler, cases);
    String before = MODEL_WITHOUT_CONSTRAINT.replace("\n", "\r\n");

    ConstraintAuthoringTools.ExpressionNode attribute = node("value", "ATTRIBUTE");
    attribute.name = "value";
    ConstraintAuthoringTools.ExpressionNode threshold = node("threshold", "NUMERIC");
    threshold.value = 10;
    ConstraintAuthoringTools.ExpressionNode comparison = node("root", "COMPARE");
    comparison.operator = ">=";
    comparison.children = List.of("value", "threshold");

    Map<String, Object> result = tools.authorIliMandatoryConstraint(
        before,
        "ReuseTest.Data.Item",
        "ValueAtLeast10",
        "root",
        List.of(attribute, threshold, comparison),
        null);

    String updated = result.get("updatedModelText").toString();
    assertThat(updated).contains("\r\n    CONSTRAINTS OF ReuseTest.Data.Item =\r\n");
    assertThat(updated.replace("\r\n", "")).doesNotContain("\n");
    assertThat(updated).startsWith("INTERLIS 2.4;\r\n\r\nMODEL ReuseTest");
    assertThat(updated).endsWith("END ReuseTest.\r\n");
  }

  private ConstraintCaseGenerationTools caseTools(IliCompilerService compiler) {
    ConstraintKnowledgeTools knowledge = new ConstraintKnowledgeTools(
        new MathTools(), new TextTools(), compiler);
    ConstraintReviewTools review = new ConstraintReviewTools(compiler, knowledge);
    ConstraintTestTools tests = new ConstraintTestTools(compiler);
    return new ConstraintCaseGenerationTools(review, tests, compiler);
  }

  private ConstraintAuthoringTools.ExpressionNode node(String id, String kind) {
    ConstraintAuthoringTools.ExpressionNode node = new ConstraintAuthoringTools.ExpressionNode();
    node.id = id;
    node.kind = kind;
    return node;
  }

  private static class CountingCompiler extends IliCompilerService {
    private int calls;

    @Override
    public CompilationResult compile(
        String modelText,
        String modelRepositories,
        String tempPrefix) {
      calls++;
      return super.compile(modelText, modelRepositories, tempPrefix);
    }
  }
}
