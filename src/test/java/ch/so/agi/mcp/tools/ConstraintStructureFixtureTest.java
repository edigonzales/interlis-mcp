package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.IliConstraintSpec;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.constraint.ConstraintFixtures;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConstraintStructureFixtureTest {
  private final IliCompilerService compiler = new IliCompilerService();
  private final ConstraintTestTools verifier = new ConstraintTestTools(compiler);
  private final ConstraintCaseGenerationTools generator = new ConstraintCaseGenerationTools(
      new ConstraintContextService(compiler), verifier);

  private static final String STRUCTURE = """
      STRUCTURE S =
        value : MANDATORY 0 .. 100;
        !!@ name = "Rule"
        MANDATORY CONSTRAINT value >= 50;
      END S;
      """;

  static String model(String body) {
    return "INTERLIS 2.3; MODEL StructureProof (en) AT \"https://example.org\" VERSION \"1\" =\n"
        + "TOPIC Data =\n" + body + "\nEND Data; END StructureProof.";
  }

  @ParameterizedTest
  @ValueSource(strings = {"s : S;", "s : BAG {0..3} OF S;", "s : LIST {2..3} OF S;"})
  void embedsDirectScalarBagAndListContexts(String attribute) {
    var proof = proof(model(STRUCTURE + "CLASS Owner = " + attribute + " END Owner;"));
    assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.PASSED);
    int count = attribute.contains("{2") ? 2 : 1;
    assertThat(proof.verification.cases).allSatisfy(c -> {
      assertThat(c.subjectCount).isEqualTo(count);
      assertThat(c.passed).isTrue();
      assertThat(c.xtfText).doesNotContain("<StructureProof.Data.S TID=");
    });
  }

  @Test
  void embedsNestedStructuresThroughInheritedOwnerAttributeAndFillsMandatorySiblings() {
    var proof = proof(model(STRUCTURE + """
        STRUCTURE Extra = code : MANDATORY 1 .. 5; END Extra;
        STRUCTURE Wrapper = s : LIST {2..3} OF S; extra : MANDATORY Extra; END Wrapper;
        CLASS Base (ABSTRACT) = wrappers : BAG {2..3} OF Wrapper; END Base;
        CLASS Owner EXTENDS Base = END Owner;
        """));
    assertThat(proof.status).as(proof.toString()).isEqualTo(IliAuthoringResult.ProofStatus.PASSED);
    assertThat(proof.generatedCases).allSatisfy(c -> {
      assertThat(c.ownerClassFqn).isEqualTo("StructureProof.Data.Owner");
      assertThat(c.structurePath).isEqualTo("wrappers->s");
    });
    assertThat(proof.verification.cases).allSatisfy(c -> {
      assertThat(c.subjectCount).isEqualTo(4);
      assertThat(c.xtfText).contains("<StructureProof.Data.Extra><code>1</code>");
    });
  }

  @Test
  void preservesReferenceTargetsWhenEmbeddingStructureRoot() {
    var proof = proof(model("""
        CLASS Target = value : MANDATORY 0 .. 100; END Target;
        STRUCTURE S = ref : MANDATORY REFERENCE TO Target;
          !!@ name = "Rule"
          MANDATORY CONSTRAINT ref->value >= 50;
        END S;
        CLASS Owner = s : S; END Owner;
        """));
    assertThat(proof.verification.cases).allSatisfy(c -> {
      assertThat(c.subjectCount).isEqualTo(1);
      assertThat(c.fixtureValid).isTrue();
      assertThat(c.xtfText).contains("<ref REF=", "<StructureProof.Data.Target TID=");
      assertThat(c.xtfText).doesNotContain("<StructureProof.Data.S TID=");
    });
  }

  @Test
  void selectsNextOwnerOnlyForFixtureFailure() {
    var proof = proof(model(STRUCTURE + """
        CLASS A = s : S; date : MANDATORY INTERLIS.XMLDate; END A;
        CLASS B = s : S; END B;
        """));
    assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.PASSED);
    assertThat(proof.generatedCases).allSatisfy(c -> assertThat(c.ownerClassFqn).endsWith(".B"));
  }

  @Test
  void semanticMismatchDoesNotRetryAnotherOwner() {
    int[] calls = {0};
    var mismatch = new ConstraintTestTools(compiler) {
      @Override Map<String, Object> testCompiledConstraint(CompiledConstraintContext context, List<TestCase> cases) {
        calls[0]++;
        return Map.of("allPassed", false, "cases", List.of(Map.of(
            "fixtureValid", true, "expectedConstraintValid", true, "actualConstraintValid", false)));
      }
    };
    var tools = new ConstraintCaseGenerationTools(new ConstraintContextService(compiler), mismatch);
    var response = tools.generateIliConstraintCases(model(STRUCTURE
        + "CLASS A = s : S; END A; CLASS B = s : S; END B;"), "StructureProof.Data.S.Rule");
    assertThat(calls[0]).isEqualTo(1);
    assertThat(response.get("generationVerified")).isEqualTo(false);
  }

  @Test
  void missingOwnerAndAbstractContextAreExplicitBoundaries() {
    var orphan = generator.generateIliConstraintCases(model(STRUCTURE), "StructureProof.Data.S.Rule");
    assertThat(orphan.get("reasonCode")).as(orphan.toString()).isEqualTo("STRUCTURE_OWNER_NOT_FOUND");
    var abstractTarget = generator.generateIliConstraintCases(model(
        STRUCTURE.replace("STRUCTURE S =", "STRUCTURE S (ABSTRACT) =")
            + "CLASS Owner = s : S; END Owner;"), "StructureProof.Data.S.Rule");
    assertThat(abstractTarget.get("reasonCode")).as(abstractTarget.toString()).isEqualTo("STRUCTURE_PATH_UNSUPPORTED");
  }

  @Test
  void refusesMoreThanFiveRequiredOccurrencesAndMoreThan64EmbeddedInstances() {
    var tooMany = generator.generateIliConstraintCases(model(STRUCTURE
        + "CLASS Owner = s : BAG {6..8} OF S; END Owner;"), "StructureProof.Data.S.Rule");
    assertThat(tooMany.get("reasonCode")).as(tooMany.toString()).isEqualTo("STRUCTURE_FIXTURE_BUDGET_EXCEEDED");
    var recursive = generator.generateIliConstraintCases(model(STRUCTURE + """
        STRUCTURE W1 = s : BAG {5..5} OF S; END W1;
        STRUCTURE W2 = w : BAG {5..5} OF W1; END W2;
        CLASS Owner = w : BAG {3..3} OF W2; END Owner;
        """), "StructureProof.Data.S.Rule");
    assertThat(recursive.get("reasonCode")).as(recursive.toString()).isEqualTo("STRUCTURE_FIXTURE_BUDGET_EXCEEDED");
  }

  @Test
  void refusesNineCompositionSteps() {
    StringBuilder body = new StringBuilder(STRUCTURE);
    String child = "S";
    for (int i = 1; i <= 8; i++) {
      body.append("STRUCTURE W").append(i).append(" = s : ").append(child).append("; END W").append(i).append(";\n");
      child = "W" + i;
    }
    body.append("CLASS Owner = s : W8; END Owner;");
    var response = generator.generateIliConstraintCases(model(body.toString()), "StructureProof.Data.S.Rule");
    assertThat(response.get("reasonCode")).as(response.toString()).isEqualTo("STRUCTURE_FIXTURE_BUDGET_EXCEEDED");
  }

  @Test
  void missingStructureAndEmptyCompositionDoNotExerciseConstraint() {
    String model = model(STRUCTURE + "CLASS Owner = s : BAG {0..3} OF S; END Owner;");
    var results = verify(model, List.of(
        testCase("absent", true, Map.of()),
        testCase("empty", true, Map.of("s", List.of())),
        testCase("two", false, Map.of("s", List.of(Map.of("value", 60), Map.of("value", 20))))));
    assertThat(results.subList(0, 2)).allSatisfy(c -> {
      assertThat(c.get("fixtureValid")).isEqualTo(true);
      assertThat(c.get("constraintExercised")).isEqualTo(false);
      assertThat(c.get("subjectCount")).isEqualTo(0);
      assertThat(c.get("passed")).isEqualTo(false);
    });
    assertThat(results.get(2)).containsEntry("subjectCount", 2).containsEntry("targetViolationCount", 1).containsEntry("passed", true);
  }

  @Test
  void explicitMissingMandatoryValueAndEmptyMandatoryCompositionAreNotFilled() {
    var missing = new LinkedHashMap<String, Object>();
    missing.put("value", null);
    String model = model(STRUCTURE + "CLASS Owner = s : BAG {1..3} OF S; END Owner;");
    var results = verify(model, List.of(
        testCase("null endpoint", true, Map.of("s", missing)),
        testCase("empty mandatory", true, Map.of("s", List.of()))));
    assertThat(results).allSatisfy(c -> {
      assertThat(c.get("fixtureValid")).isEqualTo(false);
      assertThat(c.get("passed")).isEqualTo(false);
      assertThat((List<?>)c.get("fixtureErrors")).isNotEmpty();
    });
    assertThat(results.getFirst().get("xtfText").toString()).doesNotContain("<value>");
  }

  @Test
  void countsInheritedClassConstraintOnConcreteInstances() {
    String model = model("""
        CLASS Base (ABSTRACT) = value : MANDATORY 0 .. 100;
          !!@ name = "Rule"
          MANDATORY CONSTRAINT value >= 50;
        END Base;
        CLASS Owner EXTENDS Base = END Owner;
        """);
    var response = verifier.testIliConstraint(model, "StructureProof.Data.Base.Rule",
        List.of(testCase("inherited", false, Map.of("value", 20))));
    var c = ((List<Map<String,Object>>)response.get("cases")).getFirst();
    assertThat(c).containsEntry("subjectCount", 1).containsEntry("passed", true);
  }

  @Test
  void typedAuthoringEmbedsStructureAndRetainsDiagnostics() {
    var spec = new ObjectMapper().readValue("""
        {"kind":"MANDATORY","name":"Rule","condition":{"kind":"COMPARE","operator":">=",
         "children":[{"kind":"ATTRIBUTE","name":"value"},{"kind":"NUMERIC","value":50}]}}
        """, IliConstraintSpec.class);
    var result = ConstraintFixtures.authoring(compiler).authorIliConstraint(model(
        "STRUCTURE S = value : MANDATORY 0 .. 100; END S; CLASS Owner = s : S; END Owner;"),
        "StructureProof.Data.S", spec, null, null, null);
    assertThat(result.status).as(result.toString()).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(result.constraintProofs.getFirst().generatedCases).allSatisfy(c -> assertThat(c.structurePath).isEqualTo("s"));
    assertThat(result.constraintProofs.getFirst().verification.cases).allSatisfy(c -> {
      assertThat(c.subjectCount).isEqualTo(1);
      assertThat(c.fixtureValid).isTrue();
      assertThat(c.expectedConstraintValid).isEqualTo(c.actualConstraintValid);
    });
  }

  record AuthoringRequest(String contextFqn, IliConstraintSpec spec) {}

  @Test
  void publicP06WithExplicitPresenceGuardsProvesEmptyRelationship() throws Exception {
    var request = ConstraintFixtures.arguments("P06", AuthoringRequest.class);
    String model = ConstraintFixtures.withoutRule("P06");
    var localCompiler = new IliCompilerService(ConstraintFixtures.repositories());
    var localVerifier = new ConstraintTestTools(localCompiler);
    var result = ConstraintFixtures.authoring(localCompiler).authorIliConstraint(
        model, request.contextFqn(), request.spec(), null, null, null);
    assertThat(result.status).as(result.toString()).isEqualTo(IliAuthoringResult.Status.GENERATED);
    var cases = new ArrayList<ConstraintTestTools.TestCase>();
    for (int weight : List.of(100, 99)) {
      var c = testCase("no secondary " + weight, weight == 100, Map.of("Gewichtung", weight));
      c.objects.getFirst().classFqn = request.contextFqn();
      cases.add(c);
    }
    var verification = localVerifier.testIliConstraint(result.updatedModelText,
        request.contextFqn()+"."+request.spec().name, cases);
    assertThat(verification.get("allPassed")).as(verification.toString()).isEqualTo(true);
    var checked = new ObjectMapper().valueToTree(verification).path("cases");
    assertThat(checked.get(0).path("targetViolationCount").asInt()).isZero();
    assertThat(checked.get(1).path("targetViolationCount").asInt()).isEqualTo(1);
    for (var test : checked) {
      assertThat(test.path("fixtureValid").asBoolean()).isTrue();
      assertThat(test.path("constraintExercised").asBoolean()).isTrue();
      assertThat(test.path("baskets").findValues("objectCount").stream()
          .map(value -> value.asInt()).toList()).containsExactly(2, 2);
      assertThat(test.path("baskets").findValues("associationObjectCount").stream()
          .map(value -> value.asInt()).toList()).containsExactly(1, 0);
    }
  }

  @Test
  void typedAuthoringProvesEmptyCompositionInsideStructure() {
    String model = model("""
        STRUCTURE Part = value : MANDATORY 0 .. 100; END Part;
        STRUCTURE S = weight : MANDATORY 0 .. 100; parts : BAG {0..3} OF Part; END S;
        CLASS Owner = s : S; END Owner;
        """).replace("MODEL StructureProof", """
        CONTRACTED TYPE MODEL Math (en) AT "https://example.org" VERSION "1" =
          FUNCTION sum(attributePath: TEXT): NUMERIC;
          FUNCTION add(a: NUMERIC; b: NUMERIC): NUMERIC;
        END Math.
        MODEL StructureProof""").replace("TOPIC Data =", "IMPORTS Math; TOPIC Data =");
    var summary = """
        {"kind":"FUNCTION","name":"COLLECTION_SUM","functionOrigin":"STANDARD","children":[{"kind":"PATH","name":"parts->value"}]}
        """;
    var spec = new ObjectMapper().readValue("""
        {"kind":"MANDATORY","name":"Rule","condition":{"kind":"OR","children":[
          {"kind":"AND","children":[{"kind":"DEFINED","children":[%1$s]},
            {"kind":"COMPARE","operator":"==","children":[
              {"kind":"FUNCTION","name":"NUMERIC_ADD","functionOrigin":"STANDARD","children":[%1$s,{"kind":"ATTRIBUTE","name":"weight"}]},
              {"kind":"NUMERIC","value":100}]}]},
          {"kind":"AND","children":[{"kind":"NOT","children":[{"kind":"DEFINED","children":[%1$s]}]},
            {"kind":"COMPARE","operator":"==","children":[{"kind":"ATTRIBUTE","name":"weight"},{"kind":"NUMERIC","value":100}]}]}
        ]}}
        """.formatted(summary), IliConstraintSpec.class);
    var result = ConstraintFixtures.authoring(compiler).authorIliConstraint(
        model, "StructureProof.Data.S", spec, null, null, null);
    assertThat(result.status).as(result.toString()).isEqualTo(IliAuthoringResult.Status.GENERATED);
    var verification = verify(result.updatedModelText, List.of(
        testCase("empty good", true, Map.of("s", Map.of("weight", 100, "parts", List.of()))),
        testCase("empty bad", false, Map.of("s", Map.of("weight", 99, "parts", List.of())))));
    assertThat(verification).allSatisfy(c -> assertThat(c).containsEntry("passed", true).containsEntry("subjectCount", 1));
  }

  @Test
  void cyclicCompositionSearchIsBoundedWithoutInvokingCompilerIntegrityRecursion() throws Exception {
    // ili2c itself recursively checks cyclic composition dependencies. Exercise our
    // resolver directly on the metamodel so this regression isolates our traversal.
    var td = org.mockito.Mockito.mock(ch.interlis.ili2c.metamodel.TransferDescription.class);
    var model = org.mockito.Mockito.mock(ch.interlis.ili2c.metamodel.DataModel.class);
    var target = org.mockito.Mockito.mock(ch.interlis.ili2c.metamodel.Table.class);
    var recursive = org.mockito.Mockito.mock(ch.interlis.ili2c.metamodel.Table.class);
    var owner = org.mockito.Mockito.mock(ch.interlis.ili2c.metamodel.Table.class);
    org.mockito.Mockito.when(td.iterator()).thenAnswer(call -> List.of(model).iterator());
    org.mockito.Mockito.when(model.iterator()).thenAnswer(call -> List.of(target, recursive, owner).iterator());
    org.mockito.Mockito.when(target.getScopedName(null)).thenReturn("Cycle.Data.S");
    org.mockito.Mockito.when(owner.getScopedName(null)).thenReturn("Cycle.Data.Owner");
    org.mockito.Mockito.when(owner.isIdentifiable()).thenReturn(true);
    org.mockito.Mockito.when(owner.getContainer(ch.interlis.ili2c.metamodel.Topic.class))
        .thenAnswer(call -> new ch.interlis.ili2c.metamodel.Topic());
    var attribute = org.mockito.Mockito.mock(ch.interlis.ili2c.metamodel.LocalAttribute.class);
    var type = org.mockito.Mockito.mock(ch.interlis.ili2c.metamodel.CompositionType.class);
    org.mockito.Mockito.when(attribute.getName()).thenReturn("child");
    org.mockito.Mockito.when(attribute.getDomainOrDerivedDomain()).thenReturn(type);
    org.mockito.Mockito.when(type.getCardinality()).thenReturn(new ch.interlis.ili2c.metamodel.Cardinality(0, 1));
    org.mockito.Mockito.when(type.getComponentType()).thenReturn(recursive);
    org.mockito.Mockito.when(owner.getAttributes()).thenAnswer(call -> List.of(attribute).iterator());
    org.mockito.Mockito.when(recursive.getAttributes()).thenAnswer(call -> List.of(attribute).iterator());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ConstraintFixtureContextResolver.resolve(td, target))
        .isInstanceOfSatisfying(ConstraintFixtureException.class,
            ex -> assertThat(ex.reasonCode()).isEqualTo("STRUCTURE_PATH_UNSUPPORTED"));
  }

  @Test
  void limitsMandatorySiblingMaterializationIncludingStructuresOutsideTargetPath() {
    var response = generator.generateIliConstraintCases(model(STRUCTURE + """
        STRUCTURE Extra = value : MANDATORY 0 .. 1; END Extra;
        STRUCTURE W1 = items : BAG {5..5} OF Extra; END W1;
        STRUCTURE W2 = items : BAG {5..5} OF W1; END W2;
        CLASS Owner = s : S; extra : BAG {3..3} OF W2; END Owner;
        """), "StructureProof.Data.S.Rule");
    assertThat(response.get("reasonCode")).as(response.toString()).isEqualTo("STRUCTURE_FIXTURE_BUDGET_EXCEEDED");
  }

  @Test
  void considersOnlyEightRoutesInStableOrder() {
    StringBuilder owners = new StringBuilder();
    for (int i = 0; i < 8; i++) owners.append("CLASS A").append(i)
        .append(" = s : S; date : MANDATORY INTERLIS.XMLDate; END A").append(i).append(";\n");
    owners.append("CLASS Z = s : S; END Z;");
    String fqn = "StructureProof.Data.S.Rule";
    var resolution = new ConstraintContextService(compiler).compileAndResolve(
        model(STRUCTURE + owners), fqn, null, "ili2c_structure_route_budget_");
    var proof = generator.prove(fqn, resolution, false);
    assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.INCOMPLETE);
    assertThat(proof.generatedCases).allSatisfy(c -> assertThat(c.ownerClassFqn).endsWith(".A7"));
    assertThat(proof.verification.cases).allSatisfy(c -> {
      assertThat(c.fixturePreparationReasonCode).isEqualTo("FIXTURE_MATERIALIZATION_FAILED");
      assertThat(c.fixtureValid).isFalse();
      assertThat(c.fixtureErrors).isNotEmpty();
    });
  }

  private IliAuthoringResult.ConstraintProof proof(String model) {
    var response = generator.generateIliConstraintCases(model, "StructureProof.Data.S.Rule", true);
    assertThat(response.get("generationVerified")).as(response.toString()).isEqualTo(true);
    return IliAuthoringResult.constraintProof("StructureProof.Data.S.Rule", IliAuthoringResult.ProofStatus.PASSED, response);
  }

  private List<Map<String,Object>> verify(String model, List<ConstraintTestTools.TestCase> cases) {
    return (List<Map<String,Object>>)verifier.testIliConstraint(model, "StructureProof.Data.S.Rule", cases).get("cases");
  }

  private ConstraintTestTools.TestCase testCase(String name, boolean expected, Map<String,Object> values) {
    var c = new ConstraintTestTools.TestCase();
    c.name = name; c.expectedConstraintValid = expected;
    var o = new ConstraintTestTools.TestObject();
    o.classFqn = "StructureProof.Data.Owner"; o.values = values;
    c.objects = List.of(o);
    return c;
  }
}
