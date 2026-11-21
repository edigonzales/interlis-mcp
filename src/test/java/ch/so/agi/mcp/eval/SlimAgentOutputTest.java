package ch.so.agi.mcp.eval;

import static org.assertj.core.api.Assertions.assertThat;
import ch.so.agi.mcp.analysis.*;
import ch.so.agi.mcp.constraint.*;
import ch.so.agi.mcp.knowledge.*;
import ch.so.agi.mcp.model.*;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SlimAgentOutputTest {
  final ObjectMapper mapper = new ObjectMapper();

  @Test void hashesIdentifyExactUtf8WithoutNormalization() throws Exception {
    assertThat(ModelHashes.sha256("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assertThat(ModelHashes.sha256(null)).isNull();
    assertThat(ModelHashes.sha256("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    var hashes = List.of("ä", "a", "a\n", "a\r\n", "a ", "ä\n", "a\u0308").stream().map(ModelHashes::sha256).toList();
    assertThat(hashes).doesNotHaveDuplicates();
    assertThat(ModelHashes.sha256("ä")).isEqualTo(ModelHashes.sha256("ä"));
    assertThat(mapper.writeValueAsString(ModelHashes.model("INVALID"))).doesNotContain("before", "after", "candidate");
  }

  @Test void onlyExplicitlySuccessfulWarningFreeCasesLoseTheirXtf() throws Exception {
    var verification = new IliAuthoringResult.ProofVerification();
    var negative = testCase(true, true, true, 0);
    negative.expectedConstraintValid = false;
    negative.actualConstraintValid = false;
    verification.cases = List.of(testCase(true, true, true, 0), negative, testCase(false, true, true, 0),
        testCase(true, false, true, 0), testCase(true, true, false, 0),
        testCase(true, true, true, 1), testCase(true, true, true, null));
    var full = TestXtfOutput.verificationResponse(verification, true);
    var compact = TestXtfOutput.verificationResponse(verification, false);
    TestXtfOutput.addOmittedCount(compact, verification, false);
    assertThat(compact.get("omittedSuccessfulTestXtfCount")).isEqualTo(2);
    var projected = (List<?>) compact.get("cases");
    assertThat(((Map<?, ?>) projected.get(0)).containsKey("xtfText")).isFalse();
    assertThat(((Map<?, ?>) projected.get(1)).containsKey("xtfText")).isFalse();
    for (int i = 2; i < projected.size(); i++) assertThat(((Map<?, ?>) projected.get(i)).containsKey("xtfText")).isTrue();
    assertThat((List<?>) full.get("cases")).allSatisfy(test ->
        assertThat(((Map<?, ?>) test).get("xtfText")).isEqualTo("transfer"));
    assertThat(compact.get("allPassed")).isEqualTo(full.get("allPassed"));
    assertThat(verification.cases).allSatisfy(test -> assertThat(test.xtfText).isEqualTo("transfer"));
  }

  @Test void typedProjectionPreservesAllOtherEvidenceAndIncludesCounterexamples() throws Exception {
    var compiler = new ProseConstraintWorkflowTest.Counter();
    var result = author(compiler).authorIliConstraint(before(), "Simple.Data.Person", spec(), null, null, true);
    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(result.modelHashes.before()).isEqualTo(ModelHashes.sha256(before()));
    assertThat(result.modelHashes.after()).isEqualTo(ModelHashes.sha256(result.updatedModelText));
    assertThat(result.modelHashes.candidate()).isNull();
    var snapshot = mapper.valueToTree(result);
    var hashes = result.modelHashes;
    TestXtfOutput.prepare(result, null);
    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.omittedSuccessfulTestXtfCount).isPositive();
    assertThat(result.modelHashes).isSameAs(hashes);
    var reduced = mapper.valueToTree(result);
    var restored = reduced.deepCopy();
    ((tools.jackson.databind.node.ObjectNode)restored).remove("omittedSuccessfulTestXtfCount");
    var oldCases = snapshot.get("constraintProofs").get(0).get("verification").get("cases");
    var newCases = restored.get("constraintProofs").get(0).get("verification").get("cases");
    for (int i=0;i<oldCases.size();i++) if (oldCases.get(i).has("xtfText"))
      ((tools.jackson.databind.node.ObjectNode)newCases.get(i)).set("xtfText",oldCases.get(i).get("xtfText"));
    assertThat(restored).isEqualTo(snapshot);
    assertThat(result.constraintProofs.getFirst().verification.cases).anySatisfy(c -> {
      assertThat(c.expectedConstraintValid).isFalse(); assertThat(c.passed).isTrue(); assertThat(c.xtfText).isNull();
    });
  }

  @Test void publicBoundaryHashesEarlyErrorsAndAllReviewKinds() {
    var compiler = new ProseConstraintWorkflowTest.Counter();
    var analysis = new ModelAnalysisTools(compiler);
    assertHash(analysis.analyzeIliModel("INVALID", null), "INVALID");
    var rules = new ModelingRuleTools(new KnowledgeRuleLoader(), analysis, compiler);
    assertHash(rules.reviewIliModel("INVALID", null, null), "INVALID");
    assertHash(new ConstraintReviewTools(compiler, new ConstraintKnowledgeTools(compiler)).reviewIliConstraint("INVALID", "Missing"), "INVALID");
    var testTools = new ConstraintTestTools(compiler);
    assertHash(testTools.testIliConstraint("INVALID", "Missing", List.of(ProseConstraintWorkflowTest.age(18,true,null)), false), "INVALID");
    assertHash(new ConstraintCaseGenerationTools(new ConstraintContextService(compiler), testTools).generateIliConstraintCases("INVALID", "Missing", false), "INVALID");
    var changed = new ModelChangeTools(compiler, new ModelChangeReviewService(analysis, rules)).reviewIliChange("INVALID", before(), null, null);
    assertThat(changed.get("modelHashes")).isEqualTo(ModelHashes.change("INVALID", before()));
    var invalid = spec(); invalid.name="1Invalid";
    var rejected = author(compiler).authorIliConstraint(before(), "Simple.Data.Person", invalid, null, null, false);
    assertThat(rejected.modelHashes.before()).isEqualTo(ModelHashes.sha256(before()));
    assertThat(rejected.modelHashes.after()).isNull();
    assertThat(rejected.modelHashes.candidate()).isNull();
    var candidate = new IliAuthoringResult(); candidate.candidateModelText = "candidate";
    ModelHashes.attach(candidate, "input");
    assertThat(candidate.modelHashes).isEqualTo(new ModelHashes(null,ModelHashes.sha256("input"),null,ModelHashes.sha256("candidate")));
    var fresh = new IliAuthoringResult(); fresh.updatedModelText=before();
    ModelHashes.attach(fresh,null);
    assertThat(fresh.modelHashes.before()).isNull();
    assertThat(fresh.modelHashes.after()).isEqualTo(ModelHashes.sha256(before()));
  }

  @Test void realFixtureMeasurementsAndHashBinding() throws Exception {
    var compiler = new ProseConstraintWorkflowTest.Counter();
    var authored = author(compiler).authorIliConstraint(before(), "Simple.Data.Person", spec(), null, null, false);
    var tests = new ConstraintTestTools(compiler);
    var checked = tests.testIliConstraint(authored.updatedModelText, "Simple.Data.Person.Adult", List.of(
        ProseConstraintWorkflowTest.age(17,false,null), ProseConstraintWorkflowTest.age(18,true,null), ProseConstraintWorkflowTest.age(19,true,null)), false);
    assertThat(((ModelHashes)checked.get("modelHashes")).model()).isEqualTo(authored.modelHashes.after());
    assertThat(checked.get("omittedSuccessfulTestXtfCount")).isEqualTo(3);
    assertThat(ModelHashes.sha256(authored.updatedModelText+"\n")).isNotEqualTo(authored.modelHashes.after());
    var generator = new ConstraintCaseGenerationTools(new ConstraintContextService(compiler), tests);
    for (var fixture : List.of(ProseConstraintWorkflowTest.simple("age >= 18"),
        ProseConstraintWorkflowTest.simple("age > 18 AND age < 90"))) {
      var full = generator.generateIliConstraintCases(fixture, "Simple.Data.Person.Rule", true);
      int calls = compiler.calls;
      var compact = generator.generateIliConstraintCases(fixture, "Simple.Data.Person.Rule", false);
      assertThat(compact.get("generationVerified")).isEqualTo(full.get("generationVerified"));
      int fullBytes=mapper.writeValueAsBytes(full).length, compactBytes=mapper.writeValueAsBytes(compact).length;
      assertThat(compactBytes).isLessThan(fullBytes);
      assertThat(compiler.calls).isEqualTo(calls + 1);
    }
  }

  static String before() { return ProseConstraintWorkflowTest.simple("age >= 18").replace("MANDATORY CONSTRAINT Rule: age >= 18;", ""); }
  static IliConstraintSpec.Mandatory spec() {
    var spec = new IliConstraintSpec.Mandatory(); spec.name="Adult";
    var a=new IliConstraintSpec.ExpressionSpec(); a.kind=IliConstraintSpec.ExpressionKind.ATTRIBUTE; a.name="age";
    var n=new IliConstraintSpec.ExpressionSpec(); n.kind=IliConstraintSpec.ExpressionKind.NUMERIC; n.value=18;
    var comparison=new IliConstraintSpec.ExpressionSpec(); comparison.kind=IliConstraintSpec.ExpressionKind.COMPARE; comparison.operator=">="; comparison.children=List.of(a,n);
    spec.condition=comparison; return spec;
  }
  static ConstraintAuthoringTools author(IliCompilerService compiler) {
    var analysis = new ModelAnalysisTools(compiler);
    var rules = new ModelingRuleTools(new KnowledgeRuleLoader(),analysis,compiler);
    var contexts = new ConstraintContextService(compiler);
    var generation = new ConstraintCaseGenerationTools(contexts,new ConstraintTestTools(compiler));
    return new ConstraintAuthoringTools(new ConstraintAuthoringEngine(
        compiler, contexts, new ConstraintSourceEditService(),
        new IliSpecRenderer(new AttributeTools(),new DomainTools()),generation,new ModelChangeReviewService(analysis,rules)));
  }
  static IliAuthoringResult.ProofCaseVerification testCase(boolean passed, boolean fixture, boolean exercised, Integer warnings) {
    var test = new IliAuthoringResult.ProofCaseVerification();
    test.passed = passed;
    test.fixtureValid = fixture;
    test.constraintExercised = exercised;
    test.warningCount = warnings;
    test.xtfText = "transfer";
    return test;
  }
  static void assertHash(Map<String,Object> result, String text) { assertThat(result.get("modelHashes")).isEqualTo(ModelHashes.model(text)); }
}
