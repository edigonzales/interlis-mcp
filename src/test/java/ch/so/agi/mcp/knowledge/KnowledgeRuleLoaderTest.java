package ch.so.agi.mcp.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnowledgeRuleLoaderTest {

  @Test
  void separatesPortableCoreFromSolothurnRules() {
    KnowledgeRuleLoader loader = new KnowledgeRuleLoader();

    assertThat(loader.rules()).hasSize(2);
    assertThat(loader.rules()).extracting(ModelingRule::id)
        .containsExactly("MDE-020", "MDE-040");
    assertThat(loader.rules()).extracting(ModelingRule::profile)
        .containsOnly(ModelingRuleProfile.CORE);

    assertThat(loader.rules(ModelingRuleProfile.SO)).hasSize(18);
    assertThat(loader.rules(ModelingRuleProfile.SO)).extracting(ModelingRule::id)
        .containsExactly(
            "MDE-020",
            "MDE-040",
            "MDE-001",
            "MDE-010",
            "MDE-011",
            "MDE-030",
            "MDE-050",
            "MDE-060",
            "MDE-206",
            "MDE-208",
            "MDE-209",
            "MDE-210",
            "MDE-302",
            "MDE-501",
            "MDE-502",
            "MDE-601",
            "MDE-602",
            "MDE-603");
    assertThat(loader.rules(ModelingRuleProfile.SO))
        .filteredOn(rule -> rule.profile() == ModelingRuleProfile.SO)
        .hasSize(16);
  }

  @Test
  void markdownMakesProfileScopeVisible() {
    KnowledgeRuleLoader loader = new KnowledgeRuleLoader();

    assertThat(loader.rulesAsMarkdown())
        .contains("Aktives Profil: `CORE`")
        .contains("MDE-020")
        .contains("MDE-040")
        .doesNotContain("MDE-603");

    assertThat(loader.rulesAsMarkdown(ModelingRuleProfile.SO))
        .contains("Aktives Profil: `SO`")
        .contains("MDE-020")
        .contains("MDE-603")
        .contains("Profil: SO");
  }
}
