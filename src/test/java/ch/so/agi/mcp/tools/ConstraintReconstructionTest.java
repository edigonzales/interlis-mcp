package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.interlis.ili2c.generator.Interlis2Generator;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.Constraint;
import ch.interlis.ili2c.metamodel.Constant;
import ch.interlis.ili2c.metamodel.PlausibilityConstraint;
import ch.interlis.ili2c.metamodel.Container;
import ch.interlis.ili2c.metamodel.EnumerationType;
import ch.interlis.ili2c.metamodel.Evaluable;
import ch.interlis.ili2c.metamodel.Expression;
import ch.interlis.ili2c.metamodel.FunctionCall;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.ObjectPath;
import ch.interlis.ili2c.metamodel.Objects;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.constraint.ConstraintFixtures;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
class ConstraintReconstructionTest {
  @DynamicPropertySource
  static void repositories(DynamicPropertyRegistry properties) {
    properties.add("interlis.mcp.model-repositories", ConstraintFixtures::repositories);
  }

  @Autowired @Qualifier("toolSpecs")
  List<SyncToolSpecification> tools;

  @Autowired
  IliCompilerService compiler;

  private final ObjectMapper mapper = new ObjectMapper();

  @SuppressWarnings("unchecked")
  private Map<String, Object> author(Map<String, Object> arguments) {
    String name = "authorIliConstraint";
    var tool = tools.stream().filter(s -> name.equals(s.tool().name())).findFirst().orElseThrow();
    var response = tool.callHandler().apply(null, new McpSchema.CallToolRequest(name, arguments));
    assertThat(response.isError()).as("%s", response.content()).isFalse();
    assertThat(response.structuredContent()).isInstanceOf(Map.class);
    return (Map<String, Object>) response.structuredContent();
  }

  @Test
  void provesNumericUnionAndBooleanLiterals() {
    String model = """
        INTERLIS 2.3;
        MODEL ScalarDemo (en) AT "https://example.org" VERSION "1" =
          TOPIC Data =
            CLASS Item =
              value : MANDATORY 0..100;
              enabled : MANDATORY BOOLEAN;
            END Item;
          END Data;
        END ScalarDemo.
        """;
    var range = authorCondition(model, "ScalarDemo.Data.Item", node("OR", List.of(
        node("AND", List.of(compare(attribute("value"), ">=", 10), compare(attribute("value"), "<=", 20))),
        node("AND", List.of(compare(attribute("value"), ">=", 90), compare(attribute("value"), "<=", 95))))));
    assertCases(range, "value", Map.of(9, false, 10, true, 20, true, 21, false, 89, false, 90, true, 95, true, 96, false));

    var bool = authorCondition(model, "ScalarDemo.Data.Item", Map.of(
        "kind", "COMPARE", "operator", "==",
        "children", List.of(attribute("enabled"), Map.of("kind", "BOOLEAN", "value", true))));
    var generated = passedProof(bool).path("generatedCases");
    assertThat(generated).hasSize(2).allSatisfy(test ->
        assertThat(test.path("expectedConstraintValid").asBoolean())
            .isEqualTo(test.path("values").path("enabled").asBoolean()));
  }

