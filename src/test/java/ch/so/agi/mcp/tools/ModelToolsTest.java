package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.so.agi.mcp.model.MetaAttributeSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelToolsTest {

  private final ModelTools tools = new ModelTools(
      Clock.fixed(Instant.parse("2024-05-01T12:00:00Z"), ZoneOffset.UTC));

  @Test
  void createsMinimalModelWithoutInventedMetadata() {
    Map<String, Object> result = tools.createModelSnippet(
        "TestModel", null, null, null, null, null, null, null);

    assertThat(result.get("iliSnippet").toString())
        .isEqualTo("""
            INTERLIS 2.4;

            MODEL TestModel (de) AT "https://example.org/testmodel" VERSION "2024-05-01" =

            END TestModel.
            """)
        .doesNotContain("technicalContact", "a title", "fubar");
  }

  @Test
  void trimsAndValidatesExplicitValues() {
    MetaAttributeSpec title = new MetaAttributeSpec();
    title.setName("title");
    title.setValue("Demo");

    Map<String, Object> result = tools.createModelSnippet(
        " DemoModel ",
        " en ",
        " https://data.example/demo ",
        " 2024-04-30 ",
        " 2.3 ",
        List.of("INTERLIS", "GeometryCHLV95_V1"),
        "Model documentation",
        List.of(title));

    assertThat(result.get("iliSnippet").toString())
        .contains("INTERLIS 2.3;")
        .contains("/** Model documentation */")
        .contains("!!@ title=\"Demo\"")
        .contains("MODEL DemoModel (en) AT \"https://data.example/demo\" VERSION \"2024-04-30\"")
        .contains("IMPORTS INTERLIS;", "IMPORTS GeometryCHLV95_V1;");
  }

  @Test
  void rejectsInvalidModelMetadata() {
    assertThatThrownBy(() -> tools.createModelSnippet(
        "Invalid-Model", null, null, null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.createModelSnippet(
        "Demo", "de", "relative/path", null, null, null, null, null))
        .hasMessageContaining("absolute URI");
    assertThatThrownBy(() -> tools.createModelSnippet(
        "Demo", "DE", null, null, null, null, null, null))
        .hasMessageContaining("language code");
    assertThatThrownBy(() -> tools.createModelSnippet(
        "Demo", null, null, "2024-02-31", null, null, null, null))
        .hasMessageContaining("YYYY-MM-DD");
    assertThatThrownBy(() -> tools.createModelSnippet(
        "Demo", null, null, null, "2.2", null, null, null))
        .hasMessageContaining("2.3");
    assertThatThrownBy(() -> tools.createModelSnippet(
        "Demo", null, null, null, null, List.of("INTERLIS", "INTERLIS"), null, null))
        .hasMessageContaining("Duplicate");
  }
}
