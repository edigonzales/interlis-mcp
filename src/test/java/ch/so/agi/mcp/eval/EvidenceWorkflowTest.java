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

class EvidenceWorkflowTest {
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
    Map<String,Object> reviewed = reviews.reviewIliModel(model, ModelPurpose.CAPTURE, ModelingRuleProfile.SO);
    var evidence = (EvidenceSummary) reviewed.get("evidence");
    assertThat(evidence.compiler().status()).isEqualTo(EvidenceSummary.Status.PASSED);
    assertThat(evidence.modelingRules().status()).isEqualTo(EvidenceSummary.Status.HAS_WARNINGS);
    assertThat(evidence.modelingRules().errorCount()).isZero();
    assertThat(evidence.modelingRules().warningCount()).isPositive();
    assertThat(evidence.modelingRules().checkedCount()).isPositive();
    assertThat(reviewed.get("validForAutomatedRules")).isEqualTo(false); // unchanged legacy policy
    assertThat(evidence.constraintTests().status()).isEqualTo(EvidenceSummary.Status.NOT_RUN);
    assertThat(evidence.businessAcceptance().status()).isEqualTo(EvidenceSummary.Status.NOT_RUN);
    var bad = (EvidenceSummary) reviews.reviewIliModel("INVALID", null, null).get("evidence");
    assertThat(bad.compiler().status()).isEqualTo(EvidenceSummary.Status.FAILED);
    assertThat(bad.modelingRules().status()).isEqualTo(EvidenceSummary.Status.NOT_RUN);
    assertThat(bad.constraintInteractions().status()).isEqualTo(EvidenceSummary.Status.NOT_RUN);
  }
  @Test void authoringWithoutConstraintsDoesNotClaimTestsAndInvalidSpecDoesNotClaimCompile() {
    var author = new IliModelAuthoringTools(renderer, compiler, contexts, generation, changes);
    var spec = new IliModelSpec(); spec.name = "Empty"; spec.iliVersion = "2.4";
    spec.uri = "https://example.org"; spec.version = "1";
    var result = author.authorIliModel(spec, ModelPurpose.CAPTURE, ModelingRuleProfile.CORE);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(result.proofVerified).isTrue(); // retained for older clients
    assertThat(result.evidence.constraintTests().status()).isEqualTo(EvidenceSummary.Status.NOT_APPLICABLE);
    assertThat(result.evidence.constraintTests().checkedCount()).isZero();
    assertThat(result.evidence.compiler().status()).isEqualTo(EvidenceSummary.Status.PASSED);
    spec.name = "123";
    var invalid = author.authorIliModel(spec, ModelPurpose.CAPTURE, ModelingRuleProfile.CORE);
    assertThat(invalid.status).isEqualTo(IliAuthoringResult.Status.INVALID_SPEC);
    assertThat(invalid.evidence.compiler().status()).isEqualTo(EvidenceSummary.Status.NOT_RUN);
  }
  @Test void successfulAutomaticProofDoesNotReplaceIndependentAgeBoundaryExpectations() {
    String wrong = model.replace("END Person;", "MANDATORY CONSTRAINT AgeRule: age > 18; END Person;");
    var automatic = generation.generateIliConstraintCases(wrong, "Evidence.Data.Person.AgeRule");
    assertThat(automatic.get("generationVerified")).isEqualTo(true);
    var automaticEvidence = (EvidenceSummary) automatic.get("evidence");
    assertThat(automaticEvidence.constraintTests().status()).isEqualTo(EvidenceSummary.Status.PASSED);
    assertThat(automaticEvidence.constraintTests().basis()).isEqualTo("AUTOMATIC_CONSTRAINT_DERIVED_CASES");
    List<ConstraintTestTools.TestCase> cases = List.of(age(17, false), age(18, true), age(19, true));
    var explicit = tests.testIliConstraint(wrong, "Evidence.Data.Person.AgeRule", cases);
    assertThat(explicit.get("allPassed")).isEqualTo(false);
    assertThat(explicit.get("passedCount")).isEqualTo(2);
    var evidence = (EvidenceSummary) explicit.get("evidence");
    assertThat(evidence.constraintTests().basis()).isEqualTo("CALLER_SUPPLIED_EXPECTATIONS");
    assertThat(evidence.constraintTests().status()).isEqualTo(EvidenceSummary.Status.FAILED);
    assertThat(evidence.constraintTests().checkedCount()).isEqualTo(3);
    assertThat(evidence.businessAcceptance().status()).isEqualTo(EvidenceSummary.Status.NOT_RUN);
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
    assertThat(result.requiresUserDecision).isFalse();
    assertThat(result.evidence.constraintTests().status()).isEqualTo(EvidenceSummary.Status.PASSED);
    assertThat(result.evidence.constraintTests().scope()).isEqualTo("SELECTED_CONSTRAINTS");
    assertThat(result.afterReview.constraintInteractions.contexts().getFirst().status())
        .isEqualTo(ConstraintInteractionAnalysis.Status.CONTRADICTION_PROVEN);
    assertThat(result.evidence.constraintInteractions().status()).isEqualTo(EvidenceSummary.Status.FAILED);
    var change = new ModelChangeTools(compiler, changes).reviewIliChange(before, result.updatedModelText, ModelPurpose.CAPTURE, ModelingRuleProfile.CORE);
    assertThat(((EvidenceSummary) change.get("evidence")).constraintInteractions().status()).isEqualTo(EvidenceSummary.Status.FAILED);
  }
  @Test void compilationFailureAndUnsupportedAutomaticCasesHaveHonestEvidence() {
    var invalid = generation.generateIliConstraintCases("INVALID", "Missing");
    assertThat(((EvidenceSummary) invalid.get("evidence")).compiler().status()).isEqualTo(EvidenceSummary.Status.FAILED);
    assertThat(((EvidenceSummary) invalid.get("evidence")).constraintTests().status()).isEqualTo(EvidenceSummary.Status.NOT_RUN);
    var explicit = tests.testIliConstraint("INVALID", "Missing", List.of(age(18, true)));
    assertThat(((EvidenceSummary) explicit.get("evidence")).constraintTests().status()).isEqualTo(EvidenceSummary.Status.NOT_RUN);
    String orphan = "INTERLIS 2.4; MODEL Evidence (en) AT \"https://example.org\" VERSION \"1\" = TOPIC Data = "
        + "STRUCTURE S = value : MANDATORY 0..100; MANDATORY CONSTRAINT Rule: value > 5; END S; END Data; END Evidence.";
    var unsupported = generation.generateIliConstraintCases(orphan, "Evidence.Data.S.Rule");
    assertThat(((EvidenceSummary) unsupported.get("evidence")).compiler().status()).isEqualTo(EvidenceSummary.Status.PASSED);
    assertThat(((EvidenceSummary) unsupported.get("evidence")).constraintTests().status()).isEqualTo(EvidenceSummary.Status.INCOMPLETE);
  }
  @Test void rejectedBatchPreservesActuallyPerformedInputCompilation() {
    var service = new ch.so.agi.mcp.change.IliModelChangesService(compiler, changes, renderer);
    var request = new ch.so.agi.mcp.change.IliModelChangesRequest(); request.changes = List.of();
    var result = service.apply(model, request, null, null, null);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.INVALID_SPEC);
    assertThat(result.evidence.compiler().status()).isEqualTo(EvidenceSummary.Status.PASSED);
    assertThat(result.evidence.compiler().checkedCount()).isEqualTo(1);
    assertThat(result.evidence.constraintTests().status()).isEqualTo(EvidenceSummary.Status.NOT_RUN);
    var diff = new ModelChangeTools(compiler, changes).reviewIliChange("INVALID", model, ModelPurpose.CAPTURE, ModelingRuleProfile.CORE);
    assertThat(((EvidenceSummary) diff.get("evidence")).compiler().status()).isEqualTo(EvidenceSummary.Status.FAILED);
    assertThat(((EvidenceSummary) diff.get("evidence")).compiler().checkedCount()).isEqualTo(2);
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
    test.expectedConstraintValid = valid; test.objects = List.of(object); return test;
  }
}
