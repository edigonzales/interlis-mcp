package ch.so.agi.mcp.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.knowledge.KnowledgeRuleLoader;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticChangeSafetyTest {

  @Test
  void analyzesConstraintWithCanonicalIli2cDefinition() {
    ModelAnalysisTools tools = new ModelAnalysisTools(new IliCompilerService());

    Map<String, Object> response = tools.analyzeIliModel(constraintModel(">"), null);

    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("constraints")).asList()
        .singleElement()
        .satisfies(constraint -> assertThat(constraint.toString())
            .contains("MANDATORY_CONSTRAINT")
            .contains("definitionText")
            .contains("MANDATORY CONSTRAINT value > 0;"));
  }

  @Test
  void constraintConditionChangeIsPotentiallyBreaking() {
    ModelChangeTools tools = changeTools();

    Map<String, Object> response = tools.reviewIliChange(
        constraintModel(">"), constraintModel(">="), null, null);

    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("changed")).asList()
        .anySatisfy(change -> assertThat(change.toString())
            .contains("MANDATORY_CONSTRAINT")
            .contains("definitionText")
            .contains("value > 0")
            .contains("value >= 0"));
    assertThat(response.get("potentiallyBreakingChanges")).asList()
        .anySatisfy(change -> assertThat(change.toString())
            .contains("MANDATORY_CONSTRAINT")
            .contains("domain review"));
  }

  @Test
  void associationRoleCardinalityChangeIsPotentiallyBreaking() {
    ModelChangeTools tools = changeTools();

    Map<String, Object> response = tools.reviewIliChange(
        associationModel("{0..*}"), associationModel("{1..*}"), null, null);

    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("changed")).asList()
        .anySatisfy(change -> assertThat(change.toString())
            .contains("Demo.Topic.Link")
            .contains("roles")
            .contains("0..*")
            .contains("1..*"));
  }

  @Test
  void classInheritanceChangeIsPotentiallyBreaking() {
    ModelChangeTools tools = changeTools();

    Map<String, Object> response = tools.reviewIliChange(
        inheritanceModel("BaseA"), inheritanceModel("BaseB"), null, null);

    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("changed")).asList()
        .anySatisfy(change -> assertThat(change.toString())
            .contains("Demo.Topic.Child")
            .contains("extends")
            .contains("BaseA")
            .contains("BaseB"));
  }

  @Test
  void topicDependencyChangeIsPotentiallyBreaking() {
    ModelChangeTools tools = changeTools();

    Map<String, Object> response = tools.reviewIliChange(
        topicDependencyModel(false), topicDependencyModel(true), null, null);

    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("changed")).asList()
        .anySatisfy(change -> assertThat(change.toString())
            .contains("Demo.MainTopic")
            .contains("dependsOn")
            .contains("Demo.ReferenceTopic"));
  }

  @Test
  void viewDefinitionChangeIsVisibleAndPotentiallyBreaking() {
    ModelChangeTools tools = changeTools();

    Map<String, Object> response = tools.reviewIliChange(
        viewModel("value"), viewModel("name"), null, null);

    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("changed")).asList()
        .anySatisfy(change -> assertThat(change.toString())
            .contains("Demo.Topic.Things")
            .contains("definitionText"));
  }

  @Test
  void changingAttributeDomainAliasIsVisible() {
    ModelChangeTools tools = changeTools();

    Map<String, Object> response = tools.reviewIliChange(
        aliasedAttributeModel("CodeA"), aliasedAttributeModel("CodeB"), null, null);

    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("changed")).asList()
        .anySatisfy(change -> assertThat(change.toString())
            .contains("Demo.Topic.Thing.code")
            .contains("declaredTypeFqn")
            .contains("Demo.CodeA")
            .contains("Demo.CodeB"));
  }

  private ModelChangeTools changeTools() {
    IliCompilerService compiler = new IliCompilerService();
    ModelAnalysisTools analysis = new ModelAnalysisTools(compiler);
    ModelingRuleTools rules = new ModelingRuleTools(new KnowledgeRuleLoader(), analysis, compiler);
    return new ModelChangeTools(compiler, analysis, rules);
  }

  private String constraintModel(String operator) {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-17" =
          TOPIC Topic =
            CLASS Thing =
              value : MANDATORY 0..100;
              MANDATORY CONSTRAINT value %s 0;
            END Thing;
          END Topic;
        END Demo.
        """.formatted(operator);
  }

  private String associationModel(String cardinality) {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-17" =
          TOPIC Topic =
            CLASS Thing =
            END Thing;
            CLASS Other =
            END Other;
            ASSOCIATION Link =
              thing -- {1} Thing;
              others -- %s Other;
            END Link;
          END Topic;
        END Demo.
        """.formatted(cardinality);
  }

  private String inheritanceModel(String base) {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-17" =
          TOPIC Topic =
            CLASS BaseA =
            END BaseA;
            CLASS BaseB =
            END BaseB;
            CLASS Child EXTENDS %s =
            END Child;
          END Topic;
        END Demo.
        """.formatted(base);
  }

  private String topicDependencyModel(boolean dependsOn) {
    String dependency = dependsOn ? "    DEPENDS ON ReferenceTopic;\n" : "";
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-17" =
          TOPIC ReferenceTopic =
          END ReferenceTopic;
          TOPIC MainTopic =
        %s  END MainTopic;
        END Demo.
        """.formatted(dependency);
  }

  private String viewModel(String attribute) {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-17" =
          TOPIC Topic =
            CLASS Thing =
              value : TEXT*20;
              name : TEXT*20;
            END Thing;
            VIEW Things
              PROJECTION OF Thing;
              =
              %s := Thing->%s;
            END Things;
          END Topic;
        END Demo.
        """.formatted(attribute, attribute);
  }

  private String aliasedAttributeModel(String domain) {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-17" =
          DOMAIN
            CodeA = TEXT*20;
            CodeB = TEXT*20;
          TOPIC Topic =
            CLASS Thing =
              code : %s;
            END Thing;
          END Topic;
        END Demo.
        """.formatted(domain);
  }
}
