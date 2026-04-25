package ch.so.agi.mcp.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.service.IliCompilerService;
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
    assertThat(response.get("attributes")).asList().hasSize(1);
    assertThat(response.get("summaryMarkdown").toString()).contains("modelPurpose: CAPTURE");
  }

  @Test
  void returnsPartialAnalysisForCompilerErrors() {
    Map<String, Object> response = tools.analyzeIliModel("INTERLIS 2.4;\nMODEL Broken =\n", null, ModelPurpose.UNKNOWN);

    assertThat(response.get("valid")).isEqualTo(false);
    assertThat(response.get("messages")).asList().isNotEmpty();
    assertThat(response.get("iliVersion")).isEqualTo("2.4");
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
}
