package ch.so.agi.mcp.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnowledgeRuleLoaderTest {

  @Test
  void loadsCuratedRulesFromResource() {
    KnowledgeRuleLoader loader = new KnowledgeRuleLoader();

    assertThat(loader.rules()).hasSize(14);
    assertThat(loader.rules(ModelingRuleProfile.SO)).hasSize(14);
    assertThat(loader.rules()).extracting(ModelingRule::id)
        .containsExactly(
            "MDE-001",
            "MDE-010",
            "MDE-011",
            "MDE-020",
            "MDE-030",
            "MDE-040",
            "MDE-050",
            "MDE-060",
            "MDE-206",
            "MDE-208",
            "MDE-209",
            "MDE-210",
            "MDE-302",
            "MDE-502");
    assertThat(loader.rulesAsMarkdown())
        .contains("# Curated INTERLIS Modeling Rules")
        .contains("Active profile: `CORE`")
        .contains("MDE-020")
        .contains("ili2c")
        .contains("MDE-206")
        .contains("MDE-502");
  }
}
