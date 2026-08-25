package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.knowledge.KnowledgeRuleLoader;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.model.AttributeLineRequest;
import ch.so.agi.mcp.model.BaseType;
import ch.so.agi.mcp.model.GeometryTypeSpec;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.model.IliConstraintSpec;
import ch.so.agi.mcp.model.IliModelSpec;
import ch.so.agi.mcp.model.IliSpecRenderer;
import ch.so.agi.mcp.model.TypeSpec;
import ch.so.agi.mcp.service.IliCompilerService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class IliModelAuthoringToolsTest {

  @Test
  void authorsChBaseModelWithAllFiveConstraintProofsUsingOneCompile() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelAuthoringTools tools = tools(compiler);

    IliAuthoringResult result = tools.authorIliModel(
        completeSpec(), ModelPurpose.CAPTURE, ModelingRuleProfile.CORE);

    assertThat(compiler.calls).isEqualTo(1);
    assertThat(result.status).as("%s", result.constraintProofs).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(result.complete).isTrue();
    assertThat(result.generated).isTrue();
    assertThat(result.proofVerified).isTrue();
    assertThat(result.constraintProofs).hasSize(5)
        .allSatisfy(proof -> assertThat(proof.proofVerified).isTrue());
    assertThat(result.derivedImports).containsExactly("GeometryCHLV95_V2");
    assertThat(result.afterReview).isNotNull();
    assertThat(result.updatedModelText)
        .contains("IMPORTS GeometryCHLV95_V2;")
        .contains("UNIQUE code;")
        .contains("EXISTENCE CONSTRAINT");
    assertThat(result.candidateModelText).isNull();
  }

  @Test
  void externalFunctionWithholdsNewModelAfterSingleCompile() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelSpec model = completeSpec();
    model.imports = List.of("Text_V2");
    IliConstraintSpec.ExpressionSpec code = new IliConstraintSpec.ExpressionSpec();
    code.kind = IliConstraintSpec.ExpressionKind.ATTRIBUTE;
    code.name = "code";
    IliConstraintSpec.ExpressionSpec prefix = new IliConstraintSpec.ExpressionSpec();
    prefix.kind = IliConstraintSpec.ExpressionKind.TEXT;
    prefix.value = "A";
    IliConstraintSpec.ExpressionSpec function = new IliConstraintSpec.ExpressionSpec();
    function.kind = IliConstraintSpec.ExpressionKind.FUNCTION;
    function.name = "Text_V2.startsWith";
    function.functionOrigin = IliConstraintSpec.FunctionOrigin.VALIDATOR_EXTENSION;
    function.children = List.of(code, prefix);
    IliConstraintSpec.Mandatory constraint = new IliConstraintSpec.Mandatory();
    constraint.name = "ExternalFunctionRule";
    constraint.condition = function;
    model.topics.getFirst().classes.get(1).constraints = List.of(constraint);

    IliAuthoringResult result = tools(compiler).authorIliModel(
        model, ModelPurpose.CAPTURE, ModelingRuleProfile.CORE);

    assertThat(compiler.calls).isEqualTo(1);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.EXTERNAL_FUNCTION_SEMANTICS_REQUIRED);
    assertThat(result.updatedModelText).isNull();
    assertThat(result.candidateModelText).contains("Text_V2.startsWith(code, \"A\")");
    assertThat(result.proofVerified).isFalse();
  }

  private IliModelAuthoringTools tools(IliCompilerService compiler) {
    IliSpecRenderer renderer = new IliSpecRenderer(new AttributeTools(), new DomainTools());
    ConstraintContextService contexts = new ConstraintContextService(compiler);
    ConstraintCaseGenerationTools cases = new ConstraintCaseGenerationTools(
        contexts, new ConstraintTestTools(compiler));
    ModelAnalysisTools analysis = new ModelAnalysisTools(compiler);
    ModelingRuleTools rules = new ModelingRuleTools(
        new KnowledgeRuleLoader(), analysis, compiler);
    ModelChangeReviewService review = new ModelChangeReviewService(analysis, rules);
    return new IliModelAuthoringTools(renderer, compiler, contexts, cases, review);
  }

  private IliModelSpec completeSpec() {
    IliModelSpec spec = new IliModelSpec();
    spec.name = "AuthorDemo";
    spec.uri = "https://example.org/author-demo";
    spec.version = "2026-08-24";
    spec.iliVersion = "2.4";
    spec.language = "de";

    IliModelSpec.UnitSpec centimeter = new IliModelSpec.UnitSpec();
    centimeter.name = "Centimeter";
    centimeter.factor = new BigDecimal("0.01");
    centimeter.baseUnitFqn = "INTERLIS.m";
    spec.units = List.of(centimeter);
    IliModelSpec.DomainSpec height = new IliModelSpec.DomainSpec();
    height.name = "Height";
    height.kind = IliModelSpec.DomainKind.NUMERIC;
    height.min = "0";
    height.max = "10000";
    height.unitFqn = "AuthorDemo.Centimeter";
    spec.domains = List.of(height);

    IliModelSpec.ClassSpec target = new IliModelSpec.ClassSpec();
    target.name = "Target";
    target.attributes = List.of(textAttribute("code", false));

    GeometryTypeSpec geometry = new GeometryTypeSpec();
    geometry.setProvider(GeometryTypeSpec.Provider.CHBASE);
    geometry.setChBaseType("Coord2");
    TypeSpec geometryType = new TypeSpec();
    geometryType.setGeometryType(geometry);
    AttributeLineRequest position = new AttributeLineRequest();
    position.setName("position");
    position.setTypeSpec(geometryType);

    IliModelSpec.ClassSpec item = new IliModelSpec.ClassSpec();
    item.name = "Item";
    item.attributes = List.of(
        textAttribute("code", false), textAttribute("other", false),
        namedAttribute("height", "AuthorDemo.Height"), position);
    item.constraints = List.of(
        unique(), mandatory(), existence(), plausibility(), set());

    IliModelSpec.TopicSpec topic = new IliModelSpec.TopicSpec();
    topic.name = "Data";
    IliModelSpec.StructureSpec note = new IliModelSpec.StructureSpec();
    note.name = "Note";
    note.attributes = List.of(textAttribute("text", false));
    topic.structures = List.of(note);
    topic.classes = List.of(target, item);
    IliModelSpec.AssociationSpec association = new IliModelSpec.AssociationSpec();
    association.name = "ItemTarget";
    association.roles = List.of(
        role("item", "AuthorDemo.Data.Item"),
        role("target", "AuthorDemo.Data.Target"));
    topic.associations = List.of(association);
    spec.topics = List.of(topic);
    return spec;
  }

  private AttributeLineRequest textAttribute(String name, boolean mandatory) {
    BaseType text = new BaseType();
    text.setKind(BaseType.Kind.TEXT);
    text.setLength(20);
    TypeSpec type = new TypeSpec();
    type.setBaseType(text);
    AttributeLineRequest attribute = new AttributeLineRequest();
    attribute.setName(name);
    attribute.setMandatory(mandatory);
    attribute.setTypeSpec(type);
    return attribute;
  }

  private AttributeLineRequest namedAttribute(String name, String domainFqn) {
    TypeSpec type = new TypeSpec();
    type.setDomainFqn(domainFqn);
    AttributeLineRequest attribute = new AttributeLineRequest();
    attribute.setName(name);
    attribute.setTypeSpec(type);
    return attribute;
  }

  private IliModelSpec.AssociationRoleSpec role(String name, String classFqn) {
    IliModelSpec.CardinalitySpec cardinality = new IliModelSpec.CardinalitySpec();
    cardinality.min = 0;
    cardinality.max = "*";
    IliModelSpec.AssociationRoleSpec role = new IliModelSpec.AssociationRoleSpec();
    role.name = name;
    role.classFqn = classFqn;
    role.cardinality = cardinality;
    return role;
  }

  private IliConstraintSpec.Unique unique() {
    IliConstraintSpec.Unique constraint = new IliConstraintSpec.Unique();
    constraint.name = "UniqueCode";
    constraint.scope = IliConstraintSpec.UniqueScope.GLOBAL;
    constraint.keyPaths = List.of("code");
    return constraint;
  }

  private IliConstraintSpec.Mandatory mandatory() {
    IliConstraintSpec.Mandatory constraint = new IliConstraintSpec.Mandatory();
    constraint.name = "CodeDefined";
    constraint.condition = defined("code");
    return constraint;
  }

  private IliConstraintSpec.Existence existence() {
    IliConstraintSpec.Existence constraint = new IliConstraintSpec.Existence();
    constraint.name = "CodeExists";
    constraint.restrictedPath = "code";
    IliConstraintSpec.ExistenceTargetSpec target = new IliConstraintSpec.ExistenceTargetSpec();
    target.viewableFqn = "AuthorDemo.Data.Target";
    target.attributePath = "code";
    constraint.requiredIn = List.of(target);
    return constraint;
  }

  private IliConstraintSpec.Plausibility plausibility() {
    IliConstraintSpec.Plausibility constraint = new IliConstraintSpec.Plausibility();
    constraint.name = "UsuallyDefined";
    constraint.direction = IliConstraintSpec.PlausibilityDirection.AT_LEAST;
    constraint.percentage = BigDecimal.ZERO;
    constraint.condition = defined("code");
    return constraint;
  }

  private IliConstraintSpec.Set set() {
    IliConstraintSpec.Set constraint = new IliConstraintSpec.Set();
    constraint.name = "AtLeastTwo";
    constraint.scope = IliConstraintSpec.SetScope.GLOBAL;
    IliConstraintSpec.ObjectCountSetConditionSpec condition =
        new IliConstraintSpec.ObjectCountSetConditionSpec();
    condition.objects = new IliConstraintSpec.AllObjectsSpec();
    condition.operator = ">=";
    condition.threshold = BigDecimal.valueOf(2);
    constraint.condition = condition;
    return constraint;
  }

  private IliConstraintSpec.ExpressionSpec defined(String attribute) {
    IliConstraintSpec.ExpressionSpec value = expression(IliConstraintSpec.ExpressionKind.ATTRIBUTE);
    value.name = attribute;
    IliConstraintSpec.ExpressionSpec defined = expression(IliConstraintSpec.ExpressionKind.DEFINED);
    defined.children = List.of(value);
    return defined;
  }

  private IliConstraintSpec.ExpressionSpec expression(IliConstraintSpec.ExpressionKind kind) {
    IliConstraintSpec.ExpressionSpec expression = new IliConstraintSpec.ExpressionSpec();
    expression.kind = kind;
    return expression;
  }

  private static final class CountingCompiler extends IliCompilerService {
    private int calls;

    @Override
    public CompilationResult compile(String text, String repositories, String prefix) {
      calls++;
      return super.compile(text, repositories, prefix);
    }
  }
}
