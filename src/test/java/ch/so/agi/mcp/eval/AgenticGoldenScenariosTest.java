package ch.so.agi.mcp.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelChangeTools;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.knowledge.AgentPrompts;
import ch.so.agi.mcp.knowledge.KnowledgeResources;
import ch.so.agi.mcp.knowledge.KnowledgeRuleLoader;
import ch.so.agi.mcp.knowledge.ModelCorpusService;
import ch.so.agi.mcp.knowledge.ModelCorpusTools;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.AssociationTools;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.model.IliModelSpec;
import ch.so.agi.mcp.model.IliSpecRenderer;
import ch.so.agi.mcp.tools.AttributeTools;
import ch.so.agi.mcp.tools.ConstraintCaseGenerationTools;
import ch.so.agi.mcp.tools.ConstraintTestTools;
import ch.so.agi.mcp.tools.DomainTools;
import ch.so.agi.mcp.tools.IliModelAuthoringTools;
import ch.so.agi.mcp.tools.ValidationTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgenticGoldenScenariosTest {

  @TempDir
  Path tempDir;

  @Test
  void newModelIsAuthoredCompiledAndReviewedByOneHighLevelTool() {
    CountingCompilerService compiler = new CountingCompilerService();
    ModelAnalysisTools analysis = new ModelAnalysisTools(compiler);
    ModelingRuleTools reviews = reviewTools(compiler, analysis);
    ConstraintContextService contexts = new ConstraintContextService(compiler);
    IliModelAuthoringTools authoring = new IliModelAuthoringTools(
        new IliSpecRenderer(new AttributeTools(), new DomainTools()),
        compiler,
        contexts,
        new ConstraintCaseGenerationTools(contexts, new ConstraintTestTools(compiler)),
        new ModelChangeReviewService(analysis, reviews));
    IliModelSpec spec = new IliModelSpec();
    spec.name = "Demo";
    spec.language = "de";
    spec.uri = "https://example.org/demo";
    spec.version = "2026-08-17";
    spec.iliVersion = "2.4";

    IliAuthoringResult result = authoring.authorIliModel(
        spec,
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.CORE);

    assertThat(compiler.calls).isEqualTo(1);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(result.updatedModelText).contains("MODEL Demo (de)");
    assertThat(result.afterReview.validForAutomatedRules).isEqualTo(true);
  }

  @Test
  void existingModelUsesChangeReviewAsFinalGate() {
    CountingCompilerService compiler = new CountingCompilerService();
    ModelAnalysisTools analysis = new ModelAnalysisTools(compiler);
    ModelingRuleTools reviews = reviewTools(compiler, analysis);
    ModelChangeTools changes = new ModelChangeTools(compiler, analysis, reviews);

    Map<String, Object> changeReview = changes.reviewIliChange(
        baseModel(),
        extendedModel(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.CORE);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(changeReview.get("comparable")).isEqualTo(true);
    assertThat(changeReview.get("afterCompilerValid")).isEqualTo(true);
    assertThat(changeReview.get("impact")).isEqualTo("ADDITIVE_OR_METADATA_ONLY");
    assertThat(changeReview.get("afterReview")).isInstanceOf(Map.class);
    assertThat(((Map<?, ?>) changeReview.get("afterReview")).get("validForAutomatedRules"))
        .isEqualTo(true);

    assertThat(compiler.calls).isEqualTo(2);
  }

  @Test
  void compilerRepairGetsSourceExcerpt() {
    ValidationTools validation = new ValidationTools(new IliCompilerService());
    String modelText = """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-17" =
          TOPIC Topic =
            CLASS Thing =
              name TEXT*20;
            END Thing;
          END Topic;
        END Demo.
        """;

    Map<String, Object> response = validation.validateIliModel(modelText);

    assertThat(response.get("valid")).isEqualTo(false);
    assertThat(response.get("messages")).asList()
        .anySatisfy(message -> assertThat(message.toString())
            .contains("sourceExcerpt")
            .contains("name TEXT*20;"));
  }

  @Test
  void missingCardinalityIsRejectedInsteadOfInvented() {
    AssociationTools tools = new AssociationTools();
    AssociationTools.Role left = role("left", "Demo.Topic.A", null);
    AssociationTools.Role right = role("right", "Demo.Topic.B", null);

    assertThatThrownBy(() -> tools.createAssociation(
        "AB", List.of(left, right), null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cardinality is required");
  }

  @Test
  @SuppressWarnings("unchecked")
  void exampleWorkflowSearchesThenReadsFullModel() throws Exception {
    String building = corpusModel("BuildingModel", "Building");
    Files.writeString(tempDir.resolve("building.ili"), building);
    Files.writeString(tempDir.resolve("parcel.ili"), corpusModel("ParcelModel", "Parcel"));
    ModelCorpusTools tools = new ModelCorpusTools(
        new ModelCorpusService(tempDir.toString(), 1_048_576, 10));

    Map<String, Object> search = tools.findSimilarModels(
        "building footprint", null, ModelPurpose.PUBLICATION, 1);
    Map<String, Object> hit = (Map<String, Object>) ((List<?>) search.get("results")).getFirst();
    Map<String, Object> example = tools.readModelExample(hit.get("path").toString());

    assertThat(hit.get("modelName")).isEqualTo("BuildingModel");
    assertThat(example.get("modelText")).isEqualTo(building);
  }

  @Test
  void breakingChangeIsSurfacedBeforeFinalizing() {
    CountingCompilerService compiler = new CountingCompilerService();
    ModelAnalysisTools analysis = new ModelAnalysisTools(compiler);
    ModelingRuleTools reviews = reviewTools(compiler, analysis);
    ModelChangeTools changes = new ModelChangeTools(compiler, analysis, reviews);

    Map<String, Object> response = changes.reviewIliChange(
        breakingBeforeModel(),
        breakingAfterModel(),
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.CORE);

    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("removed")).asList()
        .anySatisfy(item -> assertThat(item.toString()).contains("Demo.Topic.Thing.obsolete"));
    assertThat(response.get("changed")).asList()
        .anySatisfy(item -> assertThat(item.toString())
            .contains("Demo.Topic.Thing.name")
            .contains("mandatory"));
    assertThat(response.get("potentiallyBreakingChanges")).asList().isNotEmpty();
  }

  @Test
  void agentGuidanceRejectsRoutineLowLevelTripleCheck() {
    String toolGuide = new KnowledgeResources(null, null).toolGuide().toString();
    String agentPrompt = new AgentPrompts().interlisModelingAgent().toString();

    assertThat(toolGuide)
        .contains("reviewIliModel")
        .contains("nicht standardmaessig noch `validateIliModel`, `analyzeIliModel` und `checkModelingRules`");
    assertThat(agentPrompt)
        .contains("reviewIliModel")
        .contains("Low-Level-Tools");
  }

  @Test
  void agentGuidanceUsesSemanticChangeOrChangeReviewAsFinalGate() {
    String toolGuide = new KnowledgeResources(null, null).toolGuide().toString();
    String agentPrompt = new AgentPrompts().interlisModelingAgent().toString();

    assertThat(toolGuide)
        .contains("applyIliModelChanges")
        .contains("afterReview")
        .contains("kein zusaetzliches `reviewIliChange` oder `reviewIliModel`");
    assertThat(agentPrompt)
        .contains("applyIliModelChanges")
        .contains("afterReview")
        .contains("nicht routinemässig noch `reviewIliChange` oder `reviewIliModel`");
  }

  @Test
  void missingAssociationNamesAreNeverInvented() {
    AssociationTools tools = new AssociationTools();
    AssociationTools.Role left = role(null, "Demo.Topic.A", "{1}");
    AssociationTools.Role right = role(null, "Demo.Topic.B", "{0..*}");

    assertThatThrownBy(() -> tools.createAssociation(
        null, List.of(left, right), null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be invented");
  }

  private ModelingRuleTools reviewTools(CountingCompilerService compiler) {
    return reviewTools(compiler, new ModelAnalysisTools(compiler));
  }

  private ModelingRuleTools reviewTools(
      CountingCompilerService compiler,
      ModelAnalysisTools analysis) {
    return new ModelingRuleTools(new KnowledgeRuleLoader(), analysis, compiler);
  }

  private AssociationTools.Role role(
      @Nullable String name,
      String classFqn,
      @Nullable String cardinality) {
    AssociationTools.Role role = new AssociationTools.Role();
    role.name = name;
    role.classFQN = classFqn;
    role.card = cardinality;
    return role;
  }

  private String baseModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-17" =
          TOPIC Topic =
            OID AS INTERLIS.UUIDOID;
            /** Thing description */
            CLASS Thing =
              /** Name description */
              name : TEXT*20;
            END Thing;
          END Topic;
        END Demo.
        """;
  }

  private String extendedModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-17" =
          TOPIC Topic =
            OID AS INTERLIS.UUIDOID;
            /** Thing description */
            CLASS Thing =
              /** Name description */
              name : TEXT*20;
              /** Code description */
              code : TEXT*10;
            END Thing;
          END Topic;
        END Demo.
        """;
  }

  private String breakingBeforeModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-17" =
          TOPIC Topic =
            CLASS Thing =
              name : TEXT*20;
              obsolete : TEXT*10;
            END Thing;
          END Topic;
        END Demo.
        """;
  }

  private String breakingAfterModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-17" =
          TOPIC Topic =
            CLASS Thing =
              name : MANDATORY TEXT*20;
              code : TEXT*10;
            END Thing;
          END Topic;
        END Demo.
        """;
  }

  private String corpusModel(String modelName, String className) {
    return """
        INTERLIS 2.4;

        MODEL %s (de) AT "https://example.org/%s" VERSION "2026-08-17" =
          TOPIC Topic =
            CLASS %s =
            END %s;
          END Topic;
        END %s.
        """.formatted(modelName, modelName, className, className, modelName);
  }

  private static class CountingCompilerService extends IliCompilerService {
    int calls;

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
