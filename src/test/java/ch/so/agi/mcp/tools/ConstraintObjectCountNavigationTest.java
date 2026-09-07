package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.*;

import ch.so.agi.mcp.constraint.*;
import ch.so.agi.mcp.model.*;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConstraintObjectCountNavigationTest {
  private final IliCompilerService compiler = new IliCompilerService();
  private final ConstraintTestTools verifier = new ConstraintTestTools(compiler);
  private final ConstraintCaseGenerationTools generation = new ConstraintCaseGenerationTools(new ConstraintContextService(compiler), verifier);

  static String model(String version,String constraint) {
    if(version.equals("2.3"))constraint=constraint.replace("CONSTRAINT target:","CONSTRAINT ");
    return """
        INTERLIS %s;
        MODEL Objects (en) AT "https://example.org" VERSION "1" =
          TOPIC T =
            CLASS Root = Flag : BOOLEAN; END Root;
            CLASS Mid = END Mid;
            CLASS Leaf = END Leaf;
            ASSOCIATION RM = roots -- {0..*} Root; mids -- {0..*} Mid; END RM;
            ASSOCIATION ML = middles -- {0..*} Mid; leaves -- {0..*} Leaf; END ML;
            CONSTRAINTS OF Root =
              !!@ name = "target"
              %s
            END;
          END T;
        END Objects.
        """.formatted(version,constraint);
  }

  @ParameterizedTest @ValueSource(strings={"2.3","2.4"})
  void mandatoryAndSetProveMultiValuedNavigationWithoutProbeAttributes(String version) {
    for(String kind:List.of("MANDATORY","SET")) {
      var proof=generation.generateIliConstraintCases(model(version,kind+" CONSTRAINT target: INTERLIS.objectCount(mids->leaves) >= 2;"),"Objects.T.Root.target");
      assertThat(proof.get("generationVerified")).as(proof.toString()).isEqualTo(true);
      assertThat(proof.get("coverageComplete")).isEqualTo(true);
      var checked=(Map<?,?>)proof.get("verification");
      assertThat(checked.get("allPassed")).isEqualTo(true);
      for(var item:(List<Map<String,Object>>)checked.get("cases")) {
        assertThat(item.get("objectCounts")).isInstanceOf(List.class);
        for(var count:(List<Map<String,Object>>)item.get("objectCounts"))assertThat(count.get("plannedCount")).isEqualTo(count.get("actualCount"));
      }
    }
  }

  @ParameterizedTest @ValueSource(strings={"2.3","2.4"})
  void decisionTableAuthorsObjectCountThroughTheCommonPipeline(String version) {
    var condition=new ConstraintDecisionTableTools.DecisionCondition();condition.attribute="mids->leaves";condition.aggregate="OBJECT_COUNT";condition.operator=">=";condition.value=2;
    var row=new ConstraintDecisionTableTools.DecisionRow();row.name="two leaves";row.conditions=List.of(condition);
    var tool=new ConstraintDecisionTableTools(new ConstraintAuthoringWorkflow(compiler),generation);
    var result=tool.generateIliConstraintFromDecisionTable(model(version,""),"Objects.T.Root","counted",List.of(row));
    assertThat(result.status).as(result.toString()).isEqualTo(IliAuthoringResult.Status.GENERATED);
    condition.defined=true;
    assertThat(tool.generateIliConstraintFromDecisionTable(model(version,""),"Objects.T.Root","invalid",List.of(row)).status).isEqualTo(IliAuthoringResult.Status.INVALID_SPEC);
  }

  @ParameterizedTest @ValueSource(strings={"2.3","2.4"})
  void realValidatorCountsOccurrencesAndReportsDistinctIdentities(String version) {
    for(int shape=0;shape<4;shape++) {
      var c=new ConstraintTestTools.TestCase();c.name="shape "+shape;c.expectedConstraintValid=shape>=2;c.objects=new ArrayList<>();c.links=new ArrayList<>();
      c.objects.add(object("Root","r"));
      if(shape>0){c.objects.add(object("Mid","m1"));c.links.add(link("RM","roots","r","mids","m1"));}
      if(shape>=2){c.objects.add(object("Mid","m2"));c.objects.add(object("Leaf","l1"));c.links.add(link("RM","roots","r","mids","m2"));c.links.add(link("ML","middles","m1","leaves","l1"));if(shape==3)c.objects.add(object("Leaf","l2"));c.links.add(link("ML","middles","m2","leaves",shape==2?"l1":"l2"));}
      var result=verifier.testIliConstraint(model(version,"MANDATORY CONSTRAINT target: INTERLIS.objectCount(mids->leaves) == 2;"),"Objects.T.Root.target",List.of(c));
      var checked=((List<Map<String,Object>>)result.get("cases")).getFirst();
      assertThat(checked).as(checked.toString()).containsEntry("passed",true).containsEntry("fixtureValid",true);
      var count=((List<Map<String,Object>>)checked.get("objectCounts")).getFirst();
      assertThat(count.get("actualCount")).isEqualTo(shape>=2?2:0);
      assertThat(count.get("distinctTargetCount")).isEqualTo(shape==3?2:shape==2?1:0);
    }
  }

  @Test void sharedPrefixAssignmentsMustAgree() {
    String source=model("2.4","MANDATORY CONSTRAINT target: INTERLIS.objectCount(mids) == 2 AND INTERLIS.objectCount(mids->leaves) == 3;");
    var proof=generation.generateIliConstraintCases(source,"Objects.T.Root.target");
    assertThat(proof.get("generationVerified")).as(proof.toString()).isEqualTo(true);
  }

  @Test void everyPolymorphicIntermediateRouteIsProved() {
    String source=model("2.4","MANDATORY CONSTRAINT target: INTERLIS.objectCount(mids->leaves) >= 2;")
        .replace("CLASS Mid = END Mid;","CLASS Mid (ABSTRACT) = END Mid; CLASS First EXTENDS Mid = END First; CLASS Second EXTENDS Mid = END Second;");
    var proof=generation.generateIliConstraintCases(source,"Objects.T.Root.target");
    assertThat(proof.get("generationVerified")).as(proof.toString()).isEqualTo(true);
    var cases=(List<Map<String,Object>>)proof.get("generatedCases");
    assertThat(cases).anySatisfy(c->assertThat(c.get("concreteRoute").toString()).contains("First"));
    assertThat(cases).anySatisfy(c->assertThat(c.get("concreteRoute").toString()).contains("Second"));
  }

  @ParameterizedTest @ValueSource(strings={"2.3","2.4"})
  void threeMultiValuedStepsHaveEmptyAndBranchCoverage(String version) {
    String source=model(version,"MANDATORY CONSTRAINT target: INTERLIS.objectCount(mids->leaves->tips) >= 2;")
        .replace("CONSTRAINTS OF Root =","CLASS Tip = END Tip; ASSOCIATION LT = owners -- {0..*} Leaf; tips -- {0..*} Tip; END LT; CONSTRAINTS OF Root =");
    var proof=generation.generateIliConstraintCases(source,"Objects.T.Root.target");
    assertThat(proof.get("generationVerified")).as(proof.toString()).isEqualTo(true);
    var goals=(List<Map<String,Object>>)proof.get("objectPathGoals");
    for(int i=0;i<3;i++) {String goal="EMPTY_STEP_"+i;assertThat(goals).anySatisfy(g->assertThat(g.get("goal")).isEqualTo(goal));}
    var checked=(List<Map<String,Object>>)((Map<?,?>)proof.get("verification")).get("cases");
    assertThat(checked).filteredOn(c->c.get("name").toString().contains("SHARED_TARGET"))
        .allSatisfy(c->assertThat((List<Map<String,Object>>)c.get("objectCounts")).anySatisfy(count->{assertThat(count.get("actualCount")).isEqualTo(2);assertThat(count.get("distinctTargetCount")).isEqualTo(1);}));
  }

  @ParameterizedTest @ValueSource(strings={"2.3","2.4"})
  void finiteCyclicPathRetainsOccurrenceMultiplicity(String version) {
    String source=model(version,"MANDATORY CONSTRAINT target: INTERLIS.objectCount(mids->roots->mids->leaves) == 2;");
    var c=new ConstraintTestTools.TestCase();c.name="finite cycle";c.expectedConstraintValid=true;
    c.objects=List.of(object("Root","r"),object("Mid","m1"),object("Mid","m2"),object("Leaf","l"));
    c.links=List.of(link("RM","roots","r","mids","m1"),link("RM","roots","r","mids","m2"),link("ML","middles","m1","leaves","l"));
    var checked=((List<Map<String,Object>>)verifier.testIliConstraint(source,"Objects.T.Root.target",List.of(c)).get("cases")).getFirst();
    assertThat(checked).as(checked.toString()).containsEntry("passed",true);
    assertThat(((List<Map<String,Object>>)checked.get("objectCounts")).getFirst()).containsEntry("actualCount",2).containsEntry("distinctTargetCount",1);
  }

  @ParameterizedTest @ValueSource(strings={"2.3","2.4"})
  void concreteCompositionAndReferenceIntermediatesAreCounted(String version) {
    String source=model(version,"MANDATORY CONSTRAINT target: INTERLIS.objectCount(entries->ref) == 1;")
        .replace("CLASS Root = Flag : BOOLEAN; END Root;", "CLASS Leaf = END Leaf; STRUCTURE Entry = ref : REFERENCE TO Leaf; END Entry; CLASS Root = entries : BAG {0..*} OF Entry; END Root;")
        .replace("CLASS Mid = END Mid;\n            CLASS Leaf = END Leaf;","CLASS Mid = END Mid;");
    // Remove the second Leaf declaration regardless of text-block indentation.
    int duplicate=source.indexOf("CLASS Leaf = END Leaf;",source.indexOf("CLASS Leaf = END Leaf;")+1);
    if(duplicate>=0)source=source.substring(0,duplicate)+source.substring(duplicate+"CLASS Leaf = END Leaf;".length());
    var proof=generation.generateIliConstraintCases(source,"Objects.T.Root.target");
    assertThat(proof.get("generationVerified")).as(proof.toString()).isEqualTo(true);
  }

  @Test void budgetsProduceBoundariesAndNeverAProof() {
    String source=model("2.4","MANDATORY CONSTRAINT target: INTERLIS.objectCount(mids->roots->mids->roots->mids->roots->mids->roots->mids) == 1;");
    var proof=generation.generateIliConstraintCases(source,"Objects.T.Root.target");
    assertThat(proof).containsEntry("generationVerified",false).containsEntry("reasonCode","OBJECT_PATH_STEP_BUDGET_EXCEEDED");
    source=model("2.4","MANDATORY CONSTRAINT target: INTERLIS.objectCount(mids->leaves) == 65;");
    proof=generation.generateIliConstraintCases(source,"Objects.T.Root.target");
    assertThat(proof).as(proof.toString()).containsEntry("coverageComplete",false);
  }

  @Test void p05HydraulicAlternativesAndInvalidDoubleLink() throws Exception {
    String source=java.nio.file.Files.readString(java.nio.file.Path.of("evals/constraint-reconstruction/v1/public/P05/model.ili"))
        .replace("END v_Drainageleitung;","!!@ name = \"counted\"\n MANDATORY CONSTRAINT INTERLIS.objectCount(Knoten_vonRef) == 1;\n END v_Drainageleitung;");
    String target="VSADSSMINI_2020_LV95_Validierung_Drainage_20250311.VSADSSMini_Validierung.v_Drainageleitung.counted";
    var resolution=new ConstraintContextService(compiler).compileAndResolve(source,target,null,"p05_count_");
    assertThat(resolution.context()).as(resolution.toString()).isNotNull();
    var context=resolution.context();
    String base="VSADSSMINI_2020_LV95.VSADSSMini.";
    for(String hydraulic:List.of("Sickerleitung","Drainagetransportleitung","Pumpendruckleitung")) for(int count=0;count<=2;count++) {
      var c=new ConstraintTestTools.TestCase();c.name=hydraulic+" count "+count;c.expectedConstraintValid=count==1;c.objects=new ArrayList<>();c.links=new ArrayList<>();
      var root=new ConstraintTestTools.TestObject();root.classFqn=base+"Leitung";root.oid="r";root.values=Map.of("FunktionHierarchisch","SAA.andere","FunktionHydraulisch",hydraulic,"Nutzungsart_Ist","Reinabwasser");c.objects.add(root);
      for(int index=0;index<count;index++) {var node=new ConstraintTestTools.TestObject();node.classFqn=base+"Knoten";node.oid="k"+index;c.objects.add(node);var edge=new ConstraintTestTools.TestLink();edge.associationFqn=base+"Leitung_Knoten_vonAssoc";edge.roles=Map.of("Knoten_vonRef",node.oid,"Leitung_Knoten_vonAssocRef","r");c.links.add(edge);}
      var prepared=ViewProofFixtures.prepare(context,List.of(c));
      var checked=((List<Map<String,Object>>)verifier.testCompiledConstraint(context,prepared).get("cases")).getFirst();
      if(count==2) assertThat(checked).as(checked.toString()).containsEntry("passed",false).containsEntry("fixtureValid",false).containsEntry("fixturePreparationReasonCode","OBJECT_PATH_CARDINALITY_VIOLATION");
      else { assertThat(checked).as(checked.toString()).containsEntry("passed",true).containsEntry("fixtureValid",true).containsEntry("subjectCount",1).containsEntry("actualConstraintValid",count==1); }
    }
  }

  @Test void polymorphicEndpointsAndMissingRoutesAreExplicit() {
    String source=model("2.4","MANDATORY CONSTRAINT target: INTERLIS.objectCount(mids->leaves) >= 2;")
        .replace("CLASS Leaf = END Leaf;","CLASS Leaf (ABSTRACT) = END Leaf; CLASS Alpha EXTENDS Leaf = END Alpha; CLASS Beta EXTENDS Leaf = END Beta;");
    var proof=generation.generateIliConstraintCases(source,"Objects.T.Root.target");
    assertThat(proof).as(proof.toString()).containsEntry("generationVerified",true).containsEntry("coverageComplete",true);
    var checked=(List<Map<String,Object>>)((Map<?,?>)proof.get("verification")).get("cases");
    assertThat(checked).anySatisfy(c->{assertThat(c.get("name").toString()).contains("MIXED_TYPES_STEP_1");assertThat(((List<Map<String,Object>>)c.get("objectCounts")).getFirst()).containsEntry("topologyVerified",true);});
    source=source.replace(" CLASS Alpha EXTENDS Leaf = END Alpha; CLASS Beta EXTENDS Leaf = END Beta;","");
    assertThat(generation.generateIliConstraintCases(source.replace("TOPIC T =", "TOPIC T (ABSTRACT) ="),"Objects.T.Root.target")).containsEntry("reasonCode","OBJECT_PATH_CONCRETE_ROUTE_UNAVAILABLE");
    StringBuilder children=new StringBuilder();for(int i=0;i<9;i++)children.append(" CLASS Child").append(i).append(" EXTENDS Leaf = END Child").append(i).append(";");
    source=source.replace("CLASS Leaf (ABSTRACT) = END Leaf;","CLASS Leaf (ABSTRACT) = END Leaf;"+children);
    assertThat(generation.generateIliConstraintCases(source,"Objects.T.Root.target")).containsEntry("reasonCode","OBJECT_PATH_ROUTE_BUDGET_EXCEEDED");
  }

  @Test void graphBudgetAndMissingReferencesAreStructuredFixtureFailures() {
    String source=model("2.4","MANDATORY CONSTRAINT target: INTERLIS.objectCount(mids) >= 2;");
    var c=new ConstraintTestTools.TestCase();c.name="too large";c.expectedConstraintValid=true;c.objects=new ArrayList<>();c.links=new ArrayList<>();c.objects.add(object("Root","r"));
    for(int i=0;i<32;i++){c.objects.add(object("Mid","m"+i));c.links.add(link("RM","roots","r","mids","m"+i));}
    var checked=((List<Map<String,Object>>)verifier.testIliConstraint(source,"Objects.T.Root.target",List.of(c)).get("cases")).getFirst();
    assertThat(checked).containsEntry("passed",false).containsEntry("fixturePreparationReasonCode","OBJECT_PATH_FIXTURE_BUDGET_EXCEEDED");
    c.objects=List.of(object("Root","r"));c.links=List.of(link("RM","roots","r","mids","missing"));
    checked=((List<Map<String,Object>>)verifier.testIliConstraint(source,"Objects.T.Root.target",List.of(c)).get("cases")).getFirst();
    assertThat(checked).containsEntry("passed",false).containsEntry("fixtureValid",false);
  }

  @Test void observedCountsCannotReleaseIncorrectPlans() {
    String source=model("2.4","MANDATORY CONSTRAINT target: INTERLIS.objectCount(mids) == 1;");
    var c=new ConstraintTestTools.TestCase();c.name="wrong planned count";c.expectedConstraintValid=true;
    c.objects=List.of(object("Root","r"),object("Mid","m"));c.links=List.of(link("RM","roots","r","mids","m"));c.plannedObjectCounts=Map.of("r",Map.of("mids",2));
    var checked=((List<Map<String,Object>>)verifier.testIliConstraint(source,"Objects.T.Root.target",List.of(c)).get("cases")).getFirst();
    assertThat(checked).containsEntry("passed",false).containsEntry("fixtureValid",false).containsEntry("fixturePreparationReasonCode","OBJECT_PATH_COUNT_MISMATCH");
  }

  @Test void inheritedRoleOnConcreteSubclassIsCounted() {
    String source=model("2.4","MANDATORY CONSTRAINT target: INTERLIS.objectCount(mids->leaves) >= 2;")
        .replace("CLASS Mid = END Mid;","CLASS Mid = END Mid; CLASS Derived EXTENDS Root = END Derived;")
        .replace("CONSTRAINTS OF Root =","CONSTRAINTS OF Derived =");
    var proof=generation.generateIliConstraintCases(source,"Objects.T.Derived.target");
    assertThat(proof).as(proof.toString()).containsEntry("generationVerified",true).containsEntry("coverageComplete",true);
  }

  @ParameterizedTest @ValueSource(strings={"2.3","2.4"})
  void publicMandatoryAuthorsNestedCountsAndKeepsARejectedCandidate(String version) throws Exception {
    var analysis=new ch.so.agi.mcp.analysis.ModelAnalysisTools(compiler);
    var engine=new ConstraintAuthoringEngine(new ConstraintAuthoringWorkflow(compiler),new IliSpecRenderer(new AttributeTools(),new DomainTools()),generation,
        new ch.so.agi.mcp.analysis.ModelChangeReviewService(analysis,new ch.so.agi.mcp.knowledge.ModelingRuleTools(new ch.so.agi.mcp.knowledge.KnowledgeRuleLoader(),analysis,compiler)));
    var spec=new tools.jackson.databind.ObjectMapper().readValue("""
        {"kind":"MANDATORY","name":"nested","condition":{"kind":"IMPLIES","children":[
          {"kind":"ATTRIBUTE","name":"Flag"},
          {"kind":"NOT","children":[{"kind":"COMPARE","operator":"<","children":[
            {"kind":"OBJECT_COUNT","objects":{"kind":"PATH","path":"mids->leaves"}},
            {"kind":"NUMERIC","value":2}]}]}]}}
        """,IliConstraintSpec.Mandatory.class);
    var result=new ConstraintAuthoringTools(engine).authorIliMandatoryConstraint(model(version,""),"Objects.T.Root",spec,null,null);
    assertThat(result.status).as(result.toString()).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(result.updatedModelText).contains("INTERLIS.objectCount(mids->leaves)").doesNotContain("IMPLIES");
    assertThat(result.constraintProofs.getFirst().verification.cases).allSatisfy(c->assertThat(c.objectCounts).isNotEmpty());
    ((IliConstraintSpec.PathObjectsSpec)spec.condition.children.get(1).children.getFirst().children.getFirst().objects).path="mids->roots->mids->roots->mids->roots->mids->roots->mids";
    result=new ConstraintAuthoringTools(engine).authorIliMandatoryConstraint(model(version,""),"Objects.T.Root",spec,null,null);
    assertThat(result.generated).isFalse();assertThat(result.proofVerified).isFalse();assertThat(result.candidateModelText).isNotBlank();assertThat(result.updatedModelText).isNull();
  }

  @Test void sharedScalarAssignmentsAreSolvedWithCounts() {
    // Bind the independent typed scalar collection and count to the same compiled navigation.
    var compilation=compiler.compile(model("2.4","").replace("CLASS Leaf = END Leaf;", "CLASS Leaf = Weight : MANDATORY 0 .. 10; END Leaf;"),null);
    assertThat(compilation.valid()).isTrue();
    var count=new ConstraintExpression.ObjectCount("mids->leaves");
    var weights=new ConstraintExpression.Path("mids->leaves->Weight",new ConstraintExpression.Type(ConstraintExpression.ScalarKind.NUMERIC,true,true));
    var references=new LinkedHashMap<>(ConstraintModelSynthesizer.bind(compilation.transferDescription(),"Objects.T.Root",count).references());
    references.putAll(ConstraintModelSynthesizer.bind(compilation.transferDescription(),"Objects.T.Root",weights).references());
    var binding=new ConstraintModelSynthesizer.ModelBinding("Objects.T.Root",references);
    var assignment=Map.<String,Object>of(count.key(),java.math.BigDecimal.valueOf(2),weights.path(),List.of(java.math.BigDecimal.valueOf(4),java.math.BigDecimal.valueOf(6)));
    var graph=ConstraintModelSynthesizer.synthesize(binding,assignment,"joint");
    assertThat(graph.objects()).filteredOn(o->o.classFqn().equals("Objects.T.Leaf")).extracting(o->o.values().get("Weight")).containsExactly(java.math.BigDecimal.valueOf(4),java.math.BigDecimal.valueOf(6));
    assertThatThrownBy(()->ConstraintModelSynthesizer.synthesize(binding,assignment,"shared",new ConstraintModelSynthesizer.CountShape("mids->leaves",0,-1,true,Map.of())))
        .isInstanceOf(IllegalArgumentException.class);
    var polymorphic=compiler.compile(model("2.4","").replace("CLASS Leaf = END Leaf;", "CLASS Leaf (ABSTRACT) = Weight : MANDATORY 0 .. 10; END Leaf; CLASS Alpha EXTENDS Leaf = END Alpha; CLASS Beta EXTENDS Leaf = END Beta;"),null);
    assertThat(polymorphic.valid()).isTrue();
    var route=Map.of("mids->leaves#0","Objects.T.Mid","mids->leaves#1","Objects.T.Beta");
    var concreteRefs=new LinkedHashMap<>(ConstraintModelSynthesizer.bind(polymorphic.transferDescription(),"Objects.T.Root",count,route).references());
    concreteRefs.putAll(ConstraintModelSynthesizer.bind(polymorphic.transferDescription(),"Objects.T.Root",weights,route).references());
    var concreteGraph=ConstraintModelSynthesizer.synthesize(new ConstraintModelSynthesizer.ModelBinding("Objects.T.Root",concreteRefs),assignment,"poly");
    assertThat(concreteGraph.objects()).filteredOn(o->o.classFqn().equals("Objects.T.Beta")).hasSize(2);

  }

  @ParameterizedTest @ValueSource(strings={"2.3","2.4"})
  void zeroCountHasIndependentNegativeBoundaryExclusions(String version) {
    for(String kind:List.of("MANDATORY","SET")) {
      var proof=generation.generateIliConstraintCases(model(version,kind+" CONSTRAINT target: INTERLIS.objectCount(mids->leaves) == 0;"),"Objects.T.Root.target");
      assertThat(proof).as(proof.toString()).containsEntry("generationVerified",true).containsEntry("coverageComplete",true);
    }
  }

  static ConstraintTestTools.TestObject object(String type,String oid) {
    var object=new ConstraintTestTools.TestObject();object.classFqn="Objects.T."+type;object.oid=oid;return object;
  }
  static ConstraintTestTools.TestLink link(String association,String a,String x,String b,String y) {
    var link=new ConstraintTestTools.TestLink();link.associationFqn="Objects.T."+association;link.roles=Map.of(a,x,b,y);return link;
  }
}
