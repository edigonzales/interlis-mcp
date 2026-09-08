package ch.so.agi.mcp.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.so.agi.mcp.constraint.ConstraintAuthoringEngine;
import ch.so.agi.mcp.constraint.ConstraintAuthoringWorkflow;
import ch.so.agi.mcp.model.IliConstraintSpec.*;
import ch.so.agi.mcp.tools.AttributeTools;
import ch.so.agi.mcp.tools.DomainTools;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConstraintSpecContractTest {
  private static ExpressionSpec node(ExpressionKind kind) {
    ExpressionSpec result = new ExpressionSpec(); result.kind = kind; return result;
  }
  private static ExpressionSpec number() {
    ExpressionSpec result = node(ExpressionKind.NUMERIC); result.value = 1; return result;
  }
  private static Mandatory mandatory(ExpressionSpec condition) {
    Mandatory spec = new Mandatory(); spec.name = "Rule"; spec.condition = condition; return spec;
  }
  private static SpecValidationException failure(Runnable run, String code, String path) {
    var error = catchThrowableOfType(run::run, SpecValidationException.class);
    assertThat(error).isNotNull();
    assertThat(error.diagnostic().code()).isEqualTo(code);
    assertThat(error.diagnostic().path()).isEqualTo(path);
    return error;
  }

  @Test void rejectsIgnoredFunctionObjectsBeforeCompilingAndKeepsOriginalPayload() {
    ExpressionSpec function = node(ExpressionKind.FUNCTION);
    function.name = "External.group"; function.functionOrigin = FunctionOrigin.MODEL;
    function.objects = new AllObjectsSpec(); function.children = List.of(number());
    var workflow = mock(ConstraintAuthoringWorkflow.class);
    var engine = new ConstraintAuthoringEngine(workflow,
        new IliSpecRenderer(new AttributeTools(), new DomainTools()), null, null);
    var result = engine.author("INTERLIS 2.4;", "Demo.Data.Item", mandatory(function), null, null);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.INVALID_SPEC);
    assertThat(result.reasonCode).isEqualTo("INVALID_SPEC");
    assertThat(result.specDiagnostics).singleElement().satisfies(d -> {
      assertThat(d.code()).isEqualTo("INVALID_FIELD");
      assertThat(d.path()).isEqualTo("/spec/condition/objects");
      assertThat(d.hint()).contains("children");
    });
    assertThat(result.candidateModelText).isNull(); assertThat(result.updatedModelText).isNull();
    assertThat(result.generated).isFalse(); assertThat(result.proofVerified).isFalse();
    assertThat(function.objects).isInstanceOf(AllObjectsSpec.class);
    verifyNoInteractions(workflow);
  }

  @Test void countExpressionAndSetCountHaveDifferentContracts() {
    ExpressionSpec count = node(ExpressionKind.OBJECT_COUNT); count.objects = new AllObjectsSpec();
    count.operator = "=="; count.value = 1;
    assertThat(failure(() -> ConstraintSpecContract.expression(count,"2.4","/expr"),
        "INVALID_FIELD", "/expr/operator").diagnostic().hint()).contains("COMPARE", "threshold");
    count.operator = null;
    failure(() -> ConstraintSpecContract.expression(count,"2.4","/expr"), "INVALID_FIELD", "/expr/value");
    count.value = null;
    ExpressionSpec compare = node(ExpressionKind.COMPARE); compare.operator = "==";
    compare.children = List.of(count, number());
    ConstraintSpecContract.expression(compare,"2.4","/expr");
    var condition = new ObjectCountSetConditionSpec(); condition.objects = new AllObjectsSpec();
    condition.operator = ">="; condition.threshold = java.math.BigDecimal.ONE;
    var set = new IliConstraintSpec.Set(); set.name = "Rule"; set.scope = SetScope.GLOBAL; set.condition = condition;
    ConstraintSpecContract.validate(set, "2.4", "/spec");
    condition.threshold = null;
    failure(() -> ConstraintSpecContract.validate(set,"2.4","/spec"), "MISSING_FIELD", "/spec/condition/threshold");
  }

  @Test void missingNullAndExcessChildrenHaveDeterministicPaths() {
    ExpressionSpec compare = node(ExpressionKind.COMPARE); compare.operator = "==";
    compare.children = List.of(number());
    failure(() -> ConstraintSpecContract.expression(compare,"2.4","/expr"), "INVALID_ARITY", "/expr/children");
    compare.children = Arrays.asList(number(), null);
    failure(() -> ConstraintSpecContract.expression(compare,"2.4","/expr"), "MISSING_FIELD", "/expr/children/1");
    compare.children = List.of(number(),number(),number());
    failure(() -> ConstraintSpecContract.expression(compare,"2.4","/expr"), "INVALID_ARITY", "/expr/children");
    var leaf = number(); leaf.children = List.of(number());
    failure(() -> ConstraintSpecContract.expression(leaf,"2.4","/expr"), "INVALID_ARITY", "/expr/children");
    leaf.children = List.of(); ConstraintSpecContract.expression(leaf,"2.4","/expr");
  }

  static Stream<Object> badEnums() { return Stream.of(null, "", "#", "##Drainage", "A->B", "A..B", 1, true, List.of("Drainage")); }
  @ParameterizedTest @MethodSource("badEnums") void rejectsMalformedEnums(Object value) {
    assertThatThrownBy(() -> EnumLiteralValue.normalize(value,"/enum/value"))
        .isInstanceOf(SpecValidationException.class).hasMessageContaining("/enum/value");
  }
  @ParameterizedTest @ValueSource(strings={"Drainage", "#Drainage", " Drainage ", " #Drainage "})
  void acceptsBothEnumFormsWithoutMutatingRequests(String value) {
    ExpressionSpec e = node(ExpressionKind.ENUM); e.value = value;
    var renderer = new IliSpecRenderer(new AttributeTools(), new DomainTools());
    assertThat(renderer.renderExpression(e,"2.4","Demo",new LinkedHashSet<>())).isEqualTo("#Drainage");
    assertThat(e.value).isEqualTo(value);
    assertThat(EnumLiteralValue.normalize("#Gruppe.Drainage", "/value")).isEqualTo("Gruppe.Drainage");
  }
  @Test void textValuesIncludingEmptyStringsAndHashesRemainUntouched() {
    var renderer = new IliSpecRenderer(new AttributeTools(), new DomainTools());
    for (ExpressionKind kind : List.of(ExpressionKind.TEXT,ExpressionKind.MTEXT)) {
      for (String value : List.of("", "#Drainage", " #Drainage ")) {
        var e=node(kind); e.value=value;
        assertThat(renderer.renderExpression(e,"2.4","Demo",new LinkedHashSet<>())).isEqualTo("\""+value+"\"");
        assertThat(e.value).isEqualTo(value);
      }
    }
  }
  @Test void technicalNamesHaveSuggestionsButNoAutomaticRenaming() {
    var spec=mandatory(number()); spec.name="10201";
    assertThat(failure(() -> ConstraintSpecContract.validate(spec,"2.4","/spec"),
        "INVALID_IDENTIFIER","/spec/name").diagnostic().hint()).contains("Regel10201","business rule number");
    assertThat(spec.name).isEqualTo("10201");
    assertThat(ConstraintSpecContract.technicalName("Rule42","/spec/name")).isEqualTo("Rule42");
  }
  @Test void preservesReservedWordAndLengthRejections() {
    for (String invalid : List.of("CLASS", "a".repeat(256))) {
      failure(() -> ConstraintSpecContract.technicalName(invalid,"/spec/name"), "INVALID_IDENTIFIER","/spec/name");
    }
    assertThat(ConstraintSpecContract.technicalName("a".repeat(255),"/spec/name")).hasSize(255);
    var attribute=node(ExpressionKind.ATTRIBUTE); attribute.name="CLASS";
    failure(() -> ConstraintSpecContract.expression(attribute,"2.4","/expr"), "INVALID_IDENTIFIER","/expr/name");
  }

  @ParameterizedTest @ValueSource(strings={"2.3","2.4"})
  void standardFunctionHintsUseVersionedRegistryWithoutChangingOrigin(String version) {
    for (String local : List.of("sum","add")) {
      var f=node(ExpressionKind.FUNCTION); f.functionOrigin=FunctionOrigin.STANDARD;
      f.name=(version.equals("2.4")?"Math_V2.":"Math.")+local;
      f.children=List.of(number(),number());
      var error=failure(() -> ConstraintSpecContract.expression(f,version,"/expr"), "UNKNOWN_STANDARD_FUNCTION","/expr/name");
      assertThat(error.diagnostic().hint()).contains(local.equals("sum")?"COLLECTION_SUM":"NUMERIC_ADD", "functionOrigin=STANDARD");
      assertThat(f.functionOrigin).isEqualTo(FunctionOrigin.STANDARD);
    }
    var unknown=failure(() -> ConstraintSpecContract.standardFunction("NoSuch.function",version,"/name"), "UNKNOWN_STANDARD_FUNCTION","/name");
    assertThat(unknown.diagnostic().hint()).contains("listConstraintFunctions").doesNotContain("Use FUNCTION.name=");
  }
  @Test void standardFunctionArityIsCheckedAndExternalArityRemainsWithCompiler() {
    var f=node(ExpressionKind.FUNCTION); f.functionOrigin=FunctionOrigin.STANDARD; f.name="NUMERIC_ADD";
    f.children=List.of(number());
    failure(() -> ConstraintSpecContract.expression(f,"2.4","/expr"), "INVALID_ARITY","/expr/children");
    f.children=List.of(number(),number()); ConstraintSpecContract.expression(f,"2.4","/expr");
    f.functionOrigin=FunctionOrigin.MODEL; f.name="External.zero"; f.children=List.of();
    ConstraintSpecContract.expression(f,"2.4","/expr");
  }
}
