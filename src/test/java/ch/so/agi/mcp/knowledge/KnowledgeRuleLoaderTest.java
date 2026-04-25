package ch.so.agi.mcp.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnowledgeRuleLoaderTest {

  @Test
  void loadsCuratedRulesFromResource() {
    KnowledgeRuleLoader loader = new KnowledgeRuleLoader();

    assertThat(loader.rules()).hasSize(8);
    assertThat(loader.rules()).extracting(ModelingRule::id)
        .containsExactly("MDE-001", "MDE-010", "MDE-011", "MDE-020", "MDE-030", "MDE-040", "MDE-050", "MDE-060");
    assertThat(loader.rulesAsMarkdown())
        .contains("# Curated INTERLIS Modeling Rules")
        .contains("MDE-020")
        .contains("ili2c");
  }
}
