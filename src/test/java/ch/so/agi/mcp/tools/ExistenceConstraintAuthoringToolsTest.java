package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.constraint.ConstraintAuthoringWorkflow;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExistenceConstraintAuthoringToolsTest {

  private static final String MODEL = """
      INTERLIS 2.4;

      MODEL ExistenceAuthor (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          CLASS TargetA =
            code : 0..10;
          END TargetA;

          CLASS TargetB =
            code : 0..10;
          END TargetB;

          CLASS Source =
            code : MANDATORY 0..10;
          END Source;
        END Data;
      END ExistenceAuthor.
      """;

  @Test
  void authorsSourcePreservingExistenceConstraintAndVerifiesProofWithTwoCompiles() {
    CountingCompiler compiler = new CountingCompiler();
    ConstraintContextService contextService = new ConstraintContextService(compiler);
    ConstraintCaseGenerationTools caseTools = new ConstraintCaseGenerationTools(
        contextService, new ConstraintTestTools(compiler));
    ExistenceConstraintAuthoringTools tools = new ExistenceConstraintAuthoringTools(
        caseTools,
        new ConstraintAuthoringWorkflow(compiler));

    ExistenceConstraintAuthoringTools.RequiredInTarget targetA = target(
        "ExistenceAuthor.Data.TargetA", "code");
    ExistenceConstraintAuthoringTools.RequiredInTarget targetB = target(
        "ExistenceAuthor.Data.TargetB", "code");

    Map<String, Object> result = tools.authorIliExistenceConstraint(
        MODEL,
        "ExistenceAuthor.Data.Source",
        "CodeExists",
        "code",
        List.of(targetA, targetB));

    assertThat(result.get("generated")).isEqualTo(true);
    assertThat(result.get("proofVerified")).isEqualTo(true);
    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.get("updatedModelText").toString())
        .contains("CONSTRAINTS OF ExistenceAuthor.Data.Source =")
        .contains("!!@ name = \"CodeExists\"")
        .contains("EXISTENCE CONSTRAINT")
        .contains("code REQUIRED IN")
        .contains("ExistenceAuthor.Data.TargetA : code")
        .contains("OR ExistenceAuthor.Data.TargetB : code")
        .contains("END ExistenceAuthor.");
    assertThat(map(result.get("proof")).get("pattern")).isEqualTo("EXISTENCE_SEMANTIC_PROOF");
    assertThat(map(result.get("semanticConstraint")).get("kind")).isEqualTo("EXISTENCE");
    assertThat(result).containsKeys("sourceEdit", "coverageGoalCount", "coverageSolvedCount", "coverageComplete");
  }

  @Test
  void rejectsMissingTargetAttributeBeforeAfterCompile() {
    CountingCompiler compiler = new CountingCompiler();
    ConstraintContextService contextService = new ConstraintContextService(compiler);
    ExistenceConstraintAuthoringTools tools = new ExistenceConstraintAuthoringTools(
        new ConstraintCaseGenerationTools(contextService, new ConstraintTestTools(compiler)),
        new ConstraintAuthoringWorkflow(compiler));

    Map<String, Object> result = tools.authorIliExistenceConstraint(
        MODEL,
        "ExistenceAuthor.Data.Source",
        "CodeExists",
        "code",
        List.of(target("ExistenceAuthor.Data.TargetA", "missing")));

    assertThat(result.get("generated")).isEqualTo(false);
    assertThat(result.get("reasonCode")).isEqualTo("EXISTENCE_PATH_RESOLUTION_FAILED");
    assertThat(compiler.calls).isEqualTo(1);
  }

  private ExistenceConstraintAuthoringTools.RequiredInTarget target(
      String viewableFqn,
      String attributePath) {
    ExistenceConstraintAuthoringTools.RequiredInTarget result =
        new ExistenceConstraintAuthoringTools.RequiredInTarget();
    result.viewableFqn = viewableFqn;
    result.attributePath = attributePath;
    return result;
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
        String modelRepositories,
        String tempPrefix) {
      calls++;
      return super.compile(modelText, modelRepositories, tempPrefix);
    }
  }
}
