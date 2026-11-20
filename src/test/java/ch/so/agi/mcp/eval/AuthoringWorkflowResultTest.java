package ch.so.agi.mcp.eval;

import static org.assertj.core.api.Assertions.assertThat;
import ch.so.agi.mcp.analysis.*;
import ch.so.agi.mcp.constraint.*;
import ch.so.agi.mcp.knowledge.*;
import ch.so.agi.mcp.model.*;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthoringWorkflowResultTest {
  private final CountingCompiler compiler = new CountingCompiler();
  private final ModelAnalysisTools analysis = new ModelAnalysisTools(compiler);
  private final ModelingRuleTools reviews = new ModelingRuleTools(new KnowledgeRuleLoader(), analysis, compiler);
  private final ConstraintContextService contexts = new ConstraintContextService(compiler);
  private final ConstraintTestTools tests = new ConstraintTestTools(compiler);
  private final ConstraintCaseGenerationTools generation = new ConstraintCaseGenerationTools(contexts, tests);
  private final ModelChangeReviewService changes = new ModelChangeReviewService(analysis, reviews);
  private final IliSpecRenderer renderer = new IliSpecRenderer(new AttributeTools(), new DomainTools());
  private final String model = """
      INTERLIS 2.4;
      MODEL Evidence (en) AT "https://example.org" VERSION "1" =
        TOPIC Data =
          CLASS Person =
            age : MANDATORY 0..100;
          END Person;
        END Data;
      END Evidence.
      """;

  @Test void reviewsDistinguishWarningsCompilerFailureAndUnperformedTests() {
    Map<String,Object> reviewed = reviews.reviewIliModel(model, ModelPurpose.CAPTURE, ModelingRuleProfile.SO_GIS);
    assertThat(reviewed).containsEntry("compilerValid", true).containsEntry("validForAutomatedRules", false);
    assertThat((List<?>) reviewed.get("compilerDiagnostics")).isEmpty();
    assertThat((List<?>) reviewed.get("ruleFindings")).anySatisfy(f -> assertThat(((Map<?, ?>) f).get("severity")).isEqualTo("WARNING"));
    assertThat((Integer) reviewed.get("automatedRuleCount")).isPositive();
    var bad = reviews.reviewIliModel("INVALID", null, null);
    assertThat(bad).containsEntry("compilerValid", false);
    assertThat((List<?>) bad.get("compilerDiagnostics")).isNotEmpty();
    assertThat(((ConstraintInteractionAnalysis.Result) bad.get("constraintInteractions")).analyzed()).isFalse();
  }
  @Test void authoringWithoutConstraintsDoesNotClaimTestsAndInvalidSpecDoesNotClaimCompile() {
    var author = new IliModelAuthoringTools(renderer, compiler, contexts, generation, changes);
    var spec = new IliModelSpec(); spec.name = "Empty"; spec.iliVersion = "2.4";
    spec.uri = "https://example.org"; spec.version = "1";
    var result = author.authorIliModel(spec, ModelPurpose.CAPTURE, ModelingRuleProfile.CORE);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(result.constraintProofs).isEmpty();
    assertThat(result.afterCompilation.valid()).isTrue();
    var missingPurpose = author.authorIliModel(spec, null, ModelingRuleProfile.CORE);
    assertThat(missingPurpose.status).isEqualTo(IliAuthoringResult.Status.NEEDS_INPUT);
    assertThat(missingPurpose.constraintProofs).isEmpty();
    assertThat(missingPurpose.updatedModelText).isNull();
    assertThat(missingPurpose.openQuestions).singleElement().satisfies(q -> {
      assertThat(q.kind).isEqualTo("MODEL_PURPOSE");
      assertThat(q.decisionImpact).isEqualTo("BLOCKING");
    });
    spec.name = "123";
    var invalid = author.authorIliModel(spec, ModelPurpose.CAPTURE, ModelingRuleProfile.CORE);
    assertThat(invalid.status).isEqualTo(IliAuthoringResult.Status.INVALID_SPEC);
    assertThat(invalid.beforeCompilation).isNull();
    assertThat(invalid.afterCompilation).isNull();
    assertThat(invalid.constraintProofs).isNull();
    assertThat(invalid.updatedModelText).isNull();
  }
  @Test void successfulAutomaticProofDoesNotReplaceIndependentAgeBoundaryExpectations() {
    String wrong = model.replace("END Person;", "MANDATORY CONSTRAINT AgeRule: age > 18; END Person;");
    var automatic = generation.generateIliConstraintCases(wrong, "Evidence.Data.Person.AgeRule");
    assertThat(automatic.get("generationVerified")).isEqualTo(true);
    List<ConstraintTestTools.TestCase> cases = List.of(age(17, false), age(18, true), age(19, true));
    var explicit = tests.testIliConstraint(wrong, "Evidence.Data.Person.AgeRule", cases);
    assertThat(explicit.get("allPassed")).isEqualTo(false);
    assertThat(explicit.get("passedCount")).isEqualTo(2);
    assertThat((List<?>) explicit.get("cases")).anySatisfy(raw -> {
      var test = (Map<?, ?>) raw;
      assertThat(test.get("name")).isEqualTo("age18");
      assertThat(test.get("expectationSource")).isEqualTo(ConstraintTestTools.ExpectationSource.USER_PROVIDED.name());
      assertThat(test.get("constraintExercised")).isEqualTo(true);
      assertThat(test.get("fixtureValid")).isEqualTo(true);
      assertThat(test.get("targetViolationCount")).isEqualTo(1);
    });
    var corrected = tests.testIliConstraint(wrong.replace("age > 18", "age >= 18"), "Evidence.Data.Person.AgeRule", cases);
    assertThat(corrected.get("allPassed")).isEqualTo(true);
  }
  @Test void interactionFindingsAreAdvisoryAndEmbeddedWithoutAdditionalCompiles() {
    String before = model.replace("END Person;", "MANDATORY CONSTRAINT Young: age < 5; END Person;");
    var condition = new IliConstraintSpec.ExpressionSpec(); condition.kind = IliConstraintSpec.ExpressionKind.COMPARE; condition.operator = ">";
    var attribute = new IliConstraintSpec.ExpressionSpec(); attribute.kind = IliConstraintSpec.ExpressionKind.ATTRIBUTE; attribute.name = "age";
    var literal = new IliConstraintSpec.ExpressionSpec(); literal.kind = IliConstraintSpec.ExpressionKind.NUMERIC; literal.value = 18;
    condition.children = List.of(attribute, literal);
    var spec = new IliConstraintSpec.Mandatory(); spec.name = "Adult"; spec.condition = condition;
    var engine = new ConstraintAuthoringEngine(new ConstraintAuthoringWorkflow(compiler), renderer, generation, changes);
    int callsBefore = compiler.calls;
    var result = engine.author(before, "Evidence.Data.Person", spec, ModelPurpose.CAPTURE, ModelingRuleProfile.CORE);
    assertThat(compiler.calls - callsBefore).isEqualTo(2);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(result.updatedModelText).isNotNull();
    assertThat(result.openQuestions).isEmpty();
    assertThat(result.constraintProofs).singleElement().satisfies(proof -> {
      assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.PASSED);
      assertThat(proof.verification.cases).isNotEmpty();
    });
    assertThat(result.afterReview.constraintInteractions.contexts().getFirst().status())
        .isEqualTo(ConstraintInteractionAnalysis.Status.CONTRADICTION_PROVEN);
    var change = new ModelChangeTools(compiler, changes).reviewIliChange(before, result.updatedModelText, ModelPurpose.CAPTURE, ModelingRuleProfile.CORE);
    var afterReview = (Map<?, ?>) change.get("afterReview");
    assertThat(((ConstraintInteractionAnalysis.Result) afterReview.get("constraintInteractions")).contexts().getFirst().status())
        .isEqualTo(ConstraintInteractionAnalysis.Status.CONTRADICTION_PROVEN);
  }
  @Test void compilationFailureAndUnsupportedAutomaticCasesReportActualChecks() {
    var invalid = generation.generateIliConstraintCases("INVALID", "Missing");
    assertThat(invalid).containsEntry("compilerValid", false).doesNotContainKey("verification");
    assertThat((List<?>) invalid.get("compilerMessages")).isNotEmpty();
    var explicit = tests.testIliConstraint("INVALID", "Missing", List.of(age(18, true)));
    assertThat(explicit).containsEntry("compilerValid", false).containsEntry("tested", false);
    String orphan = "INTERLIS 2.4; MODEL Evidence (en) AT \"https://example.org\" VERSION \"1\" = TOPIC Data = "
        + "STRUCTURE S = value : MANDATORY 0..100; MANDATORY CONSTRAINT Rule: value > 5; END S; END Data; END Evidence.";
    var unsupported = generation.generateIliConstraintCases(orphan, "Evidence.Data.S.Rule");
    assertThat(unsupported).containsEntry("compilerValid", true).containsEntry("generationVerified", false);
  }
  @Test void rejectedBatchPreservesActuallyPerformedInputCompilation() {
    var service = new ch.so.agi.mcp.change.IliModelChangesService(compiler, changes, renderer,
        contexts, new ConstraintSourceEditService(), generation);
    var request = new ch.so.agi.mcp.change.IliModelChangesRequest(); request.changes = List.of();
    var result = service.apply(model, request, null, null, null);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.INVALID_SPEC);
    assertThat(result.beforeCompilation.valid()).isTrue();
    assertThat(result.afterCompilation).isNull();
    assertThat(result.constraintProofs).isNull();
    var diff = new ModelChangeTools(compiler, changes).reviewIliChange("INVALID", model, ModelPurpose.CAPTURE, ModelingRuleProfile.CORE);
    assertThat(diff).containsEntry("beforeCompilerValid", false).containsEntry("afterCompilerValid", true);
  }

  private static class CountingCompiler extends IliCompilerService {
    int calls;
    @Override public CompilationResult compile(String text, String repositories, String prefix) {
      calls++;
      return super.compile(text, repositories, prefix);
    }
  }
  private ConstraintTestTools.TestCase age(int age, boolean valid) {
    var object = new ConstraintTestTools.TestObject(); object.classFqn = "Evidence.Data.Person";
    object.oid = "p" + age; object.values = Map.of("age", age);
    var test = new ConstraintTestTools.TestCase(); test.name = "age" + age;
    test.expectationSource = ConstraintTestTools.ExpectationSource.USER_PROVIDED;
    test.expectedConstraintValid = valid; test.objects = List.of(object); return test;
  }
}