  @ParameterizedTest
  @CsvSource({"2.3, Math", "2.4, Math_V2"})
  void provesSingleValuedPathsAndMultiValuedSums(String version, String math) throws Exception {
    String model = """
        INTERLIS %1$s;
        CONTRACTED TYPE MODEL %2$s (en) AT "http://www.interlis.ch/models" VERSION "1" =
          FUNCTION sum(attributePath: TEXT): NUMERIC;
        END %2$s.
        MODEL LinkedDemo (en) AT "https://example.org" VERSION "1" =
          IMPORTS %2$s;
          TOPIC Data =
            CLASS Main = Gewichtung : MANDATORY 0..100; END Main;
            CLASS Side = Gewichtung : MANDATORY 0..100; END Side;
            ASSOCIATION Link =
              main -- {1} Main;
              Nebenauspraegung -- {0..3} Side;
            END Link;
          END Data;
        END LinkedDemo.
        """.formatted(version, math);
    var path = Map.of("kind", "PATH", "name", "main->Gewichtung");
    var single = authorCondition(model, "LinkedDemo.Data.Side",
        node("AND", List.of(compare(path, ">=", 10), compare(path, "<=", 20))));
    assertCases(single, "main->Gewichtung", Map.of(9, false, 10, true, 20, true, 21, false));
    assertThat(passedProof(single).path("generatedCases")).allSatisfy(test ->
        assertThat(test.path("associationLinkCount").asInt()).isEqualTo(1));

    var sumPath = Map.of("kind", "PATH", "name", "Nebenauspraegung->Gewichtung");
    var sum = Map.of("kind", "FUNCTION", "functionOrigin", "STANDARD", "name", "COLLECTION_SUM",
        "children", List.of(sumPath));
    var aggregate = authorCondition(model, "LinkedDemo.Data.Main", compare(sum, "==", 150));
    var cases = passedProof(aggregate).path("generatedCases");
    assertThat(cases).anySatisfy(test -> {
      int total = 0;
      for (var value : test.path("values").path("Nebenauspraegung->Gewichtung")) total += value.asInt();
      assertThat(total).isEqualTo(150);
      assertThat(test.path("expectedConstraintValid").asBoolean()).isTrue();
      assertThat(test.path("associationLinkCount").asInt()).isGreaterThanOrEqualTo(2);
    }).anySatisfy(test -> {
      assertThat(test.path("associationLinkCount").asInt()).isZero();
      assertThat(test.path("expectedConstraintValid").asBoolean()).isTrue();
    });

    if ("2.4".equals(version)) {
      Map<String, Object> request = ConstraintFixtures.arguments("P06", Map.class);
      request.put("contextFqn", "LinkedDemo.Data.Main");
      request.put("modelText", model);
      var guarded = author(request);
      passedProof(guarded);
      assertThat(guarded.get("updatedModelText").toString())
          .contains("Math_V2.sum(\"Nebenauspraegung->Gewichtung\") + Gewichtung", "NOT(DEFINED");
    }

    var invalid = authorCondition(model, "LinkedDemo.Data.Main", compare(sumPath, ">=", 10));
    assertThat(invalid).containsEntry("status", "AST_ROUND_TRIP_FAILED")
        .containsEntry("reasonCode", "UNSUPPORTED_CONSTRAINT_SEMANTICS")
        .doesNotContainKey("updatedModelText");
  }

  private Map<String, Object> authorCondition(String model, String context, Map<String, Object> condition) {
    return author(Map.of("modelText", model, "contextFqn", context,
        "spec", Map.of("kind", "MANDATORY", "name", "Rule", "condition", condition)));
  }

  private static Map<String, Object> attribute(String name) {
    return Map.of("kind", "ATTRIBUTE", "name", name);
  }

  private static Map<String, Object> compare(Map<String, ?> left, String operator, int right) {
    return Map.of("kind", "COMPARE", "operator", operator,
        "children", List.of(left, Map.of("kind", "NUMERIC", "value", right)));
  }

  private static Map<String, Object> node(String kind, List<Map<String, Object>> children) {
    return Map.of("kind", kind, "children", children);
  }

  private void assertCases(Map<String, Object> result, String attribute, Map<Integer, Boolean> expected) {
    var generated = passedProof(result).path("generatedCases");
    expected.forEach((value, valid) -> assertThat(generated).anySatisfy(test -> {
      assertThat(test.path("values").path(attribute).asText()).isEqualTo(value.toString());
      assertThat(test.path("expectedConstraintValid").asBoolean()).isEqualTo(valid);
    }));
  }

