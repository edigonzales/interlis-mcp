package ch.so.agi.mcp.constraint;

import static org.assertj.core.api.Assertions.assertThat;

import ch.interlis.ili2c.metamodel.Constraint;
import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.knowledge.KnowledgeRuleLoader;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.model.IliConstraintSpec;
import ch.so.agi.mcp.model.IliSpecRenderer;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.AttributeTools;
import ch.so.agi.mcp.tools.ConstraintAuthoringTools;
import ch.so.agi.mcp.tools.ConstraintCaseGenerationTools;
import ch.so.agi.mcp.tools.ConstraintTestTools;
import ch.so.agi.mcp.tools.DomainTools;
import ch.so.agi.mcp.tools.UniqueConstraintAuthoringTools;
import ch.so.agi.mcp.tools.SetConstraintAuthoringTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

/** Compiler regressions using frozen typed requests and the unchanged public v1 models.
 * These test insertion and lookup, not reconstruction quality or proof coverage.
 */
class ConstraintViewAuthoringRegressionTest {
  record ViewCase(String caseId, String contextFqn, IliConstraintSpec spec) {
    @Override public String toString() { return caseId; }
  }

  static Stream<ViewCase> cases() throws IOException {
    try (var input = ConstraintViewAuthoringRegressionTest.class.getResourceAsStream(
        "/constraint/view-authoring-cases.json")) {
      assertThat(input).isNotNull();
      return Arrays.stream(new ObjectMapper().readValue(input, ViewCase[].class));
    }
  }

  @ParameterizedTest(name = "{0}: view insertion compiles and resolves")
  @MethodSource("cases")
  void benchmarkConstraintCompilesInItsOriginalView(ViewCase fixture) throws IOException {
    String source = source(fixture);
    CountingCompiler compiler = new CountingCompiler();
    ConstraintAuthoringWorkflow workflow = new ConstraintAuthoringWorkflow(compiler);
    var before = workflow.compileBefore(source, "view_regression_before_");
    assertThat(before.valid()).as(before.messages().toString()).isTrue();
    LinkedHashSet<String> imports = new LinkedHashSet<>();
    var fragment = renderer().renderConstraint(fixture.spec(), "2.3",
        fixture.contextFqn().substring(0, fixture.contextFqn().indexOf('.')), imports);
    String constraintFqn = fixture.contextFqn() + "." + fixture.spec().name;
    assertThat(before.transferDescription().getElement(constraintFqn)).isNull();
    var prepared = workflow.insertAndResolve(source, before, fixture.contextFqn(),
        fragment.text(), constraintFqn, "view_regression_after_", imports);
    var after = prepared.resolution().compilation();
    assertThat(compiler.calls).isEqualTo(2);
    assertThat(after.valid()).as(after.messages().toString()).isTrue();
    var constraint = after.transferDescription().getElement(constraintFqn);
    assertThat(constraint).isInstanceOf(Constraint.class);
    assertThat(constraint.getContainer().getScopedName()).isEqualTo(fixture.contextFqn());
    // This regression isolates compiler insertion; complete proofs are asserted separately.
    ConstraintSourceEditServiceTest.assertOnlyInsertions(source, prepared.insertion());
  }

