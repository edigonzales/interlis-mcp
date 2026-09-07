package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.*;

import ch.so.agi.mcp.constraint.*;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConstraintViewProofTest {
  private final IliCompilerService compiler = new IliCompilerService();
  private final ConstraintTestTools tests = new ConstraintTestTools(compiler);
  private final ConstraintCaseGenerationTools generation = new ConstraintCaseGenerationTools(new ConstraintContextService(compiler),tests);

  private static String model(String version,String filters,String constraints) {
    if (version.equals("2.3")) constraints = constraints.replaceAll("(MANDATORY CONSTRAINT|SET CONSTRAINT|UNIQUE) ([a-z]+):", "\n!!@ name = \"$2\"\n$1");
    return """
        INTERLIS %s;
        MODEL ViewProof (en) AT "https://example.org" VERSION "2026-09-07" =
          TOPIC Data =
            DOMAIN States = (outside, active (temporary, permanent), dead);
            CLASS Parent =
              Value : MANDATORY 0..10;
              Label : TEXT*20;
              A : BOOLEAN;
              B : BOOLEAN;
              State : ALL OF States;
            END Parent;
            CLASS Item EXTENDS Parent = END Item;
            CLASS Child EXTENDS Item = END Child;
          END Data;
          VIEW TOPIC Checks =
            DEPENDS ON ViewProof.Data;
            VIEW Selected PROJECTION OF K ~ ViewProof.Data.Item;
              %s
            =
              ALL OF K;
              %s
            END Selected;
          END Checks;
        END ViewProof.
        """.formatted(version,filters,constraints);
  }
  private static ConstraintTestTools.TestObject object(String oid,Map<String,Object> values) {
    var o=new ConstraintTestTools.TestObject();o.classFqn="ViewProof.Data.Item";o.oid=oid;o.values=values;return o;
  }
  private static ConstraintTestTools.TestCase test(String name,boolean valid,ConstraintTestTools.TestObject...objects) {
    var t=new ConstraintTestTools.TestCase();t.name=name;t.expectedConstraintValid=valid;t.objects=List.of(objects);return t;
  }
  private List<Map<String,Object>> check(String source,String constraint,ConstraintTestTools.TestCase...cases) {
    var r=tests.testIliConstraint(source,"ViewProof.Checks.Selected."+constraint,List.of(cases));
    assertThat(r.get("compilerValid")).as(r.toString()).isEqualTo(true);
    return (List<Map<String,Object>>)r.get("cases");
  }

  @ParameterizedTest @ValueSource(strings={"2.3","2.4"})
  void emptySetIsActuallyExecutedAndRejectsOppositeControl(String version) {
    String source=model(version,"","SET CONSTRAINT empty: INTERLIS.objectCount(ALL) == 0; SET CONSTRAINT nonempty: INTERLIS.objectCount(ALL) > 0;");
    var empty=check(source,"empty",test("empty",true),test("one",false,object("a",Map.of())),test("two",false,object("a",Map.of()),object("b",Map.of())));
    assertThat(empty).allSatisfy(c->{assertThat(c.get("passed")).as(c.toString()).isEqualTo(true);assertThat(c.get("setExecuted")).isEqualTo(true);});
    assertThat(empty.getFirst()).containsEntry("subjectCount",0).containsEntry("constraintExercised",true);
    assertThat(empty.getFirst().get("xtfText").toString()).contains("view_base_1","view_scope_1");
    var control=check(source,"nonempty",test("empty violation",false)).getFirst();
    assertThat(control).containsEntry("passed",true).containsEntry("actualConstraintValid",false).containsEntry("setExecuted",true);
    assertThat(control.get("targetViolationCount")).isEqualTo(1);
  }

  @ParameterizedTest @ValueSource(strings={"2.3","2.4"})
  void multipleFiltersDistinguishRawMissingFromSkippedEvaluation(String version) {
    String source=model(version,"WHERE K->A; WHERE NOT (K->B);","MANDATORY CONSTRAINT target: Value > 0;");
    var child=object("child",Map.of("Value",1,"A",true,"B",false));child.classFqn="ViewProof.Data.Child";
    var rows=check(source,"target",
        test("members and exclusions",false,child,object("bad",Map.of("Value",0,"A",true,"B",false)),
            object("missing",Map.of("Value",0)),object("excluded",Map.of("Value",0,"A",false,"B",true))),
        test("second filter skipped",false,object("skip",Map.of("Value",0,"A",true))),
        test("no members",true,object("outside",Map.of("Value",0,"A",false))));
    assertThat(rows.get(0)).containsEntry("passed",true).containsEntry("baseSubjectCount",4).containsEntry("subjectCount",2).containsEntry("excludedSubjectCount",2);
    assertThat(rows.get(1)).containsEntry("passed",true).containsEntry("subjectCount",1).containsEntry("skippedFilterCount",1);
    assertThat(rows.get(2)).containsEntry("passed",false).containsEntry("constraintExercised",false);
  }

  @ParameterizedTest @ValueSource(strings={"2.3","2.4"})
  void hierarchyAndMissingStatusUsePinnedValidatorMembership(String version) {
    String source=model(version,"WHERE INTERLIS.isEnumSubVal(K->State,#active);","UNIQUE target: Label;");
    for(String status:Arrays.asList("active", "active.temporary","active.permanent","outside","dead",null)) {
      var values=new LinkedHashMap<String,Object>();values.put("Label","duplicate");if(status!=null)values.put("State",status);
      boolean included=status==null || status.startsWith("active");
      var row=check(source,"target",test("status "+status,!included,
          object("anchor",Map.of("Label","anchor","State","active.temporary")),object("a",values),object("b",values))).getFirst();
      assertThat(row).as(status).containsEntry("passed",true).containsEntry("subjectCount",included?3:1).containsEntry("excludedSubjectCount",included?0:2);
      assertThat(row.get("skippedFilterCount")).isEqualTo(status==null?2:0);
    }
  }

  @ParameterizedTest @ValueSource(strings={"2.3","2.4"})
  void scalarUniqueAndDecisionTableShareViewScope(String version) {
    String source=model(version,"WHERE K->A;","UNIQUE key: Label;");
    var unique=generation.generateIliConstraintCases(source,"ViewProof.Checks.Selected.key");
    assertThat(unique.get("generationVerified")).as(unique.toString()).isEqualTo(true);
    assertThat(unique.get("coverageComplete")).isEqualTo(true);
    var decision=new ConstraintDecisionTableTools(new ConstraintAuthoringWorkflow(compiler),generation);
    var condition=new ConstraintDecisionTableTools.DecisionCondition();condition.attribute="Value";condition.operator=">";condition.value=0;
    var row=new ConstraintDecisionTableTools.DecisionRow();row.name="positive";row.conditions=List.of(condition);
    var result=decision.generateIliConstraintFromDecisionTable(source,"ViewProof.Checks.Selected","decision",List.of(row));
    assertThat(result.status).as(result.toString()).isEqualTo(IliAuthoringResult.Status.GENERATED);
  }

  @Test
  void fixedKeysCannotBeChangedToSatisfyAViewFilter() {
    String source=model("2.4","WHERE K->Value > 5;","MANDATORY CONSTRAINT target: Value > 0;");
    var context=new ConstraintContextService(compiler).compileAndResolve(source,"ViewProof.Checks.Selected.target",null,"fixed_view_").context();
    var binding=ViewProofScope.bind(context,"ViewProof.Data.Item",new ConstraintExpression.BooleanLiteral(true));
    var goal=new ConstraintExpressionEngine.TestGoal(ConstraintExpressionEngine.GoalKind.TRUE,new ConstraintExpression.BooleanLiteral(true),"included");
    var rejected=ConstraintGoalSolver.solve(goal,binding,Map.of("Value",java.math.BigDecimal.valueOf(2)));
    assertThat(rejected.solved()).isFalse();
    assertThat(rejected.attempts()).isLessThanOrEqualTo(50_000);
    var accepted=ConstraintGoalSolver.solve(goal,binding,Map.of("Value",java.math.BigDecimal.valueOf(6)));
    assertThat(accepted.solved()).isTrue();
    assertThat(accepted.assignment()).containsEntry("Value",java.math.BigDecimal.valueOf(6));
  }

  @Test
  void automaticallyMaterializedBaseObjectsCannotSilentlyChangeThePopulation() {
    String source=model("2.4","","MANDATORY CONSTRAINT target: Value > 0;")
        .replace("END Data;", """
            ASSOCIATION Pair =
              owner -- {1} Item;
              dependent -- {0..*} Item;
            END Pair;
            END Data;
            """);
    var result=generation.generateIliConstraintCases(source,"ViewProof.Checks.Selected.target");
    assertThat(result.get("generationVerified")).as(result.toString()).isEqualTo(false);
    assertThat(result.get("reasonCode")).isEqualTo("VIEW_SCOPE_VERIFICATION_FAILED");
  }

  @Test
  void unsupportedShapeAndNativeFilterReturnStructuredBoundaries() {
    String computed=model("2.4","","Derived := Value; MANDATORY CONSTRAINT target: Value > 0;");
    assertThat(generation.generateIliConstraintCases(computed,"ViewProof.Checks.Selected.target"))
        .containsEntry("reasonCode","VIEW_PROOF_SHAPE_UNSUPPORTED");
    String nativeFilter=model("2.4","WHERE K->A => K->B;","MANDATORY CONSTRAINT target: Value > 0;");
    assertThat(generation.generateIliConstraintCases(nativeFilter,"ViewProof.Checks.Selected.target"))
        .containsEntry("reasonCode","VALIDATOR_NATIVE_IMPLICATION_UNSUPPORTED");
    String basket=model("2.4","","UNIQUE (BASKET) target: Label;");
    assertThat(generation.generateIliConstraintCases(basket,"ViewProof.Checks.Selected.target"))
        .containsEntry("reasonCode","VIEW_PROOF_SHAPE_UNSUPPORTED");
  }
}
