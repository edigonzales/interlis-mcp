package ch.so.agi.mcp.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import ch.so.agi.mcp.analysis.*;
import ch.so.agi.mcp.change.IliComments;
import ch.so.agi.mcp.service.IliCompilerService;
import org.junit.jupiter.api.Test;
import java.util.List;

class SoDataTest {
  private final IliCompilerService compiler = new IliCompilerService();
  private final ModelingRuleTools tools = new ModelingRuleTools(new KnowledgeRuleLoader(), new ModelAnalysisTools(compiler), compiler);
  static String model() {
    return "INTERLIS 2.4; MODEL Data_20261010 (de) AT \"https://example.org/!!data\" VERSION \"2026-10-10\" = TOPIC Data = OID AS INTERLIS.ANYOID; CLASS Item = value : 0..100; END Item; END Data; END Data_20261010.";
  }
  @Test void profileRetainsRulesExceptTheThreeDocumentationRules() {
    var loader = new KnowledgeRuleLoader();
    var expected = loader.rules(ModelingRuleProfile.SO).stream().map(ModelingRule::id)
        .filter(id -> !List.of("MDE-060","MDE-209","MDE-210").contains(id)).toList();
    assertThat(loader.rules(ModelingRuleProfile.SO_DATA).stream().map(ModelingRule::id).toList())
        .containsAll(expected).contains("DATA-001","DATA-002").doesNotContain("MDE-060","MDE-209","MDE-210");
    assertThat(tools.reviewIliModel(model(), ModelPurpose.VALIDATION, ModelingRuleProfile.SO_DATA).get("valid")).isEqualTo(true);
    assertThat(tools.reviewIliModel(model(), ModelPurpose.VALIDATION, ModelingRuleProfile.SO).get("valid")).isEqualTo(false);
    assertThat(tools.reviewIliModel(model(), ModelPurpose.VALIDATION, ModelingRuleProfile.CORE).get("valid")).isEqualTo(true);
  }
  @Test void stringsAreNotCommentsButCommentsAreRejected() {
    assertThat(IliComments.spans("\"https://x/!!y/*z*/\" \"escaped \\\" !!\" !! line\n/* block */\n!!@ meta")).hasSize(3);
    var source = model().replace("CLASS Item", "!! documentation\nCLASS Item");
    assertThat(tools.reviewIliModel(source, ModelPurpose.VALIDATION, ModelingRuleProfile.SO_DATA).get("valid")).isEqualTo(false);
    assertThat(source).contains("!! documentation");
  }
  @Test void versionMustBeCalendarDateAndMatchSuffix() {
    for (String version : List.of("2026-10-11", "2026-02-30", "2026-1-1")) {
      var result = tools.reviewIliModel(model().replace("2026-10-10", version), ModelPurpose.VALIDATION, ModelingRuleProfile.SO_DATA);
      assertThat(result.get("valid")).isEqualTo(false);
    }
  }
  @Test void publicProfileSerializationUsesHyphen() throws Exception {
    var mapper = new tools.jackson.databind.ObjectMapper();
    assertThat(mapper.writeValueAsString(ModelingRuleProfile.SO_DATA)).isEqualTo("\"SO-DATA\"");
    assertThat(mapper.readValue("\"SO-DATA\"", ModelingRuleProfile.class)).isEqualTo(ModelingRuleProfile.SO_DATA);
  }
}
