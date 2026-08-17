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
    assertThat(response.get("rules")).asList().hasSize(14);
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
  void flagsMissingRequiredModelMetaAttribute() {
    Map<String, Object> response = tools.checkModelingRules(
        modelWithThreeHeaderMetaAttributes(),
        ModelPurpose.CAPTURE,
        null,
        List.of("MDE-060"),
        ModelingRuleProfile.CORE);

    assertThat(response.get("findings")).asList()
        .singleElement()
        .satisfies(finding -> assertThat(finding.toString())
            .contains("MDE-060")
            .contains("furtherInformation"));
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
  void flagsMissingModelVersion() {
    Map<String, Object> response = tools.checkModelingRules(
        modelWithoutVersion(),
        ModelPurpose.CAPTURE,
        null,
        List.of("MDE-208"),
        ModelingRuleProfile.CORE);

    assertThat(response.get("validForAutomatedRules")).isEqualTo(false);
    assertThat(response.get("findings")).asList()
        .singleElement()
        .satisfies(finding -> assertThat(finding.toString()).contains("MDE-208").contains("VERSION"));
  }

  @Test
  void flagsUndocumentedClassesAndAttributes() {
    Map<String, Object> response = tools.checkModelingRules(
        modelWithUndocumentedClassAndAttribute(),
        ModelPurpose.CAPTURE,
        null,
        List.of("MDE-209", "MDE-210"),
        ModelingRuleProfile.CORE);

    assertThat(response.get("validForAutomatedRules")).isEqualTo(false);
    assertThat(response.get("findings")).asList()
        .anySatisfy(finding -> assertThat(finding.toString()).contains("MDE-209").contains("Demo.Topic.Thing.name"))
        .anySatisfy(finding -> assertThat(finding.toString()).contains("MDE-210").contains("Demo.Topic.Thing"));
  }

  @Test
  void acceptsDocumentedClassAndAttribute() {
    Map<String, Object> response = tools.checkModelingRules(
        documentedModel(),
        ModelPurpose.CAPTURE,
        null,
        List.of("MDE-209", "MDE-210"),
        ModelingRuleProfile.CORE);

    assertThat(response.get("findings")).asList().isEmpty();
  }

  @Test
  void flagsNamesLongerThanTwentyNineCharacters() {
    Map<String, Object> response = tools.checkModelingRules(
        modelWithLongAttributeName(),
        ModelPurpose.CAPTURE,
        null,
        List.of("MDE-302"),
        ModelingRuleProfile.CORE);

    assertThat(response.get("validForAutomatedRules")).isEqualTo(true);
    assertThat(response.get("findings")).asList()
        .singleElement()
        .satisfies(finding -> assertThat(finding.toString()).contains("MDE-302").contains("characters"));
  }

  @Test
  void flagsUnboundedTextAndMtext() {
    Map<String, Object> response = tools.checkModelingRules(
        modelWithUnboundedText(),
        ModelPurpose.CAPTURE,
        null,
        List.of("MDE-502"),
        ModelingRuleProfile.CORE);

    assertThat(response.get("validForAutomatedRules")).isEqualTo(false);
    assertThat(response.get("findings")).asList()
        .anySatisfy(finding -> assertThat(finding.toString()).contains("MDE-502").contains("TEXT"))
        .anySatisfy(finding -> assertThat(finding.toString()).contains("MDE-502").contains("MTEXT"));
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

  private String modelWithThreeHeaderMetaAttributes() {
    return """
        INTERLIS 2.4;

        !!@ technicalContact="mailto:agi@example.org"
        !!@ title="Demo"
        !!@ shortDescription="Demo model"
        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
        END Demo.
        """;
  }

  private String modelWithoutVersion() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" =
        END Demo.
        """;
  }

  private String modelWithUndocumentedClassAndAttribute() {
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

  private String documentedModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          TOPIC Topic =
            /** Thing description */
            CLASS Thing =
              /** Name description */
              name : TEXT*20;
            END Thing;
          END Topic;
        END Demo.
        """;
  }

  private String modelWithLongAttributeName() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          TOPIC Topic =
            CLASS Thing =
              this_attribute_name_is_definitely_too_long : TEXT*20;
            END Thing;
          END Topic;
        END Demo.
        """;
  }

  private String modelWithUnboundedText() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          DOMAIN FreeText = TEXT;
          TOPIC Topic =
            CLASS Thing =
              note : MTEXT;
            END Thing;
          END Topic;
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
