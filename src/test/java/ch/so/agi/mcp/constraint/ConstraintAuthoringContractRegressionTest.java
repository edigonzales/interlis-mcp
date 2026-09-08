package ch.so.agi.mcp.constraint;

import static org.assertj.core.api.Assertions.*;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.change.*;
import ch.so.agi.mcp.knowledge.KnowledgeRuleLoader;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.model.*;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ConstraintAuthoringContractRegressionTest {
  private static final String MODEL = """
      INTERLIS 2.4;
      MODEL ContractDemo (en) AT "https://example.org" VERSION "2026-09-08" =
        TOPIC Data =
          CLASS Item =
            status : MANDATORY (Drainage, Andere);
            value : MANDATORY 0..10;
          END Item;
        END Data;
      END ContractDemo.
      """;
  private final ObjectMapper mapper = new ObjectMapper();

  @Test void bothEnumSpellingsCompileProveAndRoundTripWithoutMutatingRequests() throws Exception {
    var compiler = new CountingCompiler();
    var engine = engine(compiler);
    IliAuthoringResult previous = null;
    for (String value : List.of("Drainage", "#Drainage", " #Drainage ")) {
      var spec = mapper.readValue("""
          {"kind":"MANDATORY","name":"Rule","condition":{"kind":"COMPARE","operator":"==",
          "children":[{"kind":"ATTRIBUTE","name":"status"},{"kind":"ENUM","value":"%s"}]}}
          """.formatted(value), IliConstraintSpec.Mandatory.class);
      var result = engine.author(MODEL, "ContractDemo.Data.Item", spec, null, null);
      assertThat(result.status).as(mapper.writeValueAsString(result)).isEqualTo(IliAuthoringResult.Status.GENERATED);
      assertThat(result.proofVerified).isTrue(); assertThat(result.specDiagnostics).isEmpty();
      assertThat(spec.condition.children.get(1).value).isEqualTo(value);
      if (previous != null) {
        assertThat(result.updatedModelText).isEqualTo(previous.updatedModelText);
        assertThat(result.constraintProofs.getFirst().coverageSolvedCount)
            .isEqualTo(previous.constraintProofs.getFirst().coverageSolvedCount);
      }
      previous = result;
    }
    assertThat(compiler.calls).isEqualTo(6);
  }

  @Test void decisionTableEnumFormsRemainEquivalentAndMissingSumIsActionable() {
    var compiler = new CountingCompiler();
    var tool = new ConstraintDecisionTableTools(engine(compiler));
    var condition = new ConstraintDecisionTableTools.DecisionCondition();
    condition.attribute="status"; condition.operator="=="; condition.value="Drainage";
    var row = new ConstraintDecisionTableTools.DecisionRow(); row.conditions=List.of(condition);
    var plain=tool.generateIliConstraintFromDecisionTable(MODEL,"ContractDemo.Data.Item","Rule",List.of(row));
    condition.value="#Drainage";
    var prefixed=tool.generateIliConstraintFromDecisionTable(MODEL,"ContractDemo.Data.Item","Rule",List.of(row));
    assertThat(plain.status).as(plain.toString()).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(prefixed.status).as(prefixed.toString()).isEqualTo(plain.status);
    assertThat(prefixed.updatedModelText).isEqualTo(plain.updatedModelText);
    assertThat(condition.value).isEqualTo("#Drainage");
    int calls=compiler.calls;
    condition.value="##Drainage";
    var invalid=tool.generateIliConstraintFromDecisionTable(MODEL,"ContractDemo.Data.Item","Rule",List.of(row));
    assertThat(invalid.specDiagnostics.getFirst().path()).isEqualTo("/rows/0/conditions/0/value");
    condition.value=null; condition.operator=null; condition.defined=false;
    invalid=tool.generateIliConstraintFromDecisionTable(MODEL,"ContractDemo.Data.Item","Rule",List.of(row));
    assertThat(invalid.status).isEqualTo(IliAuthoringResult.Status.INVALID_SPEC);
    assertThat(invalid.specDiagnostics.getFirst().path()).isEqualTo("/rows/0/conditions/0/defined");
    assertThat(invalid.specDiagnostics.getFirst().hint()).contains("aggregate=SUM");
    assertThat(compiler.calls).isEqualTo(calls);
  }

  @Test void modelAndBatchConstraintsUseTheSameValidationWithCompleteRequestPaths() throws Exception {
    var invalid = mapper.readValue("""
        {"kind":"MANDATORY","name":"Rule","condition":{"kind":"FUNCTION","name":"External.f",
         "functionOrigin":"MODEL","objects":{"kind":"ALL"},"children":[]}}
        """, IliConstraintSpec.Mandatory.class);
    var model = new IliModelSpec(); model.name="NewModel"; model.iliVersion="2.4";
    model.uri="https://example.org"; model.version="2026-09-08";
    var topic = new IliModelSpec.TopicSpec(); topic.name="Data";
    var clazz = new IliModelSpec.ClassSpec(); clazz.name="Item"; clazz.constraints=List.of(invalid);
    topic.classes=List.of(clazz); model.topics=List.of(topic);
    var renderer = new IliSpecRenderer(new AttributeTools(),new DomainTools());
    var error=catchThrowableOfType(() -> renderer.renderModel(model),SpecValidationException.class);
    assertThat(error.diagnostic().path()).isEqualTo("/spec/topics/0/classes/0/constraints/0/condition/objects");
    var compiler=new CountingCompiler();
    var service=new IliModelChangesService(compiler,null,renderer);
    var payload=new IliModelChangeRequest.AddConstraintChange(); payload.containerFqn="ContractDemo.Data.Item"; payload.constraint=invalid;
    var change=new IliModelChangeRequest(); change.setOperation(IliModelChangeOperation.ADD_CONSTRAINT); change.setAddConstraint(payload);
    var request=new IliModelChangesRequest(); request.changes=List.of(change);
    var result=service.apply(MODEL,request,null,null,null);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.INVALID_SPEC);
    assertThat(result.specDiagnostics.getFirst().path()).isEqualTo("/request/changes/0/addConstraint/constraint/condition/objects");
    assertThat(result.candidateModelText).isNull(); assertThat(result.updatedModelText).isNull();
    assertThat(compiler.calls).isEqualTo(1); // Only the unchanged input model is compiled by the batch workflow.
  }

  @Test void orderedComparisonNormalizesOnlyEnumPrefixes() throws Exception {
    var spec=mapper.readValue("""
        {"kind":"AND","children":[{"kind":"DEFINED","children":[{"kind":"ATTRIBUTE","name":"x"}]},
        {"kind":"BOOLEAN","value":true}]}
        """, IliConstraintSpec.ExpressionSpec.class);
    var type=ConstraintExpression.Type.scalar(ConstraintExpression.ScalarKind.NUMERIC);
    var defined=new ConstraintExpression.Defined(new ConstraintExpression.Attribute("x",type));
    var truth=new ConstraintExpression.BooleanLiteral(true);
    assertThat(ConstraintExpressionComparison.matches(spec,new ConstraintExpression.And(List.of(defined,truth)))).isTrue();
    assertThat(ConstraintExpressionComparison.matches(spec,new ConstraintExpression.And(List.of(truth,defined)))).isFalse();
    var text=new IliConstraintSpec.ExpressionSpec(); text.kind=IliConstraintSpec.ExpressionKind.TEXT; text.value="#Drainage";
    assertThat(ConstraintExpressionComparison.matches(text,new ConstraintExpression.TextLiteral("Drainage"))).isFalse();
    var enumeration=new IliConstraintSpec.ExpressionSpec(); enumeration.kind=IliConstraintSpec.ExpressionKind.ENUM; enumeration.value="#Drainage";
    assertThat(ConstraintExpressionComparison.matches(enumeration,new ConstraintExpression.EnumLiteral("Drainage"))).isTrue();
  }

  private static ConstraintAuthoringEngine engine(IliCompilerService compiler) {
    var analysis=new ModelAnalysisTools(compiler);
    return new ConstraintAuthoringEngine(new ConstraintAuthoringWorkflow(compiler),
        new IliSpecRenderer(new AttributeTools(),new DomainTools()),
        new ConstraintCaseGenerationTools(new ConstraintContextService(compiler),new ConstraintTestTools(compiler)),
        new ModelChangeReviewService(analysis,new ModelingRuleTools(new KnowledgeRuleLoader(),analysis,compiler)));
  }
  private static class CountingCompiler extends IliCompilerService {
    int calls;
    @Override public CompilationResult compile(String text,String repositories,String prefix) {
      calls++; return super.compile(text,repositories,prefix);
    }
  }
}
