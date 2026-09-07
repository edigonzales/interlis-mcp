package ch.so.agi.mcp.constraint;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ConstraintCoverageProofRegressionTest {
  record Request(String contextFqn, IliConstraintSpec spec) {}

  @Test
  void p02AuthorsUnchangedConditionWithVerifiedCoverageAndExplicitImpossibleBranch() throws Exception {
    Request request;
    try (var input = getClass().getResourceAsStream("/constraint/p02-coverage.json")) {
      assertThat(input).isNotNull();
      request = new ObjectMapper().readValue(input, Request.class);
    }
    String source = Files.readString(Path.of("evals/constraint-reconstruction/v1/public/P02/model.ili"));
    IliCompilerService compiler = new IliCompilerService();
    IliAuthoringResult result = new ConstraintAuthoringTools(engine(compiler)).authorIliMandatoryConstraint(
        source, request.contextFqn(), (IliConstraintSpec.Mandatory) request.spec(), null, null);
    assertThat(result.status).as(result.toString()).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(result.proofVerified).isTrue();
    var proof = result.constraintProofs.getFirst();
    assertThat(proof.coverageComplete).isTrue();
    assertThat(proof.coverageGaps).isEmpty();
    assertThat(proof.coverageGoalCount).isEqualTo(proof.coverageSolvedCount);
    assertThat(proof.coverageSolvedCount).isGreaterThan(proof.generatedCases.size());
    assertThat(proof.coverageExcludedCount).isEqualTo(proof.coverageExcludedGoals.size());
    assertThat(proof.coverageExcludedGoals).anySatisfy(exclusion -> {
      assertThat(exclusion.reason).isEqualTo("OR branch 1 independently true");
      assertThat(exclusion.reasonCode).isEqualTo("PROVEN_UNREACHABLE");
      assertThat(exclusion.justification).contains("partition", "Unterboden->Ph", "Untertyp_E");
    });
    assertThat(proof.verification.allPassed).isTrue();
    assertThat(proof.generatedCases).anySatisfy(item -> {
      assertThat(item.values.get("Unterboden->Ph")).isEqualTo("UNDEFINED");
      assertThat(item.expectedConstraintValid).isTrue();
      assertThat(proof.verification.cases).anySatisfy(verified -> {
        assertThat(verified.name).isEqualTo(item.name);
        assertThat(verified.passed).isTrue();
      });
    });
    assertThat(proof.generatedCases.stream().mapToInt(item -> item.coveredGoals.size()).sum())
        .isEqualTo(proof.coverageSolvedCount);
    var renderer = new IliSpecRenderer(new AttributeTools(), new DomainTools());
    var mandatory = (IliConstraintSpec.Mandatory) request.spec();
    String condition = renderer.renderExpression(mandatory.condition, "2.3",
        request.contextFqn().substring(0, request.contextFqn().indexOf('.')), new java.util.LinkedHashSet<>());
    assertThat(result.updatedModelText).contains("MANDATORY CONSTRAINT " + condition + ";");
    var compiled = compiler.compile(result.updatedModelText, null);
    assertThat(compiled.valid()).as(compiled.messages().toString()).isTrue();
  }

  @Test
  void p01ProvesEmbeddedStructureIncludingEveryTextureBoundary() throws Exception {
    Request request;
    try (var input = getClass().getResourceAsStream("/constraint/p01-structure.json")) {
      request = new ObjectMapper().readValue(input, Request.class);
    }
    String source = Files.readString(Path.of("evals/constraint-reconstruction/v1/public/P01/model.ili"));
    var compiler = new CountingCompiler();
    var result = new ConstraintAuthoringTools(engine(compiler)).authorIliMandatoryConstraint(
        source, request.contextFqn(), (IliConstraintSpec.Mandatory) request.spec(), null, null);
    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.status).as(result.toString()).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(result.proofVerified).isTrue();
    var proof = result.constraintProofs.getFirst();
    assertThat(proof.coverageComplete).isTrue();
    assertThat(proof.coverageGaps).isEmpty();
    assertThat(proof.verification.allPassed).isTrue();
    assertThat(proof.generatedCases).allSatisfy(item -> {
      assertThat(item.ownerClassFqn).endsWith(".BodeneinheitHauptauspraegung_Landwirtschaft");
      assertThat(item.structurePath).isEqualTo("Unterboden");
    });
    assertThat(proof.verification.cases).allSatisfy(item -> {
      assertThat(item.subjectCount).isEqualTo(1);
      assertThat(item.constraintExercised).isTrue();
      assertThat(item.fixtureValid).isTrue();
      assertThat(item.expectedValid).isEqualTo(item.actualValid);
      assertThat(item.fixtureErrors).isEmpty();
    });
    assertThat(proof.generatedCases).anySatisfy(item -> {
      assertThat(item.values.get("Koernungsklasse")).isEqualTo("ton");
      assertThat(new java.math.BigDecimal(item.values.get("Tongehalt").toString())).isBetween(
          java.math.BigDecimal.valueOf(50), java.math.BigDecimal.valueOf(100));
      assertThat(item.coveredGoals).anyMatch(goal -> "OR branch 9 independently true".equals(goal.reason));
      assertThat(item.coveredGoals).anyMatch(goal -> goal.reason.contains("OR branch 9") && !goal.reason.equals("OR branch 9 independently true"));
    });
    // Independent requirement table: inclusive boundaries plus in-domain outside neighbours.
    String[] textures = {"sand", "schluffiger_sand", "lehmiger_sand", "lehmreicher_sand",
        "sandiger_lehm", "lehm", "toniger_lehm", "lehmiger_ton", "ton",
        "sandiger_schluff", "schluff", "lehmiger_schluff", "toniger_schluff"};
    int[][] ranges = {{0,5},{0,5},{5,10},{10,15},{15,20},{20,30},{30,40},{40,50},
        {50,100},{0,10},{0,10},{10,30},{30,50}};
    var cases = new java.util.ArrayList<ConstraintTestTools.TestCase>();
    for (int i = 0; i < textures.length; i++) {
      for (int value : new int[]{ranges[i][0]-1, ranges[i][0], ranges[i][1], ranges[i][1]+1}) {
        if (value < 0 || value > 100) continue;
        var c = new ConstraintTestTools.TestCase();
        c.name = textures[i] + " at " + value;
        c.expectedConstraintValid = value >= ranges[i][0] && value <= ranges[i][1];
        var o = new ConstraintTestTools.TestObject();
        o.classFqn = proof.generatedCases.getFirst().ownerClassFqn;
        o.values = java.util.Map.of("Unterboden", java.util.Map.of("Koernungsklasse", textures[i], "Tongehalt", value));
        c.objects = java.util.List.of(o);
        cases.add(c);
      }
    }
    var verified = new ConstraintTestTools(compiler).testIliConstraint(result.updatedModelText,
        request.contextFqn() + "." + request.spec().name, cases);
    assertThat(verified.get("allPassed")).as(verified.toString()).isEqualTo(true);
    var resolved = new ConstraintContextService(compiler).compileAndResolve(result.updatedModelText,
        request.contextFqn() + "." + request.spec().name, null, "p01_resolution_");
    assertThat(resolved.available()).isTrue();
    assertThat(resolved.context().constraint().getContainer().getScopedName(null)).isEqualTo(request.contextFqn());
  }

  private static class CountingCompiler extends IliCompilerService {
    int calls;
    @Override public CompilationResult compile(String text, String repositories, String prefix) {
      calls++;
      return super.compile(text, repositories, prefix);
    }
  }

  @Test
  void booleanSetPreservesGoalCountsAndExclusionDiagnostics() {
    String model = """
        INTERLIS 2.4;
        MODEL CoverageSet (en) AT "https://example.org" VERSION "2026-09-07" =
          TOPIC Data =
            CLASS Item = value : MANDATORY 0 .. 10; END Item;
            CONSTRAINTS OF CoverageSet.Data.Item =
              !!@ name = "Rule"
              SET CONSTRAINT value > 5 AND value > 5;
            END;
          END Data;
        END CoverageSet.
        """;
    IliCompilerService compiler = new IliCompilerService();
    var response = new ConstraintCaseGenerationTools(new ConstraintContextService(compiler), new ConstraintTestTools(compiler))
        .generateIliConstraintCases(model, "CoverageSet.Data.Item.Rule");
    assertThat(response.get("coverageComplete")).as(response.toString()).isEqualTo(true);
    assertThat(response.get("generationVerified")).isEqualTo(true);
    var proof = IliAuthoringResult.constraintProof("CoverageSet.Data.Item.Rule", true, response);
    assertThat(proof.coverageExcludedGoals).isNotEmpty();
    assertThat(proof.coverageGoalCount).isEqualTo(proof.coverageSolvedCount);
    assertThat(proof.generatedCases.stream().mapToInt(item -> item.coveredGoals.size()).sum())
        .isEqualTo(proof.coverageSolvedCount);
  }

  private static ConstraintAuthoringEngine engine(IliCompilerService compiler) {
    var analysis = new ModelAnalysisTools(compiler);
    return new ConstraintAuthoringEngine(new ConstraintAuthoringWorkflow(compiler),
        new IliSpecRenderer(new AttributeTools(), new DomainTools()),
        new ConstraintCaseGenerationTools(new ConstraintContextService(compiler), new ConstraintTestTools(compiler)),
        new ModelChangeReviewService(analysis,
            new ModelingRuleTools(new KnowledgeRuleLoader(), analysis, compiler)));
  }
}
