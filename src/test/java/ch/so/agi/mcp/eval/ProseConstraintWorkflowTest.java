package ch.so.agi.mcp.eval;

import static org.assertj.core.api.Assertions.assertThat;
import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.constraint.*;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProseConstraintWorkflowTest {
  static final String MODEL = """
      INTERLIS 2.4;
      MODEL Workflow (en) AT "https://example.org" VERSION "1" =
        DOMAIN Age = 0..100;
        TOPIC Data =
          CLASS Base =
            age : MANDATORY Age;
            optional : 0.0..100.0;
            state : (active, inactive);
            MANDATORY CONSTRAINT BaseRule: age >= 0;
          END Base;
          CLASS Person EXTENDS Base =
            age (EXTENDED) : MANDATORY 0..90;
            label : TEXT*20;
          END Person;
          CLASS Other = age : 0..10; END Other;
          ASSOCIATION Related =
            person -- {1} Person;
            others -- {0..*} Other;
          END Related;
        END Data;
      END Workflow.
      """;
  @Test void scopedContextIncludesEffectiveInheritanceAndOnlyDirectTargets() {
    var compiler = new Counter(); var tools = new ModelAnalysisTools(compiler);
    var full = tools.analyzeIliModel(MODEL, null);
    assertThat(full.get("valid")).as(full.toString()).isEqualTo(true);
    assertThat(full).doesNotContainKey("authoringContext");
    var scoped = tools.analyzeIliModel(MODEL, null, "Workflow.Data.Person");
    assertThat(compiler.calls).isEqualTo(2);
    var ctx = map(scoped.get("authoringContext"));
    assertThat(ctx.get("status")).isEqualTo("AVAILABLE");
    var attrs = list(ctx.get("attributes"));
    assertThat(attrs).hasSize(4);
    assertThat(attrs).anySatisfy(a -> {
      assertThat(a.get("name")).isEqualTo("age");
      assertThat(a).containsEntry("inherited", false).containsEntry("maximum", "90");
      assertThat(a.get("declaringContext")).isEqualTo("Workflow.Data.Person");
    });
    assertThat(attrs).anySatisfy(a -> {
      assertThat(a.get("name")).isEqualTo("optional");
      assertThat(a).containsEntry("inherited", true).containsEntry("precision", 1).containsEntry("mandatory", false);
    });
    assertThat(list(ctx.get("constraints"))).anySatisfy(c -> assertThat(c).containsEntry("inherited", true));
    assertThat(list(ctx.get("roles"))).anySatisfy(r -> assertThat(r.get("name")).isEqualTo("others"));
    assertThat(list(ctx.get("relationshipTargets"))).anySatisfy(t -> assertThat(t.get("contextFqn")).isEqualTo("Workflow.Data.Other"));
    assertThat(list(scoped.get("classes"))).hasSize(1);
    var base = map(tools.analyzeIliModel(MODEL, null, "Workflow.Data.Base").get("authoringContext"));
    assertThat(list(base.get("attributes"))).anySatisfy(a -> {
      assertThat(a.get("name")).isEqualTo("age");
      assertThat(a.get("domainAliases")).isEqualTo(List.of("Workflow.Age"));
    });
  }
  @Test void missingContextAndInvalidModelNeverFallBack() {
    var tools = new ModelAnalysisTools(new IliCompilerService());
    for (String context : List.of("Person", "Workflow.Age", "Workflow.Data.Absent", " ")) {
      var result = tools.analyzeIliModel(MODEL, null, context);
      assertThat(map(result.get("authoringContext"))).containsEntry("status", "UNAVAILABLE");
      assertThat(list(result.get("attributes"))).isEmpty();
    }
    var result = tools.analyzeIliModel("INVALID", null, "Workflow.Data.Person");
    assertThat(result.get("valid")).isEqualTo(false);
    assertThat(map(result.get("authoringContext"))).containsEntry("status", "UNAVAILABLE");
  }
  static String simple(String expression) {
    return "INTERLIS 2.4; MODEL Simple (en) AT \"https://example.org\" VERSION \"1\" = TOPIC Data = CLASS Person = age : 0..100; MANDATORY CONSTRAINT Rule: " + expression + "; END Person; END Data; END Simple.";
  }
  @Test void explanationReflectsCompiledBoundaryAndOrderedUndefinedBehavior() {
    var compiler = new Counter(); var review = new ConstraintReviewTools(compiler, new ConstraintKnowledgeTools(compiler));
    var result = review.reviewIliConstraint(simple("age >= 18"), "Simple.Data.Person.Rule");
    var explanation = (ConstraintExplanation) result.get("explanation");
    assertThat(explanation.status()).isEqualTo(ConstraintExplanation.Status.COMPLETE);
    assertThat(explanation.description()).contains("mindestens 18");
    assertThat(explanation.notes().toString()).contains("UNDEFINED");
    assertThat(explanation.referencedElements()).contains("Simple.Data.Person.age");
    String source = simple("age > 18 AND age < 10");
    var cases = List.of(age(null, true, ConstraintTestTools.ExpectationSource.USER_PROVIDED), age(19, false, null));
    var tests = new ConstraintTestTools(compiler).testIliConstraint(source, "Simple.Data.Person.Rule", cases);
    assertThat(tests.get("allPassed")).isEqualTo(true);
    var ordered = (ConstraintExplanation) review.reviewIliConstraint(source, "Simple.Data.Person.Rule").get("explanation");
    assertThat(ordered.description()).contains("grösser als 18 UND danach age ist kleiner als 10");
    assertThat(ordered.notes().toString()).contains("Reihenfolge");
    assertThat(compiler.calls).isEqualTo(3);
  }
  @Test void expectationsRemainDistinctFromAutomaticProofAndKeepTheirSourceOnFailures() {
    var compiler = new Counter(); var tests = new ConstraintTestTools(compiler);
    String source = simple("age > 18");
    var generated = new ConstraintCaseGenerationTools(new ConstraintContextService(compiler), tests)
        .generateIliConstraintCases(source, "Simple.Data.Person.Rule");
    assertThat(generated.get("generationVerified")).isEqualTo(true);
    assertThat(((ConstraintExplanation)generated.get("explanation")).description()).contains("grösser als 18");
    var cases = List.of(age(17, false, ConstraintTestTools.ExpectationSource.USER_PROVIDED),
        age(18, true, ConstraintTestTools.ExpectationSource.USER_CONFIRMED), age(19, true, ConstraintTestTools.ExpectationSource.AGENT_DERIVED));
    var result = tests.testIliConstraint(source, "Simple.Data.Person.Rule", cases);
    assertThat(result.get("allPassed")).isEqualTo(false);
    assertThat(list(result.get("cases")).get(1)).containsEntry("expectationSource", ConstraintTestTools.ExpectationSource.USER_CONFIRMED).containsEntry("passed", false);
    var invalid = tests.testIliConstraint(source, "Simple.Data.Person.Rule", List.of(age(101, true, null)));
    assertThat(list(invalid.get("cases")).getFirst()).containsEntry("expectationSource", ConstraintTestTools.ExpectationSource.UNSPECIFIED).containsEntry("fixtureValid", false);
    var badModel = tests.testIliConstraint("INVALID", "Missing", cases);
    assertThat(list(badModel.get("cases")).get(2)).containsEntry("expectationSource", ConstraintTestTools.ExpectationSource.AGENT_DERIVED).containsEntry("tested", false);
  }
  @Test void noExplanationIsInventedForInvalidSource() {
    var compiler = new Counter();
    var result = new ConstraintReviewTools(compiler, new ConstraintKnowledgeTools(compiler)).reviewIliConstraint("INVALID", "Rule");
    assertThat(((ConstraintExplanation)result.get("explanation")).status()).isEqualTo(ConstraintExplanation.Status.UNAVAILABLE);
  }
  @Test void explanationKeepsUniquenessScopeAndConditionalDirection() {
    var compiler = new Counter(); var review = new ConstraintReviewTools(compiler, new ConstraintKnowledgeTools(compiler));
    String global = simple("age >= 18").replace("MANDATORY CONSTRAINT Rule: age >= 18;", "UNIQUE Rule: age;");
    var g = (ConstraintExplanation)review.reviewIliConstraint(global, "Simple.Data.Person.Rule").get("explanation");
    assertThat(g.status()).isEqualTo(ConstraintExplanation.Status.COMPLETE);
    assertThat(g.description()).contains("global", "age");
    var basket = (ConstraintExplanation)review.reviewIliConstraint(global.replace("UNIQUE Rule:", "UNIQUE (BASKET) Rule:"), "Simple.Data.Person.Rule").get("explanation");
    assertThat(basket.description()).contains("pro Basket");
    String condition = "NOT (age < 18) OR age == 17";
    var c = (ConstraintExplanation)review.reviewIliConstraint(simple(condition), "Simple.Data.Person.Rule").get("explanation");
    assertThat(c.description()).contains("NICHT", "kleiner als 18", "ODER danach", "gleich 17");
    assertThat(c.status()).isEqualTo(ConstraintExplanation.Status.COMPLETE);
  }

  @Test void structuresViewsEnumsUnitsAndDocumentationAreAvailable() {
    String source = """
        INTERLIS 2.4;
        MODEL Contexts (en) AT "https://example.org" VERSION "1" =
          TOPIC Data =
            STRUCTURE Detail = flag : BOOLEAN; END Detail;
            CLASS Item =
              /** Measured length. */
              length : 0.00..100.00 [INTERLIS.m];
              state : (open, closed(archived, deleted));
            END Item;
            VIEW Selection PROJECTION OF Item;
              = ALL OF Item;
            END Selection;
          END Data;
        END Contexts.
        """;
    var tools = new ModelAnalysisTools(new IliCompilerService());
    var result = tools.analyzeIliModel(source, null, "Contexts.Data.Item");
    assertThat(result.get("valid")).as(result.toString()).isEqualTo(true);
    var attrs = list(map(result.get("authoringContext")).get("attributes"));
    assertThat(attrs).anySatisfy(a -> {
      assertThat(a.get("name")).isEqualTo("length");
      assertThat(a).containsEntry("precision", 2).containsEntry("unit", "INTERLIS.m");
      assertThat(a.get("documentation").toString()).contains("Measured length");
    });
    assertThat(attrs).anySatisfy(a -> assertThat(a.get("enumValues")).isEqualTo(List.of("open", "closed.archived", "closed.deleted")));
    for (String fqn : List.of("Contexts.Data.Detail", "Contexts.Data.Selection"))
      assertThat(map(tools.analyzeIliModel(source, null, fqn).get("authoringContext"))).containsEntry("status", "AVAILABLE");
  }

  @Test void independentWorkflowReferencesAgreeWithRealValidator() throws Exception {
    var root = java.nio.file.Path.of("evals/constraint-authoring-workflow");
    var mapper = new tools.jackson.databind.ObjectMapper();
    var suite = mapper.readTree(java.nio.file.Files.readString(root.resolve("suite.json")));
    var compiler = new IliCompilerService();
    for (var entry : suite.get("cases")) {
      String id = entry.get("id").asText();
      String source = java.nio.file.Files.readString(root.resolve("public/" + id + "/model.ili"));
      assertThat(compiler.compile(source, null).valid()).as(id).isTrue();
      var oracle = mapper.readTree(java.nio.file.Files.readString(root.resolve("reference/" + id + ".json")));
      for (var rule : oracle.get("referenceConstraints").properties()) {
        String modified = source.replace("END Person;", "MANDATORY CONSTRAINT " + rule.getKey() + ": " + rule.getValue().asText() + "; END Person;");
        var cases = new ArrayList<ConstraintTestTools.TestCase>();
        for (var expected : oracle.get("expectations")) {
          var object = new ConstraintTestTools.TestObject(); object.classFqn = "ProseWorkflow.Data.Person"; object.oid = "p";
          object.values = mapper.convertValue(expected.get("values"), Map.class);
          var test = new ConstraintTestTools.TestCase(); test.name = expected.get("id").asText();
          test.expectedConstraintValid = expected.get("expectedRules").get(rule.getKey()).asBoolean(); test.objects = List.of(object);
          cases.add(test);
        }
        var result = new ConstraintTestTools(compiler).testIliConstraint(modified, "ProseWorkflow.Data.Person." + rule.getKey(), cases);
        assertThat(result.get("allPassed")).as(id + ": " + result).isEqualTo(true);
      }
    }
  }

  @Test void explanationsPreserveOrderThatChangesRealValidatorResults() {
    var compiler = new Counter();
    var review = new ConstraintReviewTools(compiler, new ConstraintKnowledgeTools(compiler));
    for (String expression : List.of("age > 18 AND flag", "flag AND age > 18")) {
      String source = simple(expression).replace("age : 0..100;", "age : 0..100; flag : MANDATORY BOOLEAN;");
      var explanation = (ConstraintExplanation) review.reviewIliConstraint(source, "Simple.Data.Person.Rule").get("explanation");
      assertThat(explanation.status()).isEqualTo(ConstraintExplanation.Status.COMPLETE);
      assertThat(explanation.notes().toString()).contains("Reihenfolge");
      boolean ageFirst = expression.startsWith("age");
      assertThat(explanation.description().indexOf("age") < explanation.description().indexOf("flag")).isEqualTo(ageFirst);
      var test = age(null, ageFirst, ConstraintTestTools.ExpectationSource.USER_PROVIDED);
      test.objects.getFirst().values = Map.of("flag", false);
      assertThat(new ConstraintTestTools(compiler).testIliConstraint(source, "Simple.Data.Person.Rule", List.of(test)).get("allPassed")).isEqualTo(true);
    }
  }

  static ConstraintTestTools.TestCase age(Integer value, boolean expected, ConstraintTestTools.ExpectationSource provenance) {
    var object = new ConstraintTestTools.TestObject(); object.classFqn = "Simple.Data.Person"; object.oid = "p";
    object.values = value == null ? Map.of() : Map.of("age", value);
    var test = new ConstraintTestTools.TestCase(); test.name = "age" + value; test.expectedConstraintValid = expected;
    test.expectationSource = provenance; test.objects = List.of(object); return test;
  }
  @SuppressWarnings("unchecked") static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
  @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Object value) { return (List<Map<String,Object>>)value; }
  static class Counter extends IliCompilerService {
    int calls;
    @Override public CompilationResult compile(String text, String repositories, String prefix) {
      calls++; return super.compile(text, repositories, prefix);
    }
  }
}
