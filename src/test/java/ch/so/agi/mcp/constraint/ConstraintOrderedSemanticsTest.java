package ch.so.agi.mcp.constraint;

import static ch.so.agi.mcp.constraint.ConstraintExpression.*;
import static ch.so.agi.mcp.constraint.ConstraintExpressionEngine.*;
import static org.assertj.core.api.Assertions.*;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.knowledge.KnowledgeRuleLoader;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.model.IliConstraintSpec;
import ch.so.agi.mcp.model.IliSpecRenderer;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConstraintOrderedSemanticsTest {
  private static final Type BOOL = Type.scalar(ScalarKind.BOOLEAN);
  private static final Attribute A = new Attribute("A", BOOL);
  private static final Attribute B = new Attribute("B", BOOL);
  private static final Attribute C = new Attribute("C", BOOL);
  private static final String MODEL = """
      INTERLIS %s;
      MODEL Ordered (en) AT "https://example.org" VERSION "2026-09-07" =
        TOPIC Data =
          CLASS Item =
            A : BOOLEAN;
            B : BOOLEAN;
            C : BOOLEAN;
            Label : TEXT*40;
            %s
          END Item;
        END Data;
      END Ordered.
      """;

  @Test
  void implicationHasIndependentOrderedTruthTable() {
    Object u = Undefined.INSTANCE;
    Object n = NotComputable.INSTANCE;
    Object[] inputs = {true, false, u};
    Object[][] expected = {{true, false, n}, {true, true, true}, {n, n, n}};
    for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
      assertThat(evaluate(new Implies(A, B), EvaluationContext.of(Map.of("A", inputs[a], "B", inputs[b]))))
          .isEqualTo(expected[a][b]);
    }
  }

  @Test
  void skippedBranchesAreNeverReadAndUnsupportedSemanticsAreNotUndefined() {
    var absent = EvaluationContext.of(Map.of());
    var unknown = new FunctionCall(new FunctionDefinition("UNKNOWN_BOOLEAN", List.of(), BOOL,
        ResultTypeRule.DECLARED, Map.of(IliVersion.ILI_24, new FunctionSyntax("Unknown.check"))), List.of());
    assertThat(evaluate(new And(List.of(new BooleanLiteral(false), unknown)), absent)).isEqualTo(false);
    assertThat(evaluate(new Or(List.of(new BooleanLiteral(true), unknown)), absent)).isEqualTo(true);
    assertThat(evaluate(new Implies(new BooleanLiteral(false), unknown), absent)).isEqualTo(true);
    for (ConstraintExpression expression : List.of(new And(List.of(A, unknown)),
        new Or(List.of(A, unknown)), new Implies(A, unknown))) {
      assertThat(evaluate(expression, absent)).isEqualTo(NotComputable.INSTANCE);
    }
    assertThatThrownBy(() -> evaluateConstraint(new And(List.of(new BooleanLiteral(true), unknown)), absent))
        .isInstanceOf(UnsupportedFunctionSemanticsException.class);
    assertThatThrownBy(() -> evaluate(new Defined(unknown), absent))
        .isInstanceOf(UnsupportedFunctionSemanticsException.class);
    assertThatThrownBy(() -> evaluateConstraint(new Not(A), EvaluationContext.of(Map.of("A", "bad boolean"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(evaluate(new Defined(A), absent)).isEqualTo(false);
    assertThat(evaluate(new Defined(new Not(A)), absent)).isEqualTo(NotComputable.INSTANCE);
    assertThat(evaluate(new Defined(A), EvaluationContext.of(Map.of("A", false)))).isEqualTo(true);
  }

  @Test
  void structuralComparisonPreservesGroupingOrderAndLiteralContents() {
    var a = attribute("A"); var b = attribute("B"); var c = attribute("C");
    var requested = node(IliConstraintSpec.ExpressionKind.AND, a, node(IliConstraintSpec.ExpressionKind.OR, b, c));
    assertThat(ConstraintExpressionComparison.matches(requested, new And(List.of(A, new Or(List.of(B, C)))))).isTrue();
    assertThat(ConstraintExpressionComparison.matches(requested, new Or(List.of(new And(List.of(A, B)), C)))).isFalse();
    assertThat(ConstraintExpressionComparison.matches(requested, new And(List.of(new Or(List.of(B, C)), A)))).isFalse();
    var text = node(IliConstraintSpec.ExpressionKind.TEXT); text.value = "a b(c)";
    assertThat(ConstraintExpressionComparison.matches(text, new TextLiteral("a b(c)"))).isTrue();
    assertThat(ConstraintExpressionComparison.matches(text, new TextLiteral("ab(c)"))).isFalse();
    assertThat(ConstraintExpressionComparison.matches(text, new TextLiteral("a bc"))).isFalse();
    assertThat(ConstraintExpressionComparison.matches(node(IliConstraintSpec.ExpressionKind.IMPLIES, a, b),
        new Or(List.of(new Not(A), B)))).isTrue();
    var plus = node(IliConstraintSpec.ExpressionKind.FUNCTION, attribute("X"), attribute("Y"));
    plus.name = "NUMERIC_ADD"; plus.functionOrigin = IliConstraintSpec.FunctionOrigin.STANDARD;
    var x = new Attribute("X", Type.scalar(ScalarKind.NUMERIC));
    var y = new Attribute("Y", Type.scalar(ScalarKind.NUMERIC));
    assertThat(ConstraintExpressionComparison.matches(plus, new FunctionCall(
        StandardFunctionRegistry.findBySemanticId("NUMERIC_SUB").orElseThrow().definition(), List.of(x, y)))).isFalse();
  }

  @Test
  void authorsNestedImplicationsWithVerifiedProofAndExactlyTwoCompilationsInBothVersions() {
    for (String version : List.of("2.3", "2.4")) {
      var compiler = new IliCompilerService();
      var counting = org.mockito.Mockito.spy(compiler);
      var calls = new AtomicInteger();
      org.mockito.Mockito.doAnswer(invocation -> { calls.incrementAndGet(); return invocation.callRealMethod(); })
          .when(counting).compile(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull(),
              org.mockito.ArgumentMatchers.anyString());
      var spec = new IliConstraintSpec.Mandatory();
      spec.name = "Rule";
      spec.condition = node(IliConstraintSpec.ExpressionKind.NOT,
          node(IliConstraintSpec.ExpressionKind.IMPLIES, attribute("A"),
              node(IliConstraintSpec.ExpressionKind.IMPLIES, attribute("B"), attribute("C"))));
      var result = new ConstraintAuthoringTools(engine(counting)).authorIliMandatoryConstraint(
          MODEL.formatted(version, ""), "Ordered.Data.Item", spec, null, null);
      assertThat(calls.get()).isEqualTo(2);
      assertThat(result.status).as(result.toString()).isEqualTo(IliAuthoringResult.Status.GENERATED);
      assertThat(result.proofVerified).isTrue();
      assertThat(result.updatedModelText).contains("NOT(", " OR ").doesNotContain(" IMPLIES ", "=>");
      assertThat(result.constraintProofs.getFirst().coverageComplete).isTrue();
      var ir = new Not(new Implies(A, new Implies(B, C)));
      var rendered = ir.toInterlis("2.3".equals(version) ? IliVersion.ILI_23 : IliVersion.ILI_24);
      var compiled = compiler.compile(MODEL.formatted(version, "!!@ name = \"Rule\"\nMANDATORY CONSTRAINT " + rendered + ";"), null);
      assertThat(compiled.valid()).as(compiled.messages().toString()).isTrue();
    }
  }

  @Test
  void nativeImplicationBoundaryPreventsAutomaticProofButExplicitValidatorResultsRemainVisible() {
    var compiler = new IliCompilerService();
    var verifier = new ConstraintTestTools(compiler);
    var generator = new ConstraintCaseGenerationTools(new ConstraintContextService(compiler), verifier);
    String nativeSource = MODEL.formatted("2.4", "MANDATORY CONSTRAINT Rule: NOT(A => B);");
    var blocked = generator.generateIliConstraintCases(nativeSource, "Ordered.Data.Item.Rule");
    assertThat(blocked.get("reasonCode")).isEqualTo(ConstraintValidatorCompatibility.NATIVE_IMPLICATION);
    assertThat(blocked.get("generationVerified")).isEqualTo(false);
    assertThat(blocked.get("coverageComplete")).isEqualTo(false);
    var typed = IliAuthoringResult.constraintProof("Ordered.Data.Item.Rule", false, blocked);
    assertThat(typed.reasonCode).isEqualTo(ConstraintValidatorCompatibility.NATIVE_IMPLICATION);
    var test = new ConstraintTestTools.TestCase(); test.name = "false antecedent"; test.expectedConstraintValid = false;
    var object = new ConstraintTestTools.TestObject(); object.classFqn = "Ordered.Data.Item";
    object.values = Map.of("A", false, "B", false); test.objects = List.of(object);
    var actual = verifier.testIliConstraint(nativeSource, "Ordered.Data.Item.Rule", List.of(test));
    assertThat(actual.get("allPassed")).isEqualTo(false); // pinned validator incorrectly accepts NOT(false => false)
    var fixed = verifier.testIliConstraint(nativeSource.replace("A => B", "NOT(A) OR B"),
        "Ordered.Data.Item.Rule", List.of(test));
    assertThat(fixed.get("allPassed")).isEqualTo(true);
    for (String constraint : List.of("UNIQUE WHERE A => B: Label;",
        "SET CONSTRAINT WHERE A => B: INTERLIS.objectCount(ALL) >= 1;")) {
      var response = generator.generateIliConstraintCases(MODEL.formatted("2.4", constraint), "Ordered.Data.Item.Constraint1");
      assertThat(response.get("reasonCode")).as(response.toString()).isEqualTo(ConstraintValidatorCompatibility.NATIVE_IMPLICATION);
    }
  }

  private static IliConstraintSpec.ExpressionSpec attribute(String name) {
    var spec = node(IliConstraintSpec.ExpressionKind.ATTRIBUTE); spec.name = name; return spec;
  }

  private static IliConstraintSpec.ExpressionSpec node(IliConstraintSpec.ExpressionKind kind,
      IliConstraintSpec.ExpressionSpec... children) {
    var spec = new IliConstraintSpec.ExpressionSpec(); spec.kind = kind; spec.children = List.of(children); return spec;
  }

  private static ConstraintAuthoringEngine engine(IliCompilerService compiler) {
    var analysis = new ModelAnalysisTools(compiler);
    return new ConstraintAuthoringEngine(new ConstraintAuthoringWorkflow(compiler),
        new IliSpecRenderer(new AttributeTools(), new DomainTools()),
        new ConstraintCaseGenerationTools(new ConstraintContextService(compiler), new ConstraintTestTools(compiler)),
        new ModelChangeReviewService(analysis, new ModelingRuleTools(new KnowledgeRuleLoader(), analysis, compiler)));
  }
}
