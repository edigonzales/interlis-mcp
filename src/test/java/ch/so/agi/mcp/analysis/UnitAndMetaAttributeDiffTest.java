package ch.so.agi.mcp.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.knowledge.KnowledgeRuleLoader;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UnitAndMetaAttributeDiffTest {

  @Test
  void unitFactorChangeIsPotentiallyBreaking() {
    ModelChangeTools tools = changeTools();

    Map<String, Object> response = tools.reviewIliChange(
        unitModel("2"), unitModel("3"), null, null, null);

    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("changed")).asList()
        .singleElement()
        .satisfies(change -> assertThat(change.toString())
            .contains("Demo.Derived")
            .contains("definitionText")
            .contains("2 [Demo.Base]")
            .contains("3 [Demo.Base]"));
    assertThat(response.get("potentiallyBreakingChanges")).asList()
        .singleElement()
        .satisfies(change -> assertThat(change.toString())
            .contains("UNIT")
            .contains("unit definition"));
  }

  @Test
  void metaAttributeValueChangeIsReportedSeparately() {
    ModelChangeTools tools = changeTools();

    Map<String, Object> response = tools.reviewIliChange(
        modelWithMetaAttribute("Before"), modelWithMetaAttribute("After"), null, null, null);

    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("impact")).isEqualTo("ADDITIVE_OR_METADATA_ONLY");
    assertThat(response.get("changed")).asList()
        .singleElement()
        .satisfies(change -> assertThat(change.toString())
            .contains("META_ATTRIBUTE")
            .contains("Demo!!@title")
            .contains("value")
            .contains("Before")
            .contains("After"));
    assertThat(response.get("potentiallyBreakingChanges")).asList().isEmpty();
  }

  @Test
  void removingMetaAttributeIsMetadataOnly() {
    ModelChangeTools tools = changeTools();

    Map<String, Object> response = tools.reviewIliChange(
        modelWithMetaAttribute("Before"), modelWithoutMetaAttribute(), null, null, null);

    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("impact")).isEqualTo("ADDITIVE_OR_METADATA_ONLY");
    assertThat(response.get("removed")).asList()
        .singleElement()
        .satisfies(item -> assertThat(item.toString())
            .contains("META_ATTRIBUTE")
            .contains("title")
            .contains("Before"));
    assertThat(response.get("potentiallyBreakingChanges")).asList().isEmpty();
  }

  @Test
  void analysisExposesMetaAttributeOwnerAndValue() {
    ModelAnalysisTools tools = new ModelAnalysisTools(new IliCompilerService());

    Map<String, Object> response = tools.analyzeIliModel(modelWithMetaAttribute("Before"), null, null);

    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("metaAttributes")).asList()
        .singleElement()
        .satisfies(item -> assertThat(item.toString())
            .contains("META_ATTRIBUTE")
            .contains("owner=Demo")
            .contains("name=title")
            .contains("value=Before"));
  }

  private ModelChangeTools changeTools() {
    IliCompilerService compiler = new IliCompilerService();
    ModelAnalysisTools analysis = new ModelAnalysisTools(compiler);
    ModelingRuleTools rules = new ModelingRuleTools(new KnowledgeRuleLoader(), analysis, compiler);
    return new ModelChangeTools(compiler, analysis, rules);
  }

  private String unitModel(String factor) {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-17" =
          UNIT
            Base;
            Derived = %s [Base];
        END Demo.
        """.formatted(factor);
  }

  private String modelWithMetaAttribute(String value) {
    return """
        INTERLIS 2.4;

        !!@ title="%s"
        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-17" =
        END Demo.
        """.formatted(value);
  }

  private String modelWithoutMetaAttribute() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-17" =
        END Demo.
        """;
  }
}
