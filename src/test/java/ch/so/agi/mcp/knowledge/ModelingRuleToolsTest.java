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
  void flagsAssociationsInPublicationModels() {
    Map<String, Object> response = tools.reviewIliModel(
        publicationModelWithAssociation(),
        ModelPurpose.PUBLICATION,
        ModelingRuleProfile.SO_GIS);

    assertThat(response.get("validForAutomatedRules")).isEqualTo(false);
    assertThat(response.get("ruleFindings")).asList()
        .anySatisfy(finding -> assertThat(finding.toString()).contains("MDE-010"));
  }

  @Test
  void exposesManualChecksSeparately() {
    Map<String, Object> response = tools.reviewIliModel(
        minimalModel(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.SO_GIS);

    assertThat(response.get("manualChecks").toString()).contains("MDE-001", "MDE-050", "MDE-602");
    assertThat(response.get("ruleFindings").toString()).doesNotContain("MDE-001", "MDE-050", "MDE-602");
  }

  @Test
  void flagsMissingRequiredModelMetaAttribute() {
    Map<String, Object> response = tools.reviewIliModel(
        modelWithThreeHeaderMetaAttributes(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.SO_GIS);

    assertThat(response.get("ruleFindings")).asList()
        .anySatisfy(finding -> assertThat(finding.toString())
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

    Map<String, Object> response = tools.reviewIliModel(
        modelWithTab,
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.SO_GIS);

    assertThat(response.get("validForAutomatedRules")).isEqualTo(false);
    assertThat(response.get("ruleFindings")).asList()
        .anySatisfy(finding -> assertThat(finding.toString()).contains("MDE-206").contains("line"));
  }

  @Test
  void flagsMissingModelVersion() {
    Map<String, Object> response = tools.reviewIliModel(
        modelWithoutVersion(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.SO_GIS);

    assertThat(response.get("validForAutomatedRules")).isEqualTo(false);
    assertThat(response.get("ruleFindings")).asList()
        .anySatisfy(finding -> assertThat(finding.toString()).contains("MDE-208").contains("VERSION"));
  }

  @Test
  void flagsUndocumentedClassesAndAttributes() {
    Map<String, Object> response = tools.reviewIliModel(
        modelWithUndocumentedClassAndAttribute(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.SO_GIS);

    assertThat(response.get("validForAutomatedRules")).isEqualTo(false);
    assertThat(response.get("ruleFindings")).asList()
        .anySatisfy(finding -> assertThat(finding.toString()).contains("MDE-209").contains("Demo.Topic.Thing.name"))
        .anySatisfy(finding -> assertThat(finding.toString()).contains("MDE-210").contains("Demo.Topic.Thing"));
  }

  @Test
  void acceptsDocumentedClassAndAttribute() {
    Map<String, Object> response = tools.reviewIliModel(
        documentedModel(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.SO_GIS);

    assertThat(response.get("ruleFindings").toString()).doesNotContain("MDE-209", "MDE-210");
  }

  @Test
  void flagsNamesLongerThanTwentyNineCharacters() {
    Map<String, Object> response = tools.reviewIliModel(
        modelWithLongAttributeName(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.SO_GIS);

    assertThat(response.get("ruleFindings")).asList()
        .anySatisfy(finding -> assertThat(finding.toString()).contains("MDE-302").contains("characters"));
  }

  @Test
  void flagsViewsOutsideValidationModels() {
    Map<String, Object> analysis = Map.of(
        "valid", true,
        "views", List.of(Map.of(
            "kind", "VIEW",
            "name", "Derived",
            "scopedName", "Demo.Topic.Derived")));

    Map<String, Object> captureReview = tools.reviewAnalyzedModel(
        "", ModelPurpose.CAPTURE, ModelingRuleProfile.SO_GIS, analysis);
    Map<String, Object> validationReview = tools.reviewAnalyzedModel(
        "", ModelPurpose.VALIDATION, ModelingRuleProfile.SO_GIS, analysis);
    Map<String, Object> unknownReview = tools.reviewAnalyzedModel(
        "", ModelPurpose.UNKNOWN, ModelingRuleProfile.SO_GIS, analysis);

    assertThat(captureReview.get("ruleFindings")).asList()
        .anySatisfy(finding -> assertThat(finding.toString()).contains("MDE-501").contains("Demo.Topic.Derived"));
    assertThat(validationReview.get("ruleFindings").toString()).doesNotContain("MDE-501");
    assertThat(unknownReview.get("ruleFindings").toString()).doesNotContain("MDE-501");
  }

  @Test
  void flagsUnboundedTextAndMtext() {
    Map<String, Object> response = tools.reviewIliModel(
        modelWithUnboundedText(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.SO_GIS);

    assertThat(response.get("validForAutomatedRules")).isEqualTo(false);
    assertThat(response.get("ruleFindings")).asList()
        .anySatisfy(finding -> assertThat(finding.toString()).contains("MDE-502").contains("TEXT"))
        .anySatisfy(finding -> assertThat(finding.toString()).contains("MDE-502").contains("MTEXT"));
  }

  @Test
  void flagsImplicitRoleCardinalities() {
    Map<String, Object> response = tools.reviewIliModel(
        publicationModelWithAssociation(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.SO_GIS);

    assertThat(response.get("validForAutomatedRules")).isEqualTo(false);
    assertThat((List<?>) response.get("ruleFindings"))
        .filteredOn(finding -> finding.toString().contains("MDE-601")).hasSize(2)
        .allSatisfy(finding -> assertThat(finding.toString()).contains("MDE-601").contains("{1}"));
  }

  @Test
  void acceptsExplicitRoleCardinalities() {
    Map<String, Object> response = tools.reviewIliModel(
        modelWithExplicitRoleCardinalities(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.SO_GIS);

    assertThat(response.get("ruleFindings").toString()).doesNotContain("MDE-601");
  }

  @Test
  void flagsConcreteClassWithoutEffectiveOid() {
    Map<String, Object> response = tools.reviewIliModel(
        modelWithConcreteClassWithoutOid(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.SO_GIS);

    assertThat(response.get("validForAutomatedRules")).isEqualTo(false);
    assertThat(response.get("ruleFindings")).asList()
        .anySatisfy(finding -> assertThat(finding.toString()).contains("MDE-603").contains("Demo.Topic.Thing"));
  }

  @Test
  void acceptsTopicOidForConcreteClassAndSkipsAbstractClass() {
    Map<String, Object> withTopicOid = tools.reviewIliModel(
        modelWithTopicOid(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.SO_GIS);
    Map<String, Object> abstractClass = tools.reviewIliModel(
        modelWithAbstractClassWithoutOid(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.SO_GIS);

    assertThat(withTopicOid.get("ruleFindings").toString()).doesNotContain("MDE-603");
    assertThat(abstractClass.get("ruleFindings").toString()).doesNotContain("MDE-603");
  }

  @Test
  void reviewCompilesExactlyOnceAndCombinesResults() {
    Map<String, Object> response = tools.reviewIliModel(
        minimalModel(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.CORE);

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
        .containsKeys("models", "topics", "classes", "views", "domains", "attributes");
  }

  @Test
  void reviewAsksForPurposeWhenUnknown() {
    Map<String, Object> response = tools.reviewIliModel(
        minimalModel(),
        null,
        ModelingRuleProfile.CORE);

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

  private String modelWithExplicitRoleCardinalities() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          TOPIC Topic =
            CLASS A =
            END A;
            CLASS B =
            END B;
            ASSOCIATION AB =
              a -- {1} A;
              b -- {0..*} B;
            END AB;
          END Topic;
        END Demo.
        """;
  }

  private String modelWithConcreteClassWithoutOid() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          TOPIC Topic =
            CLASS Thing =
            END Thing;
          END Topic;
        END Demo.
        """;
  }

  private String modelWithTopicOid() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          TOPIC Topic =
            OID AS INTERLIS.UUIDOID;
            CLASS Thing =
            END Thing;
          END Topic;
        END Demo.
        """;
  }

  private String modelWithAbstractClassWithoutOid() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          TOPIC Topic =
            CLASS Thing (ABSTRACT) =
            END Thing;
          END Topic;
        END Demo.
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
