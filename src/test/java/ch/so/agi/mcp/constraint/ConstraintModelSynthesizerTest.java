package ch.so.agi.mcp.constraint;

import static ch.so.agi.mcp.constraint.ConstraintExpression.ComparisonOperator.EQ;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.BOOLEAN;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.ENUM;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.NUMERIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.ConstraintTestTools;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintModelSynthesizerTest {

  private static final String MODEL = """
      INTERLIS 2.3;

      CONTRACTED TYPE MODEL Math (en) AT "http://www.interlis.ch/models"
      VERSION "2018-11-19" =
        FUNCTION add(a: NUMERIC; b: NUMERIC): NUMERIC;
        FUNCTION sum(attributePath: TEXT): NUMERIC;
      END Math.

      MODEL SynthesisModel (en)
      AT "http://example.org/models"
      VERSION "2026-08-18" =
        IMPORTS Math;

        TOPIC Data =
          CLASS Main =
            Gewichtung : MANDATORY 0 .. 100;
            Status : MANDATORY (draft, active);
            Aktiv : BOOLEAN;
          END Main;

          CLASS Secondary =
            Gewichtung : MANDATORY 0 .. 100;
          END Secondary;

          ASSOCIATION MainSecondary =
            Hauptobjekt -- {1} Main;
            Nebenauspraegung -- {0..3} Secondary;
          END MainSecondary;

          CONSTRAINTS OF SynthesisModel.Data.Main =
            !!@ name = "WeightSum100"
            MANDATORY CONSTRAINT
              (
                DEFINED(Math.sum("Nebenauspraegung->Gewichtung"))
                AND Math.add(Math.sum("Nebenauspraegung->Gewichtung"), Gewichtung) == 100
              )
              OR
              (
                Gewichtung == 100
                AND NOT(DEFINED(Math.sum("Nebenauspraegung->Gewichtung")))
              );
          END;
        END Data;
      END SynthesisModel.
      """;

  private static final String CONTEXT = "SynthesisModel.Data.Main";

  private final IliCompilerService compilerService = new IliCompilerService();

  @Test
  void bindsAfuReferencesToDomainsAndAssociationCardinality() {
    ConstraintModelSynthesizer.ModelBinding binding = binding(afuExpression());

    ConstraintModelSynthesizer.ReferenceBinding main = binding.reference("Gewichtung");
    assertEquals(NUMERIC, main.domain().kind());
    assertTrue(main.domain().mandatory());
    assertEquals(BigDecimal.ZERO, main.domain().numeric().minimum());
    assertEquals(BigDecimal.valueOf(100), main.domain().numeric().maximum());
    assertEquals(BigDecimal.ONE, main.domain().numeric().step());
    assertEquals(false, main.associationPath());

    ConstraintModelSynthesizer.ReferenceBinding secondary =
        binding.reference("Nebenauspraegung->Gewichtung");
    assertEquals(NUMERIC, secondary.domain().kind());
    assertTrue(secondary.reference().type().collection());
    assertTrue(secondary.associationPath());
    assertEquals("SynthesisModel.Data.MainSecondary", secondary.association().associationFqn());
    assertEquals("Nebenauspraegung", secondary.association().roleName());
    assertEquals("Hauptobjekt", secondary.association().oppositeRoleName());
    assertEquals("SynthesisModel.Data.Secondary", secondary.association().targetClassFqn());
    assertEquals(0, secondary.association().minimum());
    assertEquals(3, secondary.association().maximum());
  }

  @Test
  void materializesAssignmentsAsRealAssociationObjectGraphs() {
    ConstraintExpression expression = afuExpression();
    ConstraintModelSynthesizer.ModelBinding binding = binding(expression);

    Map<String, Object> withSecondary = Map.of(
        "Gewichtung", BigDecimal.valueOf(60),
        "Nebenauspraegung->Gewichtung", List.of(BigDecimal.valueOf(20), BigDecimal.valueOf(20)));
    ConstraintModelSynthesizer.ObjectGraph graph = ConstraintModelSynthesizer.synthesize(
        binding, withSecondary, "case1");

    assertEquals(3, graph.objects().size());
    assertEquals(2, graph.links().size());
    assertEquals(CONTEXT, graph.objects().getFirst().classFqn());
    assertEquals(BigDecimal.valueOf(60), graph.objects().getFirst().values().get("Gewichtung"));
    assertEquals(BigDecimal.valueOf(20), graph.objects().get(1).values().get("Gewichtung"));
    assertEquals(BigDecimal.valueOf(20), graph.objects().get(2).values().get("Gewichtung"));
    assertTrue(graph.links().stream().allMatch(link ->
        "SynthesisModel.Data.MainSecondary".equals(link.associationFqn())
            && "case1_root".equals(link.roles().get("Hauptobjekt"))));

    assertTrue(ConstraintExpressionEngine.evaluateConstraint(
        expression,
        ConstraintExpressionEngine.EvaluationContext.of(withSecondary)));

    Map<String, Object> withoutSecondary = Map.of(
        "Gewichtung", BigDecimal.valueOf(100),
        "Nebenauspraegung->Gewichtung", List.of());
    ConstraintModelSynthesizer.ObjectGraph emptyGraph = ConstraintModelSynthesizer.synthesize(
        binding, withoutSecondary, "case2");
    assertEquals(1, emptyGraph.objects().size());
    assertEquals(0, emptyGraph.links().size());
    assertTrue(ConstraintExpressionEngine.evaluateConstraint(
        expression,
        ConstraintExpressionEngine.EvaluationContext.of(withoutSecondary)));
  }

  @Test
  void synthesizedAfuGraphsAreAcceptedByTheRealValidator() {
    ConstraintExpression expression = afuExpression();
    ConstraintModelSynthesizer.ModelBinding binding = binding(expression);

    List<ConstraintTestTools.TestCase> cases = new ArrayList<>();
    cases.add(testCase(
        "defined sum witness",
        true,
        ConstraintModelSynthesizer.synthesize(
            binding,
            Map.of(
                "Gewichtung", BigDecimal.valueOf(60),
                "Nebenauspraegung->Gewichtung", List.of(BigDecimal.valueOf(20), BigDecimal.valueOf(20))),
            "proof1")));
    cases.add(testCase(
        "defined sum counterexample",
        false,
        ConstraintModelSynthesizer.synthesize(
            binding,
            Map.of(
                "Gewichtung", BigDecimal.valueOf(60),
                "Nebenauspraegung->Gewichtung", List.of(BigDecimal.valueOf(20))),
            "proof2")));
    cases.add(testCase(
        "undefined sum witness",
        true,
        ConstraintModelSynthesizer.synthesize(
            binding,
            Map.of(
                "Gewichtung", BigDecimal.valueOf(100),
                "Nebenauspraegung->Gewichtung", List.of()),
            "proof3")));

    ConstraintTestTools tools = new ConstraintTestTools(compilerService);
    Map<String, Object> result = tools.testIliConstraint(MODEL, "WeightSum100", cases, null);

    assertEquals(true, result.get("allPassed"), String.valueOf(result));
    assertEquals(3, result.get("passedCount"));
  }

  @Test
  void bindsBooleanAndEnumDomainsWithoutDecisionTableKnowledge() {
    ConstraintExpression expression = new ConstraintExpression.And(List.of(
        new ConstraintExpression.Comparison(
            EQ,
            new ConstraintExpression.Attribute("Status", ConstraintExpression.Type.scalar(ENUM)),
            new ConstraintExpression.EnumLiteral("active")),
        new ConstraintExpression.Comparison(
            EQ,
            new ConstraintExpression.Attribute("Aktiv", ConstraintExpression.Type.scalar(BOOLEAN)),
            new ConstraintExpression.BooleanLiteral(true))));

    ConstraintModelSynthesizer.ModelBinding binding = binding(expression);
    assertEquals(List.of("draft", "active"), binding.reference("Status").domain().values());
    assertTrue(binding.reference("Status").domain().mandatory());
    assertEquals(BOOLEAN, binding.reference("Aktiv").domain().kind());
    assertEquals(false, binding.reference("Aktiv").domain().mandatory());

    ConstraintModelSynthesizer.ObjectGraph graph = ConstraintModelSynthesizer.synthesize(
        binding,
        Map.of("Status", "active", "Aktiv", ConstraintExpressionEngine.Undefined.INSTANCE),
        "categories");
    assertEquals(Map.of("Status", "active"), graph.objects().getFirst().values());
  }

  @Test
  void rejectsAssignmentsOutsideModelDomainsOrAssociationCardinality() {
    ConstraintModelSynthesizer.ModelBinding binding = binding(afuExpression());

    assertThrows(
        IllegalArgumentException.class,
        () -> ConstraintModelSynthesizer.synthesize(
            binding,
            Map.of(
                "Gewichtung", BigDecimal.valueOf(101),
                "Nebenauspraegung->Gewichtung", List.of()),
            "badDomain"));

    assertThrows(
        IllegalArgumentException.class,
        () -> ConstraintModelSynthesizer.synthesize(
            binding,
            Map.of(
                "Gewichtung", BigDecimal.valueOf(60),
                "Nebenauspraegung->Gewichtung",
                List.of(
                    BigDecimal.TEN,
                    BigDecimal.TEN,
                    BigDecimal.TEN,
                    BigDecimal.TEN)),
            "badCardinality"));
  }

  private ConstraintModelSynthesizer.ModelBinding binding(ConstraintExpression expression) {
    IliCompilerService.CompilationResult compilation = compilerService.compile(
        MODEL, null, "ili2c_constraint_synthesis_test_");
    assertTrue(compilation.valid(), String.valueOf(compilation.messages()));
    return ConstraintModelSynthesizer.bind(
        compilation.transferDescription(), CONTEXT, expression);
  }

  private ConstraintExpression afuExpression() {
    ConstraintExpression.Path secondary = new ConstraintExpression.Path(
        "Nebenauspraegung->Gewichtung",
        ConstraintExpression.Type.collection(NUMERIC));
    ConstraintExpression.FunctionCall sum = call("COLLECTION_SUM", secondary);
    ConstraintExpression.Attribute main = new ConstraintExpression.Attribute(
        "Gewichtung", ConstraintExpression.Type.scalar(NUMERIC));
    ConstraintExpression.NumericLiteral hundred = new ConstraintExpression.NumericLiteral(100);
    ConstraintExpression.FunctionCall total = call("NUMERIC_ADD", sum, main);

    return new ConstraintExpression.Or(List.of(
        new ConstraintExpression.And(List.of(
            new ConstraintExpression.Defined(sum),
            new ConstraintExpression.Comparison(EQ, total, hundred))),
        new ConstraintExpression.And(List.of(
            new ConstraintExpression.Comparison(EQ, main, hundred),
            new ConstraintExpression.Not(new ConstraintExpression.Defined(sum))))));
  }

  private ConstraintExpression.FunctionCall call(
      String semanticId,
      ConstraintExpression... arguments) {
    ConstraintExpression.FunctionDefinition definition = StandardFunctionRegistry.findBySemanticId(semanticId)
        .orElseThrow()
        .definition();
    return new ConstraintExpression.FunctionCall(definition, List.of(arguments));
  }

  private ConstraintTestTools.TestCase testCase(
      String name,
      boolean expected,
      ConstraintModelSynthesizer.ObjectGraph graph) {
    ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
    testCase.name = name;
    testCase.expectedConstraintValid = expected;
    testCase.objects = graph.objects().stream().map(object -> {
      ConstraintTestTools.TestObject result = new ConstraintTestTools.TestObject();
      result.classFqn = object.classFqn();
      result.oid = object.oid();
      result.values = object.values();
      return result;
    }).toList();
    testCase.links = graph.links().stream().map(link -> {
      ConstraintTestTools.TestLink result = new ConstraintTestTools.TestLink();
      result.associationFqn = link.associationFqn();
      result.roles = link.roles();
      return result;
    }).toList();
    return testCase;
  }
}
