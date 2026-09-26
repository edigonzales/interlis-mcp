package ch.so.agi.mcp.constraint;

import static org.assertj.core.api.Assertions.assertThat;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.ConstraintTestTools;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintInteractionAnalysisTest {
  private final IliCompilerService compiler = new IliCompilerService();

  private String model(String attributes, String constraints) {
    return "INTERLIS 2.4; MODEL Interactions (en) AT \"https://example.org\" VERSION \"1\" = "
        + "TOPIC Data = CLASS Item = " + attributes + " " + constraints + " END Item; END Data; END Interactions.";
  }
  private ConstraintInteractionAnalysis.Result analyze(String text) {
    var compiled = compiler.compile(text, null, "interaction_test_");
    assertThat(compiled.valid()).as(compiled.messages().toString()).isTrue();
    return ConstraintInteractionAnalysis.analyze(compiled.transferDescription());
  }
  @Test void contradictingRulesAreDetectedWithoutClaimingEmptyTransfersInvalid() {
    var result = analyze(model("value : MANDATORY 0..20;",
        "MANDATORY CONSTRAINT Lower: value > 10; MANDATORY CONSTRAINT Upper: value < 5;"));
    var context = result.contexts().getFirst();
    assertThat(context.status()).isEqualTo(ConstraintInteractionAnalysis.Status.CONTRADICTION_PROVEN);
    assertThat(context.constraintFqns()).containsExactly("Interactions.Data.Item.Lower", "Interactions.Data.Item.Upper");
    assertThat(context.domains().getFirst().minimum()).isEqualByComparingTo("0");
    assertThat(context.domains().getFirst().maximum()).isEqualByComparingTo("20");
    assertThat(result.limitation()).contains("not necessarily an empty transfer");
  }
  @Test void precisionAndEnumsArePartitionedCompletely() {
    assertThat(analyze(model("value : MANDATORY 0.0..1.0;",
        "MANDATORY CONSTRAINT value > 0.1; MANDATORY CONSTRAINT value < 0.2;"))
        .contexts().getFirst().status()).isEqualTo(ConstraintInteractionAnalysis.Status.CONTRADICTION_PROVEN);
    assertThat(analyze(model("value : MANDATORY 0.00..1.00;",
        "MANDATORY CONSTRAINT value > 0.1; MANDATORY CONSTRAINT value < 0.2;"))
        .contexts().getFirst().status()).isEqualTo(ConstraintInteractionAnalysis.Status.SCALAR_ASSIGNMENT_FOUND);
    assertThat(analyze(model("state : MANDATORY (a,b);",
        "MANDATORY CONSTRAINT state == #a; MANDATORY CONSTRAINT state == #b;"))
        .contexts().getFirst().status()).isEqualTo(ConstraintInteractionAnalysis.Status.CONTRADICTION_PROVEN);
  }
  @Test void optionalValuesAndIndependentRulesUseValidatorSemantics() {
    String attributes = "value : 0..20; flag : MANDATORY BOOLEAN;";
    String rules = "MANDATORY CONSTRAINT First: value > 10; MANDATORY CONSTRAINT Second: flag; MANDATORY CONSTRAINT Third: NOT(flag);";
    var contradiction = analyze(model(attributes, rules));
    assertThat(contradiction.contexts().getFirst().status()).isEqualTo(ConstraintInteractionAnalysis.Status.CONTRADICTION_PROVEN);
    // If the three rules were combined as ordered AND, missing value would mask both flag rules.
    var possible = analyze(model("value : 0..20;", "MANDATORY CONSTRAINT value > 10; MANDATORY CONSTRAINT value < 5;"));
    var assignment = possible.contexts().getFirst();
    assertThat(assignment.status()).isEqualTo(ConstraintInteractionAnalysis.Status.SCALAR_ASSIGNMENT_FOUND);
    assertThat(assignment.undefinedAttributes()).containsExactly("value");
  }
  @Test void orderedExpressionsAgreeWithActualValidatorForMissingValues() {
    String attributes = "value : 0..20; flag : MANDATORY BOOLEAN;";
    for (String expression : List.of("(value > 10) AND flag", "flag AND (value > 10)")) {
      String text = model(attributes, "MANDATORY CONSTRAINT Rule: " + expression + "; MANDATORY CONSTRAINT NoFlag: NOT(flag);");
      var result = analyze(text).contexts().getFirst();
      var test = new ConstraintTestTools.TestCase(); test.name = "missing";
      test.expectedConstraintValid = expression.startsWith("(value");
      var object = new ConstraintTestTools.TestObject(); object.classFqn = "Interactions.Data.Item";
      object.oid = "o1"; object.values = Map.of("flag", false); test.objects = List.of(object);
      assertThat(new ConstraintTestTools(compiler).testIliConstraint(text, "Interactions.Data.Item.Rule", List.of(test)))
          .containsEntry("allPassed", true);
      assertThat(result.status()).isEqualTo(expression.startsWith("(value")
          ? ConstraintInteractionAnalysis.Status.SCALAR_ASSIGNMENT_FOUND : ConstraintInteractionAnalysis.Status.CONTRADICTION_PROVEN);
    }
  }
  @Test void unsupportedRuleDoesNotHideContradictionAndDoesNotImplyOverallConsistency() {
    for (String upper : List.of("5", "15")) {
      var result = analyze(model("value : MANDATORY 0..20;",
          "MANDATORY CONSTRAINT value > 10; MANDATORY CONSTRAINT value < " + upper
          + "; MANDATORY CONSTRAINT (value + 1) > 0; UNIQUE value;"));
      var context = result.contexts().getFirst();
      assertThat(context.unsupported()).hasSize(2);
      assertThat(context.status()).isEqualTo(upper.equals("5") ? ConstraintInteractionAnalysis.Status.CONTRADICTION_PROVEN
          : ConstraintInteractionAnalysis.Status.SCALAR_ASSIGNMENT_FOUND);
      assertThat(result.evidence().status()).isEqualTo(upper.equals("5")
          ? ch.so.agi.mcp.model.EvidenceSummary.Status.FAILED : ch.so.agi.mcp.model.EvidenceSummary.Status.INCOMPLETE);
    }
  }
  @Test void budgetIsSharedAndExhaustionIsNeverAContradiction() {
    String text = model("value : MANDATORY 0..20;", "MANDATORY CONSTRAINT value > 10; MANDATORY CONSTRAINT value < 5;");
    text = text.replace("END Item;", "END Item; CLASS Other = value : MANDATORY 0..20; MANDATORY CONSTRAINT value > 10; MANDATORY CONSTRAINT value < 5; END Other;");
    var compiled = compiler.compile(text, null, "interaction_budget_");
    assertThat(compiled.valid()).as(compiled.messages().toString()).isTrue();
    var result = ConstraintInteractionAnalysis.analyze(compiled.transferDescription(), 1);
    assertThat(result.examinedStates()).isEqualTo(1);
    assertThat(result.contexts()).allSatisfy(c -> assertThat(c.status()).isEqualTo(ConstraintInteractionAnalysis.Status.UNKNOWN));
    assertThat(result.contexts().get(1).examinedStates()).isZero();
    assertThat(result).isEqualTo(ConstraintInteractionAnalysis.analyze(compiled.transferDescription(), 1));
  }
  @Test void productionBudgetStopsAnExponentialPartitionWithoutFalseProof() {
    StringBuilder attributes = new StringBuilder(), rules = new StringBuilder();
    for (int i = 0; i < 17; i++) {
      attributes.append("flag").append(i).append(" : MANDATORY BOOLEAN; ");
      rules.append("MANDATORY CONSTRAINT flag").append(i).append(" OR NOT(flag").append(i).append("); ");
    }
    rules.append("MANDATORY CONSTRAINT flag0 AND NOT(flag0);");
    var result = analyze(model(attributes.toString(), rules.toString()));
    assertThat(result.examinedStates()).isEqualTo(50_000);
    assertThat(result.contexts().getFirst().status()).isEqualTo(ConstraintInteractionAnalysis.Status.UNKNOWN);
    assertThat(result.contexts().getFirst().reasonCode()).isEqualTo("STATE_BUDGET_EXCEEDED");
  }

  @Test void unsupportedContextsAndNoRulesAreExplicit() {
    String text = "INTERLIS 2.4; MODEL Interactions (en) AT \"https://example.org\" VERSION \"1\" = TOPIC Data = "
        + "CLASS Base (ABSTRACT) = value : MANDATORY 0..10; MANDATORY CONSTRAINT value > 1; END Base; "
        + "CLASS Derived EXTENDS Base = END Derived; STRUCTURE S = value : 0..10; MANDATORY CONSTRAINT value > 1; END S; "
        + "CLASS Empty = END Empty; END Data; END Interactions.";
    var results = analyze(text).contexts();
    assertThat(results.stream().filter(c -> c.contextFqn().endsWith(".Derived")).findFirst().orElseThrow().reasonCode()).isEqualTo("INHERITANCE_UNSUPPORTED");
    assertThat(results.stream().filter(c -> c.contextFqn().endsWith(".S")).findFirst().orElseThrow().reasonCode()).isEqualTo("STRUCTURE_CONTEXT_UNSUPPORTED");
    assertThat(results.stream().filter(c -> c.contextFqn().endsWith(".Empty")).findFirst().orElseThrow().status()).isEqualTo(ConstraintInteractionAnalysis.Status.NOT_APPLICABLE);
    assertThat(ConstraintInteractionAnalysis.analyze(null).evidence().status()).isEqualTo(ch.so.agi.mcp.model.EvidenceSummary.Status.NOT_RUN);
  }
}
