package ch.so.agi.mcp.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.knowledge.KnowledgeRuleLoader;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelChangeToolsTest {

  @Test
  void detectsAddedRemovedAndChangedElements() {
    IliCompilerService compiler = new IliCompilerService();
    ModelChangeTools tools = changeTools(compiler);

    Map<String, Object> response = tools.reviewIliChange(beforeModel(), afterModel(), null, null);

    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("comparable")).isEqualTo(true);
    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("added")).asList()
        .anySatisfy(item -> assertThat(item.toString()).contains("Demo.Topic.Thing.code"));
    assertThat(response.get("removed")).asList()
        .anySatisfy(item -> assertThat(item.toString()).contains("Demo.Topic.Thing.obsolete"));
    assertThat(response.get("changed")).asList()
        .anySatisfy(item -> assertThat(item.toString())
            .contains("Demo.Topic.Thing.name")
            .contains("mandatory"));
    assertThat(response.get("potentiallyBreakingChanges")).asList().isNotEmpty();
  }

  @Test
  void reviewsAfterModelWithPurposeAndProfile() {
    IliCompilerService compiler = new IliCompilerService();
    ModelChangeTools tools = changeTools(compiler);

    Map<String, Object> response = tools.reviewIliChange(
        minimalModel(),
        minimalModelWithExtraBlankLines(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.SO);

    assertThat(response.get("afterReview")).isInstanceOfSatisfying(Map.class, review -> {
      assertThat(review.get("modelPurpose")).isEqualTo("CAPTURE");
      assertThat(review.get("ruleProfile")).isEqualTo("SO");
      assertThat(review.get("ruleFindings")).asList()
          .anySatisfy(item -> assertThat(item.toString()).contains("MDE-060"));
      assertThat(review.get("manualChecks")).asList().isNotEmpty();
      assertThat(review.get("openQuestions")).asList().isEmpty();
    });
  }

  @Test
  void detectsTextLengthChange() {
    IliCompilerService compiler = new IliCompilerService();
    ModelChangeTools tools = changeTools(compiler);

    Map<String, Object> response = tools.reviewIliChange(
        minimalModel(),
        minimalModel().replace("TEXT*20", "TEXT*50"),
        null,
        null);

    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("changed")).asList()
        .singleElement()
        .satisfies(change -> assertThat(change.toString())
            .contains("Demo.Topic.Thing.name")
            .contains("typeText")
            .contains("TEXT*20")
            .contains("TEXT*50"));
  }

  @Test
  void detectsNumericDomainRangeChange() {
    IliCompilerService compiler = new IliCompilerService();
    ModelChangeTools tools = changeTools(compiler);

    Map<String, Object> response = tools.reviewIliChange(
        numericDomainModel("10"),
        numericDomainModel("20"),
        null,
        null);

    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("changed")).asList()
        .singleElement()
        .satisfies(change -> assertThat(change.toString())
            .contains("Demo.Value")
            .contains("typeText")
            .contains("0..10")
            .contains("0..20"));
  }

  @Test
  void detectsEnumerationDomainValueChange() {
    IliCompilerService compiler = new IliCompilerService();
    ModelChangeTools tools = changeTools(compiler);

    Map<String, Object> response = tools.reviewIliChange(
        enumerationDomainModel("A, B"),
        enumerationDomainModel("A, B, C"),
        null,
        null);

    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("changed")).asList()
        .singleElement()
        .satisfies(change -> assertThat(change.toString())
            .contains("Demo.Status")
            .contains("typeText")
            .contains("ENUM|values=A,B")
            .contains("ENUM|values=A,B,C"));
  }

  @Test
  void detectsCoordinateDomainRangeChange() {
    IliCompilerService compiler = new IliCompilerService();
    ModelChangeTools tools = changeTools(compiler);

    Map<String, Object> response = tools.reviewIliChange(
        coordinateDomainModel("100"),
        coordinateDomainModel("200"),
        null,
        null);

    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("changed")).asList()
        .singleElement()
        .satisfies(change -> assertThat(change.toString())
            .contains("Demo.Point")
            .contains("typeText")
            .contains("0.000..100.000")
            .contains("0.000..200.000"));
  }

  @Test
  void detectsCoordinateDomainRotationChange() {
    IliCompilerService compiler = new IliCompilerService();
    ModelChangeTools tools = changeTools(compiler);

    Map<String, Object> response = tools.reviewIliChange(
        coordinateDomainRotationModel("2", "1"),
        coordinateDomainRotationModel("1", "2"),
        null,
        null);

    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("changed")).asList()
        .singleElement()
        .satisfies(change -> assertThat(change.toString())
            .contains("Demo.Point")
            .contains("typeText")
            .contains("rotation=2->1")
            .contains("rotation=1->2"));
  }

  @Test
  void ignoresFormattingOnlyChanges() {
    IliCompilerService compiler = new IliCompilerService();
    ModelChangeTools tools = changeTools(compiler);

    Map<String, Object> response = tools.reviewIliChange(
        minimalModel(), minimalModelWithExtraBlankLines(), null, null);

    assertThat(response.get("hasChanges")).isEqualTo(false);
    assertThat(response.get("impact")).isEqualTo("NONE");
    assertThat(response.get("added")).asList().isEmpty();
    assertThat(response.get("removed")).asList().isEmpty();
    assertThat(response.get("changed")).asList().isEmpty();
  }

  @Test
  void doesNotCompareWhenOneVersionDoesNotCompile() {
    IliCompilerService compiler = new IliCompilerService();
    ModelChangeTools tools = changeTools(compiler);

    Map<String, Object> response = tools.reviewIliChange(
        minimalModel(),
        "INTERLIS 2.4;\nMODEL Broken =\n",
        null,
        null);

    assertThat(response.get("valid")).isEqualTo(false);
    assertThat(response.get("comparable")).isEqualTo(false);
    assertThat(response.get("impact")).isEqualTo("UNKNOWN");
    assertThat(response.get("afterDiagnostics")).asList().isNotEmpty();
    assertThat(response.get("changed")).asList().isEmpty();
    assertThat(response.get("afterReview")).isInstanceOfSatisfying(Map.class, review ->
        assertThat(review.get("available")).isEqualTo(false));
  }

  @Test
  void compilesEachVersionExactlyOnceIncludingAfterReview() {
    CountingCompiler compiler = new CountingCompiler();
    ModelChangeTools tools = changeTools(compiler);

    Map<String, Object> response = tools.reviewIliChange(
        minimalModel(),
        minimalModelWithExtraBlankLines(),
        ModelPurpose.UNKNOWN,
        ModelingRuleProfile.CORE);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(response.get("afterReview")).isInstanceOfSatisfying(Map.class, review ->
        assertThat(review.get("openQuestions")).asList()
            .anySatisfy(item -> assertThat(item.toString()).contains("MODEL_PURPOSE")));
  }

  private ModelChangeTools changeTools(IliCompilerService compiler) {
    ModelAnalysisTools analysisTools = new ModelAnalysisTools(compiler);
    ModelingRuleTools ruleTools = new ModelingRuleTools(
        new KnowledgeRuleLoader(), analysisTools, compiler);
    return new ModelChangeTools(compiler, analysisTools, ruleTools);
  }

  private String beforeModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          TOPIC Topic =
            CLASS Thing =
              name : TEXT*20;
              obsolete : TEXT*10;
            END Thing;
          END Topic;
        END Demo.
        """;
  }

  private String afterModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =

          TOPIC Topic =
            CLASS Thing =
              name : MANDATORY TEXT*20;
              code : TEXT*10;
            END Thing;
          END Topic;
        END Demo.
        """;
  }

  private String minimalModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          TOPIC Topic =
            CLASS Thing =
              name : TEXT*20;
            END Thing;
          END Topic;
        END Demo.
        """;
  }

  private String minimalModelWithExtraBlankLines() {
    return """
        INTERLIS 2.4;


        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =

          TOPIC Topic =

            CLASS Thing =
              name : TEXT*20;
            END Thing;
          END Topic;
        END Demo.
        """;
  }

  private String numericDomainModel(String max) {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          DOMAIN
            Value = 0 .. %s;
        END Demo.
        """.formatted(max);
  }

  private String enumerationDomainModel(String values) {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          DOMAIN
            Status = (%s);
        END Demo.
        """.formatted(values);
  }

  private String coordinateDomainModel(String firstAxisMax) {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          DOMAIN
            Point = COORD
              0.000 .. %s.000,
              0.000 .. 100.000;
        END Demo.
        """.formatted(firstAxisMax);
  }

  private String coordinateDomainRotationModel(String nullAxis, String piHalfAxis) {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          DOMAIN
            Point = COORD
              0.000 .. 100.000,
              0.000 .. 100.000,
              ROTATION %s -> %s;
        END Demo.
        """.formatted(nullAxis, piHalfAxis);
  }

  private static class CountingCompiler extends IliCompilerService {
    private int calls;

    @Override
    public CompilationResult compile(String modelText, String modelRepositories, String tempPrefix) {
      calls++;
      return super.compile(modelText, modelRepositories, tempPrefix);
    }
  }
}
