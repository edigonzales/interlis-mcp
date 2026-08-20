package ch.so.agi.mcp.eval;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelChangeTools;
import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.knowledge.KnowledgeRuleLoader;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.ConstraintAuthoringTools;
import ch.so.agi.mcp.tools.ConstraintCaseGenerationTools;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ConstraintWorkflowGoldenScenariosTest {

  private static final String BASE_MODEL = """
      INTERLIS 2.4;

      MODEL ConstraintWorkflow (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Item =
            value : MANDATORY 0 .. 100;
            code : MANDATORY TEXT*20;
          END Item;
        END Data;
      END ConstraintWorkflow.
      """;

  private static final String UNIQUE_MODEL = """
      INTERLIS 2.4;

      MODEL ConstraintWorkflow (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Item =
            value : MANDATORY 0 .. 100;
            code : MANDATORY TEXT*20;

            !!@ name = "CodeUnique"
            UNIQUE code;
          END Item;
        END Data;
      END ConstraintWorkflow.
      """;

  @Test
  void typedConstraintAuthoringProofIsFollowedByExactlyOneModelChangeReview() {
    CountingCompiler compiler = new CountingCompiler();
    ConstraintCaseGenerationTools cases = new ConstraintCaseGenerationTools(
        compiler,
        new ConstraintContextService(compiler));
    ConstraintAuthoringTools authoring = new ConstraintAuthoringTools(compiler, cases);

    List<ConstraintAuthoringTools.ExpressionNode> nodes = List.of(
        node("value", "ATTRIBUTE", "value", null, null, null),
        node("minimum", "NUMERIC", null, null, 10, null),
        node("root", "COMPARE", null, ">=", null, List.of("value", "minimum")));

    Map<String, Object> authored = authoring.authorIliMandatoryConstraint(
        BASE_MODEL,
        "ConstraintWorkflow.Data.Item",
        "MinimumValue",
        "root",
        nodes,
        null);

    assertThat(authored.get("generated")).isEqualTo(true);
    assertThat(authored.get("proofVerified")).isEqualTo(true);
    assertThat(map(authored.get("proof")).get("generationVerified")).isEqualTo(true);
    assertThat(compiler.calls).isEqualTo(2);

    String updatedModelText = authored.get("updatedModelText").toString();
    ModelAnalysisTools analysis = new ModelAnalysisTools(compiler);
    ModelingRuleTools rules = new ModelingRuleTools(new KnowledgeRuleLoader(), analysis, compiler);
    ModelChangeTools changes = new ModelChangeTools(compiler, analysis, rules);

    Map<String, Object> review = changes.reviewIliChange(
        BASE_MODEL,
        updatedModelText,
        null,
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.CORE);

    assertThat(compiler.calls).isEqualTo(4);
    assertThat(review.get("comparable")).isEqualTo(true);
    assertThat(review.get("afterCompilerValid")).isEqualTo(true);
    assertThat(review.get("afterReview")).isInstanceOf(Map.class);
    assertThat(map(review.get("afterReview")).get("validForAutomatedRules")).isEqualTo(true);
  }

  @Test
  void uniqueFallbackUsesOneAutomaticProofThenExactlyOneModelChangeReview() {
    CountingCompiler compiler = new CountingCompiler();
    ConstraintCaseGenerationTools cases = new ConstraintCaseGenerationTools(
        compiler,
        new ConstraintContextService(compiler));

    Map<String, Object> proof = cases.generateIliConstraintCases(
        UNIQUE_MODEL,
        "CodeUnique",
        null);

    assertThat(proof.get("generationVerified")).isEqualTo(true);
    assertThat(proof.get("pattern")).isEqualTo("UNIQUE_SEMANTIC_PROOF");
    assertThat(compiler.calls).isEqualTo(1);

    ModelAnalysisTools analysis = new ModelAnalysisTools(compiler);
    ModelingRuleTools rules = new ModelingRuleTools(new KnowledgeRuleLoader(), analysis, compiler);
    ModelChangeTools changes = new ModelChangeTools(compiler, analysis, rules);

    Map<String, Object> review = changes.reviewIliChange(
        BASE_MODEL,
        UNIQUE_MODEL,
        null,
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.CORE);

    assertThat(compiler.calls).isEqualTo(3);
    assertThat(review.get("comparable")).isEqualTo(true);
    assertThat(review.get("afterCompilerValid")).isEqualTo(true);
    assertThat(review.get("afterReview")).isInstanceOf(Map.class);
  }

  private ConstraintAuthoringTools.ExpressionNode node(
      String id,
      String kind,
      @Nullable String name,
      @Nullable String operator,
      @Nullable Object value,
      @Nullable List<String> children) {
    ConstraintAuthoringTools.ExpressionNode node = new ConstraintAuthoringTools.ExpressionNode();
    node.id = id;
    node.kind = kind;
    node.name = name;
    node.operator = operator;
    node.value = value;
    node.children = children;
    return node;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static class CountingCompiler extends IliCompilerService {
    private int calls;

    @Override
    public CompilationResult compile(
        String modelText,
        @Nullable String modelRepositories,
        String tempPrefix) {
      calls++;
      return super.compile(modelText, modelRepositories, tempPrefix);
    }
  }
}
