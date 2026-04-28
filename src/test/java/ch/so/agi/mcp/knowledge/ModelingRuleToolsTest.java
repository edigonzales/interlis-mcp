package ch.so.agi.mcp.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelingRuleToolsTest {

  private final ModelingRuleTools tools = new ModelingRuleTools(
      new KnowledgeRuleLoader(),
      new ModelAnalysisTools(new IliCompilerService()));

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
}
