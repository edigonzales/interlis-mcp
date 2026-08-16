package ch.so.agi.mcp.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.so.agi.mcp.analysis.ModelPurpose;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelCorpusToolsTest {

  @TempDir
  Path tempDir;

  @Test
  void indexesConfiguredIliFiles() throws Exception {
    Files.writeString(tempDir.resolve("demo.ili"), modelText("DemoModel", "Building"));
    Files.writeString(tempDir.resolve("ignored.txt"), "not an ili file");
    ModelCorpusTools tools = new ModelCorpusTools(new ModelCorpusService(tempDir.toString(), 1_048_576, 10));

    Map<String, Object> response = tools.indexConfiguredModels();

    assertThat(response.get("indexedCount")).isEqualTo(1);
    assertThat(response.get("models").toString()).contains("DemoModel");
  }

  @Test
  void findsSimilarModelsByWeightedTerms() throws Exception {
    Files.writeString(tempDir.resolve("building.ili"), modelText("BuildingModel", "Building"));
    Files.writeString(tempDir.resolve("parcel.ili"), modelText("ParcelModel", "Parcel"));
    ModelCorpusTools tools = new ModelCorpusTools(new ModelCorpusService(tempDir.toString(), 1_048_576, 10));

    Map<String, Object> response = tools.findSimilarModels("building footprint", null, ModelPurpose.PUBLICATION, 5);

    assertThat(response.get("results")).asList().isNotEmpty();
    assertThat(response.get("results").toString()).contains("BuildingModel");
  }

  @Test
  @SuppressWarnings("unchecked")
  void readsFullModelFromSearchResultPath() throws Exception {
    String expected = modelText("BuildingModel", "Building");
    Files.writeString(tempDir.resolve("building.ili"), expected);
    ModelCorpusTools tools = new ModelCorpusTools(new ModelCorpusService(tempDir.toString(), 1_048_576, 10));

    Map<String, Object> search = tools.findSimilarModels("building", null, null, 1);
    Map<String, Object> hit = (Map<String, Object>) ((List<?>) search.get("results")).getFirst();
    Map<String, Object> response = tools.readModelExample(hit.get("path").toString());

    assertThat(response.get("modelName")).isEqualTo("BuildingModel");
    assertThat(response.get("path")).isEqualTo(tempDir.resolve("building.ili").toRealPath().toString());
    assertThat(response.get("modelText")).isEqualTo(expected);
    assertThat(((Number) response.get("sizeBytes")).longValue()).isGreaterThan(0);
  }

  @Test
  void rejectsExistingFileOutsideConfiguredCorpus() throws Exception {
    Path corpus = Files.createDirectory(tempDir.resolve("corpus"));
    Path outside = tempDir.resolve("outside.ili");
    Files.writeString(outside, modelText("OutsideModel", "Outside"));
    ModelCorpusTools tools = new ModelCorpusTools(new ModelCorpusService(corpus.toString(), 1_048_576, 10));

    assertThatThrownBy(() -> tools.readModelExample(outside.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outside the configured corpus");
  }

  private String modelText(String modelName, String className) {
    return """
        INTERLIS 2.4;

        MODEL %s (de) AT "https://example.org/%s" VERSION "2024-01-31" =
          TOPIC Topic =
            CLASS %s =
              name : TEXT*20;
            END %s;
          END Topic;
        END %s.
        """.formatted(modelName, modelName, className, className, modelName);
  }
}
