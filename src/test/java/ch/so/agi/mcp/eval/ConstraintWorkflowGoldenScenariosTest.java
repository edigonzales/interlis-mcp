package ch.so.agi.mcp.eval;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.constraint.ConstraintAuthoringEngine;
import ch.so.agi.mcp.constraint.ConstraintAuthoringWorkflow;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.knowledge.KnowledgeRuleLoader;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.model.IliConstraintSpec;
import ch.so.agi.mcp.model.IliSpecRenderer;
import ch.so.agi.mcp.tools.AttributeTools;
import ch.so.agi.mcp.tools.ConstraintAuthoringTools;
import ch.so.agi.mcp.tools.ConstraintCaseGenerationTools;
import ch.so.agi.mcp.tools.ConstraintTestTools;
import ch.so.agi.mcp.tools.DomainTools;
import ch.so.agi.mcp.tools.UniqueConstraintAuthoringTools;
import java.util.List;
import java.math.BigDecimal;
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

  private static final String COMPLETE_MODEL = """
      INTERLIS 2.4;

      MODEL ConstraintWorkflow (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Target = code : 0 .. 100; END Target;
          CLASS Item =
            value : MANDATORY 0 .. 100;
            code : MANDATORY 0 .. 100;
          END Item;
        END Data;
      END ConstraintWorkflow.
      """;

  private static final String NAVIGATED_SET_MODEL = """
      INTERLIS 2.4;

      MODEL ConstraintSetPath (en) AT "https://example.org" VERSION "2026-08-25" =
        TOPIC Data =
          CLASS Detail = value : MANDATORY 0..10; END Detail;
          CLASS Main = code : MANDATORY 0..10; END Main;
          ASSOCIATION MainDetail =
            MainRole -- {1} Main;
            Secondary -- {0..3} Detail;
          END MainDetail;
        END Data;
      END ConstraintSetPath.
      """;

  private static final String POLYMORPHIC_SET_MODEL = """
      INTERLIS 2.4;

      MODEL ConstraintSetPolymorphic (en) AT "https://example.org" VERSION "2026-08-25" =
        TOPIC Data =
          CLASS Main = END Main;
          CLASS AbstractTarget (ABSTRACT) = value : MANDATORY 0..10; END AbstractTarget;
          CLASS TargetA EXTENDS AbstractTarget = END TargetA;
          CLASS TargetB EXTENDS AbstractTarget = END TargetB;
          ASSOCIATION MainTarget =
            MainRole -- {1} Main;
            TargetRole -- {0..*} AbstractTarget;
          END MainTarget;
        END Data;
      END ConstraintSetPolymorphic.
      """;

  @Test
  void typedConstraintAuthoringIncludesFinalReviewWithoutAdditionalCompiles() {
    CountingCompiler compiler = new CountingCompiler();
    ConstraintCaseGenerationTools cases = new ConstraintCaseGenerationTools(
        new ConstraintContextService(compiler),
        new ConstraintTestTools(compiler));
    ModelChangeReviewService review = reviewService(compiler);
    ConstraintAuthoringTools authoring = new ConstraintAuthoringTools(
        engine(compiler, cases, review));
    IliConstraintSpec.ExpressionSpec value = expression(
        IliConstraintSpec.ExpressionKind.ATTRIBUTE, "value", null, null, null);
    IliConstraintSpec.ExpressionSpec minimum = expression(
        IliConstraintSpec.ExpressionKind.NUMERIC, null, null, 10, null);
    IliConstraintSpec.ExpressionSpec root = expression(
        IliConstraintSpec.ExpressionKind.COMPARE, null, ">=", null,
        List.of(value, minimum));
    IliConstraintSpec.Mandatory spec = new IliConstraintSpec.Mandatory();
    spec.name = "MinimumValue";
    spec.condition = root;

    IliAuthoringResult authored = authoring.authorIliMandatoryConstraint(
        BASE_MODEL,
        "ConstraintWorkflow.Data.Item",
        spec,
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.CORE);

    assertThat(authored.generated).isEqualTo(true);
    assertThat(authored.proofVerified).isEqualTo(true);
    assertThat(compiler.calls).isEqualTo(2);
    assertThat(authored.semanticDiff).isNotNull();
    assertThat(authored.afterReview).isNotNull();
    assertThat(authored.afterReview.validForAutomatedRules).isEqualTo(true);
  }

  @Test
  void uniqueHighLevelAuthoringIncludesProofDiffAndReviewWithTwoCompiles() {
    CountingCompiler compiler = new CountingCompiler();
    ConstraintContextService contexts = new ConstraintContextService(compiler);
    ConstraintCaseGenerationTools cases = new ConstraintCaseGenerationTools(
        contexts,
        new ConstraintTestTools(compiler));
    ModelChangeReviewService review = reviewService(compiler);
    UniqueConstraintAuthoringTools authoring = new UniqueConstraintAuthoringTools(
        engine(compiler, cases, review));
    IliConstraintSpec.Unique spec = new IliConstraintSpec.Unique();
    spec.name = "CodeUnique";
    spec.scope = IliConstraintSpec.UniqueScope.GLOBAL;
    spec.keyPaths = List.of("code");

    IliAuthoringResult result = authoring.authorIliUniqueConstraint(
        BASE_MODEL,
        "ConstraintWorkflow.Data.Item",
        spec,
        ModelPurpose.CAPTURE,
        ModelingRuleProfile.CORE);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(result.proofVerified).isTrue();
    assertThat(result.semanticDiff).isNotNull();
    assertThat(result.afterReview).isNotNull();
  }

  @Test
  void everyConstraintAdapterUsesExactlyBeforeAndAfterCompile() {
    List<IliConstraintSpec> specs = List.of(
        mandatorySpec(), uniqueSpec(), existenceSpec(), plausibilitySpec(), setSpec());
    for (IliConstraintSpec spec : specs) {
      CountingCompiler compiler = new CountingCompiler();
      ConstraintCaseGenerationTools cases = new ConstraintCaseGenerationTools(
          new ConstraintContextService(compiler), new ConstraintTestTools(compiler));
      IliAuthoringResult result = engine(compiler, cases, reviewService(compiler)).author(
          COMPLETE_MODEL, "ConstraintWorkflow.Data.Item", spec,
          ModelPurpose.CAPTURE, ModelingRuleProfile.CORE);
      assertThat(compiler.calls).as(spec.kind().name()).isEqualTo(2);
      assertThat(result.status).as("%s: %s", spec.kind(), result).isEqualTo(IliAuthoringResult.Status.GENERATED);
      assertThat(result.proofVerified).isTrue();
    }
  }

  @Test
  void standardFunctionImportIsInsertedInSameSourcePreservingBatch() {
    CountingCompiler compiler = new CountingCompiler();
    ConstraintCaseGenerationTools cases = new ConstraintCaseGenerationTools(
        new ConstraintContextService(compiler), new ConstraintTestTools(compiler));
    IliConstraintSpec.ExpressionSpec value = expression(
        IliConstraintSpec.ExpressionKind.ATTRIBUTE, "value", null, null, null);
    IliConstraintSpec.ExpressionSpec abs = expression(
        IliConstraintSpec.ExpressionKind.FUNCTION, "NUMERIC_ABS", null, null, List.of(value));
    abs.functionOrigin = IliConstraintSpec.FunctionOrigin.STANDARD;
    IliConstraintSpec.ExpressionSpec minimum = expression(
        IliConstraintSpec.ExpressionKind.NUMERIC, null, null, 10, null);
    IliConstraintSpec.Mandatory spec = new IliConstraintSpec.Mandatory();
    spec.name = "AbsoluteMinimum";
    spec.condition = expression(
        IliConstraintSpec.ExpressionKind.COMPARE, null, ">=", null, List.of(abs, minimum));

    IliAuthoringResult result = engine(compiler, cases, reviewService(compiler)).author(
        COMPLETE_MODEL, "ConstraintWorkflow.Data.Item", spec, null, null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.status).as(result.toString()).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(result.derivedImports).containsExactly("Math_V2");
    assertThat(result.updatedModelText).contains("IMPORTS Math_V2;").contains("Math_V2.abs(value)");
    assertThat(result.sourceEdits).hasSize(2);
  }

  @Test
  void externalFunctionKeepsCompilableCandidateWithTwoCompiles() {
    String model = COMPLETE_MODEL.replace(
        "  TOPIC Data =", "  FUNCTION custom(a: NUMERIC): BOOLEAN;\n\n  TOPIC Data =");
    CountingCompiler compiler = new CountingCompiler();
    ConstraintCaseGenerationTools cases = new ConstraintCaseGenerationTools(
        new ConstraintContextService(compiler), new ConstraintTestTools(compiler));
    IliConstraintSpec.ExpressionSpec value = expression(
        IliConstraintSpec.ExpressionKind.ATTRIBUTE, "value", null, null, null);
    IliConstraintSpec.ExpressionSpec custom = expression(
        IliConstraintSpec.ExpressionKind.FUNCTION, "ConstraintWorkflow.custom", null, null,
        List.of(value));
    custom.functionOrigin = IliConstraintSpec.FunctionOrigin.MODEL;
    IliConstraintSpec.Mandatory spec = new IliConstraintSpec.Mandatory();
    spec.name = "CustomRule";
    spec.condition = custom;

    IliAuthoringResult result = engine(compiler, cases, reviewService(compiler)).author(
        model, "ConstraintWorkflow.Data.Item", spec, null, null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.EXTERNAL_FUNCTION_SEMANTICS_REQUIRED);
    assertThat(result.updatedModelText).isNull();
    assertThat(result.candidateModelText).contains("ConstraintWorkflow.custom(value)");
    assertThat(result.proofVerified).isFalse();
  }

  @Test
  void setAdapterAuthorsNavigatedObjectCountAndBooleanExpressionWithTwoCompilesEach() {
    IliConstraintSpec.PathObjectsSpec path = new IliConstraintSpec.PathObjectsSpec();
    path.path = "Secondary";
    IliConstraintSpec.ObjectCountSetConditionSpec count =
        new IliConstraintSpec.ObjectCountSetConditionSpec();
    count.objects = path;
    count.operator = ">=";
    count.threshold = new BigDecimal("2");
    IliConstraintSpec.Set navigated = new IliConstraintSpec.Set();
    navigated.name = "LinkedAtLeastTwo";
    navigated.scope = IliConstraintSpec.SetScope.GLOBAL;
    navigated.condition = count;

    IliConstraintSpec.BooleanSetConditionSpec booleanCondition =
        new IliConstraintSpec.BooleanSetConditionSpec();
    booleanCondition.expression = compare("code", ">", 0);
    IliConstraintSpec.Set bool = new IliConstraintSpec.Set();
    bool.name = "PositiveCode";
    bool.scope = IliConstraintSpec.SetScope.GLOBAL;
    bool.condition = booleanCondition;

    for (IliConstraintSpec.Set spec : List.of(navigated, bool)) {
      CountingCompiler compiler = new CountingCompiler();
      ConstraintCaseGenerationTools cases = new ConstraintCaseGenerationTools(
          new ConstraintContextService(compiler), new ConstraintTestTools(compiler));
      IliAuthoringResult result = engine(compiler, cases, reviewService(compiler)).author(
          NAVIGATED_SET_MODEL,
          "ConstraintSetPath.Data.Main",
          spec,
          ModelPurpose.CAPTURE,
          ModelingRuleProfile.CORE);

      assertThat(compiler.calls).as(spec.name).isEqualTo(2);
      assertThat(result.status).as(result.toString()).isEqualTo(IliAuthoringResult.Status.GENERATED);
      assertThat(result.proofVerified).isTrue();
      assertThat(result.updatedModelText)
          .contains("CONSTRAINTS OF ConstraintSetPath.Data.Main")
          .contains(spec.name);
    }
  }

  @Test
  void setAdapterProvesEveryConcretePolymorphicRoute() {
    IliConstraintSpec.PathObjectsSpec path = new IliConstraintSpec.PathObjectsSpec();
    path.path = "TargetRole";
    IliConstraintSpec.ObjectCountSetConditionSpec condition =
        new IliConstraintSpec.ObjectCountSetConditionSpec();
    condition.objects = path;
    condition.operator = ">=";
    condition.threshold = BigDecimal.ONE;
    IliConstraintSpec.Set spec = new IliConstraintSpec.Set();
    spec.name = "LinkedAtLeastOne";
    spec.scope = IliConstraintSpec.SetScope.GLOBAL;
    spec.condition = condition;
    CountingCompiler compiler = new CountingCompiler();
    ConstraintCaseGenerationTools cases = new ConstraintCaseGenerationTools(
        new ConstraintContextService(compiler), new ConstraintTestTools(compiler));

    IliAuthoringResult result = engine(compiler, cases, reviewService(compiler)).author(
        POLYMORPHIC_SET_MODEL,
        "ConstraintSetPolymorphic.Data.Main",
        spec,
        null,
        null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.status).as(result.toString()).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(result.updatedModelText).contains("INTERLIS.objectCount(TargetRole) >= 1");
    assertThat(result.candidateModelText).isNull();
    assertThat(result.constraintProofs).singleElement().satisfies(proof -> {
      assertThat(proof.proofVerified).isTrue();
      assertThat(proof.coverageComplete).isTrue();
      assertThat(proof.generatedCases)
          .extracting(candidate -> candidate.get("routeTargetFqn"))
          .contains(
              "ConstraintSetPolymorphic.Data.TargetA",
              "ConstraintSetPolymorphic.Data.TargetB");
    });
  }

  private IliConstraintSpec.Mandatory mandatorySpec() {
    IliConstraintSpec.Mandatory spec = new IliConstraintSpec.Mandatory();
    spec.name = "MandatoryRule";
    spec.condition = compare("value", ">=", 10);
    return spec;
  }

  private IliConstraintSpec.Unique uniqueSpec() {
    IliConstraintSpec.Unique spec = new IliConstraintSpec.Unique();
    spec.name = "UniqueRule";
    spec.scope = IliConstraintSpec.UniqueScope.GLOBAL;
    spec.keyPaths = List.of("code");
    return spec;
  }

  private IliConstraintSpec.Existence existenceSpec() {
    IliConstraintSpec.ExistenceTargetSpec target = new IliConstraintSpec.ExistenceTargetSpec();
    target.viewableFqn = "ConstraintWorkflow.Data.Target";
    target.attributePath = "code";
    IliConstraintSpec.Existence spec = new IliConstraintSpec.Existence();
    spec.name = "ExistenceRule";
    spec.restrictedPath = "code";
    spec.requiredIn = List.of(target);
    return spec;
  }

  private IliConstraintSpec.Plausibility plausibilitySpec() {
    IliConstraintSpec.Plausibility spec = new IliConstraintSpec.Plausibility();
    spec.name = "PlausibilityRule";
    spec.direction = IliConstraintSpec.PlausibilityDirection.AT_LEAST;
    spec.percentage = new BigDecimal("80");
    spec.condition = compare("value", ">=", 10);
    return spec;
  }

  private IliConstraintSpec.Set setSpec() {
    IliConstraintSpec.AllObjectsSpec all = new IliConstraintSpec.AllObjectsSpec();
    IliConstraintSpec.ObjectCountSetConditionSpec condition =
        new IliConstraintSpec.ObjectCountSetConditionSpec();
    condition.objects = all;
    condition.operator = ">=";
    condition.threshold = new BigDecimal("2");
    IliConstraintSpec.Set spec = new IliConstraintSpec.Set();
    spec.name = "SetRule";
    spec.scope = IliConstraintSpec.SetScope.GLOBAL;
    spec.where = compare("value", ">=", 5);
    spec.condition = condition;
    return spec;
  }

  private IliConstraintSpec.ExpressionSpec compare(String attribute, String operator, int value) {
    return expression(IliConstraintSpec.ExpressionKind.COMPARE, null, operator, null, List.of(
        expression(IliConstraintSpec.ExpressionKind.ATTRIBUTE, attribute, null, null, null),
        expression(IliConstraintSpec.ExpressionKind.NUMERIC, null, null, value, null)));
  }

  private ModelChangeReviewService reviewService(IliCompilerService compiler) {
    ModelAnalysisTools analysis = new ModelAnalysisTools(compiler);
    ModelingRuleTools rules = new ModelingRuleTools(
        new KnowledgeRuleLoader(), analysis, compiler);
    return new ModelChangeReviewService(analysis, rules);
  }

  private ConstraintAuthoringEngine engine(
      IliCompilerService compiler,
      ConstraintCaseGenerationTools cases,
      ModelChangeReviewService review) {
    return new ConstraintAuthoringEngine(
        new ConstraintAuthoringWorkflow(compiler),
        new IliSpecRenderer(new AttributeTools(), new DomainTools()),
        cases,
        review);
  }

  private IliConstraintSpec.ExpressionSpec expression(
      IliConstraintSpec.ExpressionKind kind,
      @Nullable String name,
      @Nullable String operator,
      @Nullable Object value,
      @Nullable List<IliConstraintSpec.ExpressionSpec> children) {
    IliConstraintSpec.ExpressionSpec expression = new IliConstraintSpec.ExpressionSpec();
    expression.kind = kind;
    expression.name = name;
    expression.operator = operator;
    expression.value = value;
    expression.children = children;
    return expression;
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