  private tools.jackson.databind.JsonNode passedProof(Map<String, Object> result) {
    assertThat(result).containsEntry("status", "GENERATED");
    var proof = mapper.valueToTree(result.get("constraintProofs")).get(0);
    assertThat(proof.path("status").asText()).isEqualTo("PASSED");
    assertThat(proof.path("coverageComplete").asBoolean()).isTrue();
    assertThat(proof.path("verification").path("cases")).isNotEmpty().allSatisfy(test -> {
      assertThat(test.path("constraintExercised").asBoolean()).isTrue();
      assertThat(test.path("fixtureValid").asBoolean()).isTrue();
      assertThat(test.path("passed").asBoolean()).isTrue();
      assertThat(test.path("targetViolationCount").asInt()).isEqualTo(
          test.path("expectedConstraintValid").asBoolean() ? 0 : 1);
    });
    return proof;
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource({
      "P01, CheckKoernungsklasseUbTongehalt_Land",
      "P02, UntertypENachPH_Unterboden_Wald_Neben",
      "P03, HauptHatMehrGewicht_alsNeben_Landwirtschaft",
      "P04, 105_1",
      "P05, 103_1",
      "P06, GewichtungSumme100_Wald",
      "P07, 10201",
      "P08, 11006b",
      "P09, Constraint1",
      "P10, 19901",
      "N11, checkMinimumFlaeche",
      "N12, CheckBefundSteinBlockschlagKeineUeberragung"
  })
  @SuppressWarnings("unchecked")
  void reconstructsOnlyTheIntendedRule(String id, String originalName) throws Exception {
    Map<String, Object> arguments = ConstraintFixtures.arguments(id, Map.class);
    String context = (String) arguments.get("contextFqn");
    String target = context + ".ReconstructedRule";
    arguments.put("modelText", ConstraintFixtures.withoutRule(id));
    arguments.put("includeSuccessfulTestXtf", false);

    var result = author(arguments);

    String after;
    if (id.startsWith("N")) {
      assertThat(result).containsEntry("status", "EXTERNAL_FUNCTION_SEMANTICS_REQUIRED");
      assertThat(result.get("updatedModelText")).isNull();
      var proof = mapper.valueToTree(result.get("constraintProofs")).get(0);
      assertThat(proof.path("status").asText()).isEqualTo("EXTERNAL_FUNCTION_SEMANTICS_REQUIRED");
      assertThat(proof.has("verification")).isFalse();
      after = (String) result.get("candidateModelText");
    } else {
      assertThat(result).containsEntry("status", "GENERATED");
      after = (String) result.get("updatedModelText");
      var proof = mapper.valueToTree(result.get("constraintProofs")).get(0);
      assertThat(proof.path("status").asText()).isEqualTo("PASSED");
      var cases = proof.path("verification").path("cases");
      assertThat(cases).isNotEmpty().allSatisfy(test -> {
        assertThat(test.path("constraintExercised").asBoolean()).as(test.toString()).isTrue();
        assertThat(test.path("fixtureValid").asBoolean()).as(test.toString()).isTrue();
        assertThat(test.path("fixtureErrors").isEmpty()).as(test.toString()).isTrue();
        assertThat(test.path("passed").asBoolean()).as(test.toString()).isTrue();
      }).anySatisfy(test -> assertThat(test.path("actualConstraintValid").asBoolean(true)).isFalse());
    }

    assertThat(after).isNotBlank();
    var original = compile(ConstraintFixtures.original(id));
    var candidate = compile(after);
    var expected = constraint(original, context + "." + originalName);
    var actual = constraint(candidate, target);
    assertThat(actual).isExactlyInstanceOf(expected.getClass());
    if (id.equals("P10")) {
      var count = (FunctionCall) ((Expression.Equality) ungroup(actual.getCondition())).getLeft();
      assertThat(count.getFunction().getScopedName()).isEqualTo("INTERLIS.objectCount");
      assertThat(count.getArguments()).singleElement().isInstanceOfSatisfying(Objects.class,
          all -> assertThat(all.getContext().getScopedName()).isEqualTo(context));
    }
    if (id.equals("N12")) {
      var inside = (FunctionCall) ungroup(actual.getCondition());
      assertThat(inside.getFunction().getScopedName()).isEqualTo("NGK_SO_FunctionsExt.IsInsideAreaByCode");
      var groups = (FunctionCall) inside.getArguments()[0];
      assertThat(groups.getFunction().getScopedName()).isEqualTo("GeoW_FunctionsExt.GetInGroups");
      assertThat(groups.getArguments()[0]).isInstanceOfSatisfying(Objects.class,
          all -> assertThat(all.getContext().getScopedName()).isEqualTo(context));
    }

    // These two payloads express the same ranges in a different form. Their numeric
    // and undefined boundaries are tested with explicit validator cases.
    switch (id) {
      case "P01", "P02" -> { }
      case "P03", "P04", "P05", "P06", "P07", "N11" -> {
        assertThat(expression(actual.getCondition())).as("%s rule", id)
            .isEqualTo(expression(expected.getCondition()));
        if (actual instanceof PlausibilityConstraint rule) {
          assertThat(rule.getDirection()).isEqualTo(PlausibilityConstraint.DIRECTION_AT_MOST);
          assertThat(rule.getPercentage()).isEqualTo(50.0);
        }
      }
      default -> assertThat(definition(candidate, actual)).as("%s rule", id)
          .isEqualTo(definition(original, expected));
    }
    assertThat(removeAndRender(candidate, actual)).as("%s surrounding model", id)
        .isEqualTo(removeAndRender(original, expected));

    switch (id) {
      case "P02" -> assertSoilPh(after, target, context, original);
      case "P03" -> assertWeightRelationship(after, target, context);
      case "P04" -> assertDrainageProfile(after, target);
      case "P09" -> assertShootingRangeExistence(after, target);
    }
  }

  private void assertSoilPh(String source, String target, String context, TransferDescription original) {
    var ph = (AttributeDef) original.getElement(
        "SO_AFU_Bodeneinheiten_20251210.Bodeneinheiten.Unterboden_Wald.Ph");
    var domain = (NumericType) ph.getDomainResolvingAliases();
    assertThat(domain.getMinimum().toString()).isEqualTo("0.0");
    assertThat(domain.getMaximum().toString()).isEqualTo("10.0");
    assertThat(domain.getMinimum().getAccuracy()).isEqualTo(1);
    assertThat(domain.getMaximum().getAccuracy()).isEqualTo(1);

    // Adjacent bands are separated by one representable pH increment (0.1).
    int[][] ranges = {{68, 100}, {62, 67}, {51, 61}, {43, 50}, {33, 42}, {0, 32}};
    var cases = new ArrayList<ConstraintTestTools.TestCase>();
    cases.add(soilCase(context, "Wald", 60, Map.of("Untertyp_E", "E5"), true));
    for (int band = 0; band < ranges.length; band++) {
      var lower = Map.of("Ph", BigDecimal.valueOf(ranges[band][0], 1));
      var upper = Map.of("Ph", BigDecimal.valueOf(ranges[band][1], 1));
      String subtype = "E" + band;
      cases.add(soilCase(context, "Wald", 60, Map.of("Untertyp_E", subtype, "Unterboden", lower), true));
      cases.add(soilCase(context, "Wald", 60, Map.of("Untertyp_E", subtype, "Unterboden", upper), true));
      if (band < ranges.length - 1) {
        cases.add(soilCase(context, "Wald", 60, Map.of("Untertyp_E", "E" + (band + 1), "Unterboden", lower), false));
      }
      if (band > 0) {
        cases.add(soilCase(context, "Wald", 60, Map.of("Untertyp_E", "E" + (band - 1), "Unterboden", upper), false));
      }
    }
    assertValidatorCases(source, target, cases);
  }

  private void assertWeightRelationship(String source, String target, String context) {
    var cases = List.of(40, 50, 60).stream().map(weight ->
        soilCase(context, "Landwirtschaft", weight, Map.of("Gewichtung", 50), weight == 60)).toList();
    assertValidatorCases(source, target, cases);
  }

  private static ConstraintTestTools.TestCase soilCase(String context, String landUse, int weight,
      Map<String, Object> values, boolean valid) {
    String topic = context.substring(0, context.lastIndexOf('.') + 1);
    var main = object(topic + "BodeneinheitHauptauspraegung_" + landUse, "main", Map.of("Gewichtung", weight));
    var secondary = object(context, "secondary", values);
    var link = link(topic + "Bodeneinheit_Nebenauspraegungen_" + landUse,
        Map.of("Bodeneinheit", "main", "Nebenauspraegung", "secondary"));
    return testCase("main weight " + weight + ", secondary " + values, valid, List.of(main, secondary), List.of(link));
  }

  private void assertDrainageProfile(String source, String target) {
    assertValidatorCases(source, target, List.of(
        drainageCase(null, "Pumpendruckleitung", true),
        drainageCase("Andere", "Pumpendruckleitung", true),
        drainageCase("Kiesschlitzdrainage", "Pumpendruckleitung", false),
        drainageCase("Kiesschlitzdrainage", "Sickerleitung", true)));
  }

  private static ConstraintTestTools.TestCase drainageCase(String description, String hydraulic, boolean valid) {
    String model = "VSADSSMINI_2020_LV95.VSADSSMini.";
    var pipe = object(model + "Leitung", "mcp0000000000001", Map.of("FunktionHierarchisch", "SAA.andere",
        "FunktionHydraulisch", hydraulic, "Nutzungsart_Ist", "Reinabwasser", "Profiltyp", "Spezialprofil"));
    String name = description + " / " + hydraulic;
    if (description == null) return testCase(name, valid, List.of(pipe), List.of());
    var profile = object(model + "Rohrprofil", "mcp0000000000002", Map.of("Bezeichnung", description));
    var link = link(model + "Leitung_RohrprofilAssoc",
        Map.of("RohrprofilRef", "mcp0000000000002", "Leitung_RohrprofilAssocRef", "mcp0000000000001"));
    return testCase(name, valid, List.of(pipe, profile), List.of(link));
  }

  private static ConstraintTestTools.TestObject object(String classFqn, String oid, Map<String, Object> values) {
    var object = new ConstraintTestTools.TestObject();
    object.classFqn = classFqn;
    object.oid = oid;
    object.values = values;
    return object;
  }

  private static ConstraintTestTools.TestLink link(String association, Map<String, String> roles) {
    var link = new ConstraintTestTools.TestLink();
    link.associationFqn = association;
    link.roles = roles;
    return link;
  }

  private static ConstraintTestTools.TestCase testCase(String name, boolean valid,
      List<ConstraintTestTools.TestObject> objects, List<ConstraintTestTools.TestLink> links) {
    var test = new ConstraintTestTools.TestCase();
    test.name = name;
    test.expectedConstraintValid = valid;
    test.objects = objects;
    test.links = links;
    return test;
  }

  private void assertValidatorCases(String source, String target, List<ConstraintTestTools.TestCase> expected) {
    var results = mapper.valueToTree(new ConstraintTestTools(compiler).testIliConstraint(source, target, expected))
        .path("cases");
    assertThat(results).hasSize(expected.size());
    for (int i = 0; i < expected.size(); i++) {
      var result = results.get(i);
      assertThat(result.path("fixtureValid").asBoolean()).as(result.toString()).isTrue();
      assertThat(result.path("constraintExercised").asBoolean()).as(result.toString()).isTrue();
      assertThat(result.path("targetViolationCount").asInt(-1)).as(result.toString())
          .isEqualTo(expected.get(i).expectedConstraintValid ? 0 : 1);
    }
  }

  private void assertShootingRangeExistence(String source, String target) {
    String topic = "SO_AFU_Schiesslaerm_20260210.Schiesslaerm.";
    var candidate = object(topic + "Anlage_Kandidat", "candidate", Map.of("ID", "80.2"));
    var location = new TypedValueFixtureFactory.CoordinateValue(
        List.of(new BigDecimal("2600000.000"), new BigDecimal("1200000.000")));
    var match = object(topic + "Anlage_Attribute", "range", Map.of("ID", "80.2", "Lage", location));
    var mismatch = object(topic + "Anlage_Attribute", "range", Map.of("ID", "80.3", "Lage", location));
    assertValidatorCases(source, target, List.of(
        testCase("matching range ID", true, List.of(candidate, match), List.of()),
        testCase("different range ID", false, List.of(candidate, mismatch), List.of()),
        testCase("no range", false, List.of(candidate), List.of())));
  }

  @Test
  void compilerKeepsAndOperandsInSourceOrder() {
    var same = compile(model("2.4", "MANDATORY CONSTRAINT Named: (A AND B);"));
    var reversed = compile(model("2.4", "MANDATORY CONSTRAINT Other: B AND A;"));
    assertThat(andOperands(constraint(same, "Example.Data.Item.Named"))).containsExactly("A", "B");
    assertThat(andOperands(constraint(reversed, "Example.Data.Item.Other"))).containsExactly("B", "A");
  }

  @Test
  void removingNamedRulePreservesAnonymousRulesButDetectsOtherEdits() {
    String anonymous = "MANDATORY CONSTRAINT number < 9;";
    var original = compile(model("2.3", anonymous));
    var originalModel = renderModels(original);
    String named = "!!@ name = Rule\nMANDATORY CONSTRAINT number > 2;\n";
    var clean = compile(model("2.3", named + anonymous));
    assertThat(constraint(clean, "Example.Data.Item.Rule").getMetaValue("name")).isEqualTo("Rule");
    assertThat(constraint(clean, "Example.Data.Item.Constraint2").getMetaValue("name")).isNull();
    assertThat(removeAndRender(clean, constraint(clean, "Example.Data.Item.Rule"))).isEqualTo(originalModel);

    var changed = compile(model("2.3", named + anonymous).replace("0..10", "0..20"));
    assertThat(removeAndRender(changed, constraint(changed, "Example.Data.Item.Rule"))).isNotEqualTo(originalModel);
    var excess = compile(model("2.3", named + anonymous + " MANDATORY CONSTRAINT number < 8;"));
    assertThat(removeAndRender(excess, constraint(excess, "Example.Data.Item.Rule"))).isNotEqualTo(originalModel);
  }

  @Test
  void clayContentAndTextureFormAFiniteMandatoryDomain() throws Exception {
    var model = compile(ConstraintFixtures.original("P01"));
    String context = "SO_AFU_Bodeneinheiten_20251210.Bodeneinheiten.Unterboden_Landwirtschaft.";
    var numeric = (AttributeDef) model.getElement(context + "Tongehalt");
    var enumeration = (AttributeDef) model.getElement(context + "Koernungsklasse");
    var type = (NumericType) numeric.getDomainResolvingAliases();
    assertThat(type.getMinimum().toString()).isEqualTo("0");
    assertThat(type.getMaximum().toString()).isEqualTo("100");
    assertThat(type.getMinimum().getAccuracy()).isZero();
    assertThat(type.getMaximum().getAccuracy()).isZero();
    assertThat(numeric.getDomain().isMandatoryConsideringAliases()).isTrue();
    assertThat(enumeration.getDomain().isMandatoryConsideringAliases()).isTrue();
    assertThat(((EnumerationType) enumeration.getDomainResolvingAliases()).getValues()).hasSize(13);
  }

  private TransferDescription compile(String source) {
    return compiler.compileOrThrow(source, null, "constraint_regression");
  }

  private static Constraint constraint(TransferDescription model, String name) {
    var element = model.getElement(name);
    assertThat(element).as(name).isInstanceOf(Constraint.class);
    return (Constraint) element;
  }

  private static String definition(TransferDescription model, Constraint constraint) {
    var text = new StringWriter();
    Interlis2Generator.generateElements(text, model).printConstraint(constraint, true);
    return text.toString().strip();
  }

  // Parentheses are represented by wrapper nodes. Preserve the ordered tree inside them.
  private static Object expression(Evaluable node) {
    return switch (ungroup(node)) {
      case Expression.Conjunction and -> List.of("AND", expressions(and.getConjoined()));
      case Expression.Disjunction or -> List.of("OR", expressions(or.getDisjoined()));
      case Expression.Negation not -> List.of("NOT", expression(not.getNegated()));
      case Expression.DefinedCheck defined -> List.of("DEFINED", expression(defined.getArgument()));
      case Expression.Equality eq -> List.of("==", expression(eq.getLeft()), expression(eq.getRight()));
      case Expression.GreaterThan gt -> List.of(">", expression(gt.getLeft()), expression(gt.getRight()));
      case FunctionCall call -> List.of(call.getFunction().getScopedName(), expressions(call.getArguments()));
      case ObjectPath path -> List.of(path.getRoot().getScopedName(), path.toString());
      case Constant.Numeric number -> List.of("number", number.getValue().toString());
      case Constant.Text text -> List.of("text", text.getValue());
      case Constant.Enumeration enumeration -> List.of("enum", List.of(enumeration.getValue()));
      default -> throw new AssertionError("Unexpected expression: " + node.getClass());
    };
  }

  private static List<Object> expressions(Evaluable[] nodes) {
    return java.util.Arrays.stream(nodes).map(ConstraintReconstructionTest::expression).toList();
  }

  private static Evaluable ungroup(Evaluable expression) {
    while (expression instanceof Expression.Subexpression group) {
      expression = group.getSubexpression();
    }
    return expression;
  }

  private static List<String> andOperands(Constraint constraint) {
    var and = (Expression.Conjunction) ungroup(constraint.getCondition());
    return java.util.Arrays.stream(and.getConjoined()).map(ConstraintReconstructionTest::ungroup)
        .map(operand -> ((ObjectPath) operand).getPathElements()[0].getName()).toList();
  }

  private static List<String> removeAndRender(TransferDescription model, Constraint constraint) {
    constraint.getContainer().remove(constraint);
    return renderModels(model);
  }

  private static List<String> renderModels(TransferDescription model) {
    return java.util.Arrays.stream(model.getModelsFromLastFile()).map(element -> {
      renumberAnonymousRules(element);
      return Interlis2Generator.debugToString(model, element);
    }).toList();
  }

  private static void renumberAnonymousRules(Container<?> container) {
    int index = 0;
    for (var iterator = container.iterator(); iterator.hasNext();) {
      var element = iterator.next();
      if (element instanceof Constraint constraint) {
        index++;
        if (!constraint.hasCustomName() && constraint.getMetaValue("name") == null) constraint.setNameIdx(index);
      } else if (element instanceof Container<?> nested) {
        renumberAnonymousRules(nested);
      }
    }
  }

  private static String model(String version, String rule) {
    return """
        INTERLIS %s;
        MODEL Example (en) AT "https://example.org" VERSION "1" =
          TOPIC Data =
            CLASS Item =
              A : BOOLEAN;
              B : BOOLEAN;
              number : 0..10;
              %s
            END Item;
          END Data;
        END Example.
        """.formatted(version, rule);
  }
}