  @Test
  void externalAreaFunctionReachesItsDocumentedBoundaryThroughTheMandatoryTool() throws IOException {
    ViewCase fixture = cases().filter(value -> value.caseId().equals("N11")).findFirst().orElseThrow();
    CountingCompiler compiler = new CountingCompiler();
    ModelAnalysisTools analysis = new ModelAnalysisTools(compiler);
    ModelChangeReviewService review = new ModelChangeReviewService(analysis,
        new ModelingRuleTools(new KnowledgeRuleLoader(), analysis, compiler));
    var engine = new ConstraintAuthoringEngine(new ConstraintAuthoringWorkflow(compiler),
        renderer(), new ConstraintCaseGenerationTools(new ConstraintContextService(compiler),
            new ConstraintTestTools(compiler)), review);
    IliAuthoringResult result = new ConstraintAuthoringTools(engine).authorIliMandatoryConstraint(
        source(fixture), fixture.contextFqn(), (IliConstraintSpec.Mandatory) fixture.spec(), null, null);
    assertThat(compiler.calls).as(new ObjectMapper().writeValueAsString(result)).isEqualTo(2);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.EXTERNAL_FUNCTION_SEMANTICS_REQUIRED);
    assertThat(result.candidateModelText).isNotBlank();
    assertThat(result.updatedModelText).isNull();
    assertThat(result.generated).isFalse();
    assertThat(result.proofVerified).isFalse();
    var compiled = new IliCompilerService().compile(result.candidateModelText, null);
    assertThat(compiled.valid()).as(compiled.messages().toString()).isTrue();
  }

  static Stream<ViewCase> proofCases() throws IOException {
    return cases().filter(c -> java.util.Set.of("P04", "P05", "P07", "P08", "P10").contains(c.caseId()));
  }

  @ParameterizedTest(name = "{0}: complete View proof")
  @MethodSource("proofCases")
  void completeViewProof(ViewCase fixture) throws IOException {
    CountingCompiler compiler = new CountingCompiler();
    ModelAnalysisTools analysis = new ModelAnalysisTools(compiler);
    var engine = new ConstraintAuthoringEngine(new ConstraintAuthoringWorkflow(compiler), renderer(),
        new ConstraintCaseGenerationTools(new ConstraintContextService(compiler), new ConstraintTestTools(compiler)),
        new ModelChangeReviewService(analysis, new ModelingRuleTools(new KnowledgeRuleLoader(), analysis, compiler)));
    var result = switch (fixture.spec()) {
      case IliConstraintSpec.Mandatory spec -> new ConstraintAuthoringTools(engine).authorIliMandatoryConstraint(source(fixture), fixture.contextFqn(), spec, null, null);
      case IliConstraintSpec.Plausibility spec -> new ConstraintAuthoringTools(engine).authorIliPlausibilityConstraint(source(fixture), fixture.contextFqn(), spec, null, null);
      case IliConstraintSpec.Unique spec -> new UniqueConstraintAuthoringTools(engine).authorIliUniqueConstraint(source(fixture), fixture.contextFqn(), spec, null, null);
      case IliConstraintSpec.Set spec -> new SetConstraintAuthoringTools(engine).authorIliSetConstraint(source(fixture), fixture.contextFqn(), spec, null, null);
      default -> throw new AssertionError("Unexpected fixture kind");
    };
    assertThat(compiler.calls).as(new ObjectMapper().writeValueAsString(result)).isEqualTo(2);
    assertThat(result.status).as(result.toString()).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(result.proofVerified).isTrue();
    assertThat(result.constraintProofs.getFirst().coverageComplete).isTrue();
    assertThat(result.constraintProofs.getFirst().verification.allPassed).isTrue();
    var verified = result.constraintProofs.getFirst().verification.cases;
    assertThat(verified).allSatisfy(c -> {
      assertThat(c.viewFqn).isEqualTo(fixture.contextFqn());
      assertThat(c.plannedSubjectCount).isEqualTo(c.subjectCount);
      assertThat(c.fixtureValid).isTrue();
      assertThat(c.constraintExercised).isTrue();
    });
    if (fixture.caseId().equals("P08")) {
      assertThat(verified).anySatisfy(c -> { assertThat(c.name).isEqualTo("exactly key 2 differs"); assertThat(c.actualValid).isTrue(); });
      assertThat(verified).anySatisfy(c -> { assertThat(c.skippedFilterCount).isEqualTo(1); assertThat(c.subjectCount).isEqualTo(1); });
      assertThat(verified).anySatisfy(c -> assertThat(c.excludedSubjectCount).isEqualTo(1));
    }
    if (fixture.caseId().equals("P05")) {
      assertThat(verified).anySatisfy(c -> { assertThat(c.actualValid).isFalse(); assertThat(c.objectCounts).anySatisfy(count -> assertThat(count.get("actualCount")).isEqualTo(0)); });
      assertThat(verified).anySatisfy(c -> { assertThat(c.actualValid).isTrue(); assertThat(c.objectCounts).anySatisfy(count -> assertThat(count.get("actualCount")).isEqualTo(1)); });
      for(int alternative=1;alternative<=3;alternative++) {
        String expectedName="View filter 3 alternative "+alternative;
        assertThat(verified).anySatisfy(c -> assertThat(c.name).isEqualTo(expectedName));
      }
      assertThat(result.constraintProofs.getFirst().coverageExcludedGoals).anySatisfy(goal -> {
        assertThat(goal.reason).isEqualTo("object count 2 for Knoten_vonRef");
        assertThat(goal.reasonCode).isEqualTo("PROVEN_UNREACHABLE");
      });
    }
    if (fixture.caseId().equals("P10")) {
      assertThat(verified).anySatisfy(c -> { assertThat(c.subjectCount).isZero(); assertThat(c.setExecuted).isTrue(); assertThat(c.actualValid).isTrue(); });
      assertThat(verified).anySatisfy(c -> { assertThat(c.subjectCount).isEqualTo(2); assertThat(c.actualValid).isFalse(); });
    }
    if (fixture.caseId().equals("P07")) {
      assertThat(verified).anySatisfy(c -> { assertThat(c.subjectCount).isEqualTo(2); assertThat(c.actualValid).isTrue(); });
      assertThat(verified).anySatisfy(c -> { assertThat(c.subjectCount).isEqualTo(19); assertThat(c.actualValid).isFalse(); });
    }
  }

  private static String source(ViewCase fixture) throws IOException {
    return Files.readString(Path.of("evals/constraint-reconstruction/v1/public",
        fixture.caseId(), "model.ili"));
  }

  private static IliSpecRenderer renderer() {
    return new IliSpecRenderer(new AttributeTools(), new DomainTools());
  }

  private static class CountingCompiler extends IliCompilerService {
    private int calls;

    @Override
    public CompilationResult compile(String source, @Nullable String repositories, String prefix) {
      calls++;
      return super.compile(source, repositories, prefix);
    }
  }
}
