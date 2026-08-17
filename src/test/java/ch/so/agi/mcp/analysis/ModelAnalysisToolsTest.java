package ch.so.agi.mcp.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelAnalysisToolsTest {

  private final ModelAnalysisTools tools = new ModelAnalysisTools(new IliCompilerService());

  @Test
  void analyzesCompiledModelStructure() {
    Map<String, Object> response = tools.analyzeIliModel(model(), null, ModelPurpose.CAPTURE);

    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("iliVersion")).isEqualTo("2.4");
    assertThat(response.get("models")).asList().hasSize(1);
    assertThat(response.get("topics")).asList().hasSize(1);
    assertThat(response.get("classes")).asList().hasSize(1);
    assertThat(response.get("attributes")).asList()
        .singleElement()
        .satisfies(attribute -> assertThat(attribute.toString()).contains("typeText=TEXT*20"));
    assertThat(response.get("summaryMarkdown").toString()).contains("modelPurpose: CAPTURE");
  }

  @Test
  void exposesModelVersionAndElementDocumentation() {
    Map<String, Object> response = tools.analyzeIliModel(documentedModel(), null, ModelPurpose.CAPTURE);

    assertThat(element(response, "models", "Demo")).containsEntry("version", "2026-08-17");
    assertThat(element(response, "classes", "Demo.Topic.Thing"))
        .containsEntry("documentation", "Thing description");
    assertThat(element(response, "attributes", "Demo.Topic.Thing.name"))
        .containsEntry("documentation", "Name description");
  }

  @Test
  void exposesSemanticRelationshipsForAgenticUnderstanding() {
    Map<String, Object> response = tools.analyzeIliModel(semanticModel(), null, ModelPurpose.CAPTURE);

    assertThat(response.get("valid")).isEqualTo(true);

    Map<String, Object> baseTopic = element(response, "topics", "Demo.BaseTopic");
    assertThat(baseTopic).containsEntry("abstract", true).containsEntry("final", false);

    Map<String, Object> mainTopic = element(response, "topics", "Demo.MainTopic");
    assertThat(mainTopic)
        .containsEntry("extends", "Demo.BaseTopic")
        .containsEntry("dependsOn", List.of("Demo.ReferenceTopic"));

    Map<String, Object> thing = element(response, "classes", "Demo.MainTopic.Thing");
    assertThat(thing).containsEntry("extends", "Demo.MainTopic.BaseThing");
    assertThat(element(response, "classes", "Demo.MainTopic.Leaf")).containsEntry("final", true);

    Map<String, Object> part = element(response, "structures", "Demo.MainTopic.Part");
    assertThat(part).containsEntry("extends", "Demo.MainTopic.BasePart");
    assertThat(element(response, "structures", "Demo.MainTopic.LeafPart")).containsEntry("final", true);

    assertThat(element(response, "domains", "Demo.BaseCode")).containsEntry("abstract", true);
    assertThat(element(response, "domains", "Demo.Code")).containsEntry("extends", "Demo.BaseCode");
    assertThat(element(response, "domains", "Demo.FinalCode")).containsEntry("final", true);

    Map<String, Object> association = element(response, "associations", "Demo.MainTopic.Link");
    assertThat(association).containsEntry("final", true);
    assertThat(association.get("roles")).asList()
        .anySatisfy(role -> assertThat(role.toString())
            .contains("name=thing")
            .contains("roleKind=ASSOCIATE")
            .contains("cardinality={1}")
            .contains("target=Demo.MainTopic.Thing")
            .contains("external=true"))
        .anySatisfy(role -> assertThat(role.toString())
            .contains("name=others")
            .contains("cardinality={0..*}")
            .contains("target=Demo.MainTopic.Other")
            .contains("external=false"));
  }

  @Test
  void exposesUnitsInResponseSummaryAndLexicalTerms() {
    ModelAnalysisTools.AnalysisData data = new ModelAnalysisTools.AnalysisData();
    data.units.add(Map.of(
        "kind", "UNIT",
        "name", "Meter",
        "scopedName", "Demo.Meter"));

    Map<String, Object> response = tools.toResponse(true, List.of(), data, ModelPurpose.CAPTURE);

    assertThat(response.get("units")).asList().hasSize(1);
    assertThat(response.get("summaryMarkdown").toString()).contains("units: 1");
    assertThat(tools.lexicalTerms(response)).contains("meter", "demo");
  }

  @Test
  void returnsPartialAnalysisForCompilerErrors() {
    Map<String, Object> response = tools.analyzeIliModel("INTERLIS 2.4;\nMODEL Broken =\n", null, ModelPurpose.UNKNOWN);

    assertThat(response.get("valid")).isEqualTo(false);
    assertThat(response.get("messages")).asList().isNotEmpty();
    assertThat(response.get("iliVersion")).isEqualTo("2.4");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> element(Map<String, Object> response, String category, String scopedName) {
    return ((List<Map<String, Object>>) response.get(category)).stream()
        .filter(item -> scopedName.equals(item.get("scopedName")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing " + category + " element " + scopedName));
  }

  private String model() {
    return """
        INTERLIS 2.4;

        !!@ title="Demo"
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

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-17" =
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

  private String semanticModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          DOMAIN
            BaseCode (ABSTRACT) = TEXT*20;
            Code EXTENDS BaseCode = TEXT*20;
            FinalCode (FINAL) = TEXT*10;

          TOPIC ReferenceTopic =
          END ReferenceTopic;

          TOPIC BaseTopic (ABSTRACT) =
          END BaseTopic;

          TOPIC MainTopic EXTENDS BaseTopic =
            DEPENDS ON ReferenceTopic;

            CLASS BaseThing (ABSTRACT) =
            END BaseThing;

            CLASS Thing EXTENDS BaseThing =
            END Thing;

            CLASS Leaf (FINAL) =
            END Leaf;

            STRUCTURE BasePart (ABSTRACT) =
            END BasePart;

            STRUCTURE Part EXTENDS BasePart =
            END Part;

            STRUCTURE LeafPart (FINAL) =
            END LeafPart;

            CLASS Other =
            END Other;

            ASSOCIATION Link (FINAL) =
              thing (EXTERNAL) -- {1} Thing;
              others -- {0..*} Other;
            END Link;
          END MainTopic;
        END Demo.
        """;
  }
}
