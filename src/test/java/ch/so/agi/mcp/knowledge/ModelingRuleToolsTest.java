package ch.so.agi.mcp.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ModelingRuleToolsTest {

  private final CountingCompilerService compilerService = new CountingCompilerService();
  private final ModelAnalysisTools analysisTools = new ModelAnalysisTools(compilerService);
  private final ModelingRuleTools tools = new ModelingRuleTools(
      new KnowledgeRuleLoader(),
      analysisTools,
      compilerService);

  @Test
  void listsCuratedRules() {
    Map<String, Object> response = tools.listModelingRules(null);

    assertThat(response.get("profile")).isEqualTo("CORE");
    assertThat(response.get("rules")).asList().hasSize(9);
  }

  @Test
  void flagsAssociationsInPublicationModels() {
    Map<String, Object> response = tools.checkModelingRules(
        publicationModelWithAssociation(),
        ModelPurpose.PUBLICATION,
        null,
        List.of("MDE-010"),
        null);

    assertThat(response.get("validForAutomatedRules")).isEqualTo(false);
    assertThat(response.get("findings")).asList()
        .anySatisfy(finding -> assertThat(finding.toString()).contains("MDE-010"));
  }

  @Test
  void exposesManualChecksSeparately() {
    Map<String, Object> response = tools.checkModelingRules(
        minimalModel(),
        ModelPurpose.CAPTURE,
        null,
        List.of("MDE-001", "MDE-050"),
        null);

    assertThat(response.get("findings")).asList().isEmpty();
    assertThat(response.get("manualChecks")).asList().hasSize(2);
  }

  @Test
  void flagsTabsInModelText() {
    String modelWithTab = """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
\tTOPIC Topic =
            CLASS Thing =
              name : TEXT*20;
            END Thing;
          END Topic;
        END Demo.
        """;

    Map<String, Object> response = tools.checkModelingRules(
        modelWithTab,
        ModelPurpose.CAPTURE,
        null,
        List.of("MDE-206"),
        ModelingRuleProfile.CORE);

    assertThat(response.get("validForAutomatedRules")).isEqualTo(false);
    assertThat(response.get("findings")).asList()
        .anySatisfy(finding -> assertThat(finding.toString()).contains("MDE-206").contains("line"));
  }

  @Test
  void reviewCompilesExactlyOnceAndCombinesResults() {
    Map<String, Object> response = tools.reviewIliModel(
        minimalModel(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.CORE,
        null);

    assertThat(compilerService.calls).isEqualTo(1);
    assertThat(response).containsKeys(
        "valid",
        "compilerValid",
        "validForAutomatedRules",
        "compilerDiagnostics",
        "structure",
        "ruleFindings",
        "manualChecks",
        "openQuestions");
    assertThat(response.get("compilerValid")).isEqualTo(true);
    assertThat(response.get("compilerDiagnostics")).asList().isEmpty();
    assertThat(response.get("structure")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsKeys("models", "topics", "classes", "domains", "attributes");
  }

  @Test
  void reviewAsksForPurposeWhenUnknown() {
    Map<String, Object> response = tools.reviewIliModel(
        minimalModel(),
        null,
        ModelingRuleProfile.CORE,
        null);

    assertThat(response.get("modelPurpose")).isEqualTo("UNKNOWN");
    assertThat(response.get("openQuestions")).asList()
        .anySatisfy(question -> assertThat(question.toString()).contains("MODEL_PURPOSE"));
  }

  private String minimalModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
        END Demo.
        """;
  }

  private String publicationModelWithAssociation() {
    return """
        INTERLIS 2.4;

        MODEL Pub (de) AT "https://example.org/pub" VERSION "2024-01-31" =
          TOPIC Topic =
            CLASS A =
            END A;
            CLASS B =
            END B;
            ASSOCIATION AB =
              a -- A;
              b -- B;
            END AB;
          END Topic;
        END Pub.
        """;
  }

  private static class CountingCompilerService extends IliCompilerService {
    int calls;

    @Override
    public CompilationResult compile(
        String modelText,
        @Nullable String modelRepositories,
        String tempPrefix) {
      calls++;
      return super.compile(modelText, modelRepositories, tempPrefix);
    }
  }
}
