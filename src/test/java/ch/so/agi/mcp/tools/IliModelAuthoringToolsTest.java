package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.change.IliModelChangeOperation;
import ch.so.agi.mcp.change.IliModelChangeRequest;
import ch.so.agi.mcp.change.IliModelChangeTools;
import ch.so.agi.mcp.change.IliModelChangesRequest;
import ch.so.agi.mcp.change.IliModelChangesService;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.constraint.ConstraintFixtures;
import ch.so.agi.mcp.constraint.ConstraintSourceEditService;
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
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class IliModelAuthoringToolsTest {

  @Test
  void verifiesUnchangedDateModelAndRejectsDuplicateNames() {
    String text = """
        INTERLIS 2.4;
        MODEL DateProof (en) AT "https://example.org" VERSION "2026-10-15" =
          TOPIC Data =
            CLASS Item =
              day : MANDATORY INTERLIS.XMLDate;
              UNIQUE UniqueDay: day;
            END Item;
          END Data;
        END DateProof.
        """;
    var compiler = new CountingCompiler();
    var result = tools(compiler).verifyIliModel(text, ModelPurpose.VALIDATION, ModelingRuleProfile.CORE, null);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.VERIFIED);
    assertThat(result.semanticDiff).isNull();
    assertThat(result.updatedModelText).isNull();
    assertThat(result.modelHashes).isEqualTo(ch.so.agi.mcp.model.ModelHashes.model(text));
    assertThat(result.constraintProofs).hasSize(1);
    assertThat(result.constraintProofs).isNotEmpty().allSatisfy(proof -> assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.PASSED));
    assertThat(compiler.calls).isEqualTo(1);
    var duplicate = tools(compiler).verifyIliModel(text.replace("UNIQUE UniqueDay: day;", "UNIQUE UniqueDay: day;\n UNIQUE UniqueDay: day;"), ModelPurpose.VALIDATION, ModelingRuleProfile.CORE, null);
    assertThat(duplicate.updatedModelText).isNull();
  }

@Test
void soDataKeepsAllFiveConstraintNamesWithoutMetadataComments() throws Exception {
  var mapper = new tools.jackson.databind.ObjectMapper();
  var spec = mapper.readValue(mapper.writeValueAsString(completeSpec()).replace("AuthorDemo", "AuthorDemo_20260824"), IliModelSpec.class);
  var compiler = new CountingCompiler();
  var result = tools(compiler).authorIliModel(spec, ModelPurpose.VALIDATION, ModelingRuleProfile.SO_DATA);
  assertThat(result.constraintProofs).isNotEmpty().allSatisfy(proof -> assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.PASSED));
  assertThat(result.constraintProofs).hasSize(5);
  assertThat(result.semanticDiff.astRoundTripVerified).isTrue();
  String text = result.updatedModelText != null ? result.updatedModelText : result.candidateModelText;
  assertThat(ch.so.agi.mcp.change.IliComments.spans(text)).isEmpty();
  assertThat(text).contains("MANDATORY CONSTRAINT ", "UNIQUE ", "EXISTENCE CONSTRAINT ");
}

@Test

  void authorsChBaseModelWithAllFiveConstraintProofsUsingOneCompile() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelAuthoringTools tools = tools(compiler);

    IliAuthoringResult result = tools.authorIliModel(
        completeSpec(), ModelPurpose.CAPTURE, ModelingRuleProfile.CORE);

    assertThat(compiler.calls).isEqualTo(1);
    assertThat(result.status).as("%s (%s)", result, result.afterCompilation).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(result.semanticDiff.astGenerated).isTrue();
    assertThat(result.semanticDiff.astRoundTripVerified).isTrue();
    assertThat(result.constraintProofs).hasSize(5)
        .allSatisfy(proof -> assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.PASSED));
    assertThat(result.derivedImports).containsExactly("GeometryCHLV95_V2");
    assertThat(result.afterReview).isNotNull();
    assertThat(result.updatedModelText)
        .contains("IMPORTS GeometryCHLV95_V2;")
        .contains("UNIQUE code;")
        .contains("EXISTENCE CONSTRAINT");
    assertThat(result.candidateModelText).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"2.3", "2.4"})
  void allFiveKindsPassTheFullSingleAndBatchWorkflows(String version) {
    var spec = completeSpec();
    spec.iliVersion = version;
    var item = spec.topics.getFirst().classes.get(1);
    // The CHBase import is covered separately. These constraints only use code.
    item.attributes = item.attributes.subList(0, 2);
    var compiler = new CountingCompiler();
    var full = tools(compiler).authorIliModel(spec, ModelPurpose.VALIDATION, ModelingRuleProfile.CORE);
    assertVerifiedProofs(full, IliAuthoringResult.Status.GENERATED, 5);
    assertThat(compiler.calls).isEqualTo(1);

    var constraints = item.constraints;
    item.constraints = List.of();
    var renderer = new IliSpecRenderer(new AttributeTools(), new DomainTools());
    String before = renderer.renderModel(spec).modelText();
    String context = "AuthorDemo.Data.Item";
    for (var constraint : constraints) {
      int beforeSingle = compiler.calls;
      var single = ConstraintFixtures.authoring(compiler)
          .authorIliConstraint(before, context, constraint, ModelPurpose.VALIDATION, ModelingRuleProfile.CORE, null);
      assertVerifiedProofs(single, IliAuthoringResult.Status.GENERATED, 1);
      assertThat(compiler.calls - beforeSingle).isEqualTo(2);
    }

    var request = new IliModelChangesRequest();
    request.allowPotentiallyBreaking = true;
    request.changes = constraints.stream().map(constraint -> {
      var payload = new IliModelChangeRequest.AddConstraintChange();
      payload.containerFqn = context;
      payload.constraint = constraint;
      var change = new IliModelChangeRequest();
      change.setOperation(IliModelChangeOperation.ADD_CONSTRAINT);
      change.setAddConstraint(payload);
      return change;
    }).toList();
    var contexts = new ConstraintContextService(compiler);
    var service = new IliModelChangesService(compiler, review(compiler), renderer, contexts,
        new ConstraintSourceEditService(), new ConstraintCaseGenerationTools(contexts, new ConstraintTestTools(compiler)));
    int beforeBatch = compiler.calls;
    var batch = new IliModelChangeTools(service)
        .applyIliModelChanges(before, request, ModelPurpose.VALIDATION, ModelingRuleProfile.CORE, null);
    assertVerifiedProofs(batch, IliAuthoringResult.Status.APPLIED, 5);
    assertThat(compiler.calls - beforeBatch).isEqualTo(2);
  }

  private void assertVerifiedProofs(IliAuthoringResult result, IliAuthoringResult.Status status, int count) {
    assertThat(result.status).as("%s (%s)", result.reason, result.afterCompilation).isEqualTo(status);
    assertThat(result.semanticDiff.astRoundTripVerified).isTrue();
    assertThat(result.constraintProofs).hasSize(count).allSatisfy(proof -> {
      assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.PASSED);
      assertThat(proof.coverageComplete).isTrue();
    });
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', textBlock = """
      MANDATORY CONSTRAINT DEFINED(code); | MANDATORY CONSTRAINT DEFINED(other); | CodeDefined | MANDATORY condition differs
      UNIQUE code; | UNIQUE other; | UniqueCode | UNIQUE keys differ
      AuthorDemo.Data.Target : code; | AuthorDemo.Data.Target : other; | CodeExists | EXISTENCE targets differ
      >= 0% DEFINED(code); | >= 40% DEFINED(code); | UsuallyDefined | PLAUSIBILITY percentage differs
      objectCount(ALL) >= 2; | objectCount(ALL) >= 3; | AtLeastTwo | SET objectCount threshold differs
      """)
  void refusesCompiledModelWithDifferentConstraintSemantics(
      String original, String replacement, String name, String mismatch) {
    var compiler = new CountingCompiler();
    compiler.compileText = text -> {
      assertThat(text).contains(original);
      return text.replace(original, replacement);
    };
    var spec = completeSpec();
    spec.topics.getFirst().classes.getFirst().attributes =
        List.of(textAttribute("code", false), textAttribute("other", false));
    var result = tools(compiler).authorIliModel(spec, ModelPurpose.VALIDATION, ModelingRuleProfile.CORE);

    assertRejectedAst(result, "AuthorDemo.Data.Item." + name, mismatch);
    assertThat(result.candidateModelText).contains(original).doesNotContain(replacement);
    assertThat(compiler.calls).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void refusesMissingConstraintEvenWhenTheSameNameExistsInAnotherContext(boolean moveToTarget) {
    var compiler = new CountingCompiler();
    compiler.compileText = text -> {
      String rule = "!!@ name=\"UniqueCode\"\n      UNIQUE code;";
      assertThat(text).contains(rule);
      String withoutRule = text.replace(rule, "");
      return moveToTarget ? withoutRule.replace("END Target;", rule + "\n    END Target;") : withoutRule;
    };
    var result = tools(compiler).authorIliModel(completeSpec(), ModelPurpose.VALIDATION, ModelingRuleProfile.CORE);

    assertRejectedAst(result, "AuthorDemo.Data.Item.UniqueCode", "Constraint not found");
    assertThat(result.reasonCode).isEqualTo("CONSTRAINT_LOOKUP_FAILED");
    boolean foundInTarget = compiler.lastCompilation.transferDescription()
        .getElement("AuthorDemo.Data.Target.UniqueCode") != null;
    assertThat(foundInTarget).isEqualTo(moveToTarget);
    assertThat(compiler.calls).isEqualTo(1);
  }

  @Test
  void rejectsDuplicateRequestedConstraintFqnsBeforeCompilation() {
    var compiler = new CountingCompiler();
    var spec = completeSpec();
    spec.topics.getFirst().classes.get(1).constraints = List.of(unique(), unique());
    var result = tools(compiler).authorIliModel(spec, ModelPurpose.VALIDATION, ModelingRuleProfile.CORE);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.INVALID_SPEC);
    assertThat(result.reason).contains("DUPLICATE_CONSTRAINT_NAME", "AuthorDemo.Data.Item.UniqueCode");
    assertThat(result.updatedModelText).isNull();
    assertThat(compiler.calls).isZero();
  }

  private void assertRejectedAst(IliAuthoringResult result, String fqn, String mismatch) {
    assertThat(result.afterCompilation.valid()).as("%s", result.afterCompilation).isTrue();
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.AST_ROUND_TRIP_FAILED);
    assertThat(result.reason).contains(fqn, mismatch);
    assertThat(result.updatedModelText).isNull();
    assertThat(result.candidateModelText).isNotBlank();
    assertThat(result.semanticDiff.astGenerated).isTrue();
    assertThat(result.semanticDiff.astRoundTripVerified).isFalse();
    assertThat(result.constraintProofs).isNull();
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
    assertThat(result.semanticDiff.astRoundTripVerified).isFalse();
    assertThat(result.updatedModelText).isNull();
    assertThat(result.candidateModelText).contains("Text_V2.startsWith(code, \"A\")");
    assertThat(result.constraintProofs).anySatisfy(proof ->
        assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.EXTERNAL_FUNCTION_SEMANTICS_REQUIRED));
  }

  private IliModelAuthoringTools tools(IliCompilerService compiler) {
    IliSpecRenderer renderer = new IliSpecRenderer(new AttributeTools(), new DomainTools());
    ConstraintContextService contexts = new ConstraintContextService(compiler);
    ConstraintCaseGenerationTools cases = new ConstraintCaseGenerationTools(
        contexts, new ConstraintTestTools(compiler));
    return new IliModelAuthoringTools(renderer, compiler, contexts, cases, review(compiler));
  }

  private ModelChangeReviewService review(IliCompilerService compiler) {
    ModelAnalysisTools analysis = new ModelAnalysisTools(compiler);
    ModelingRuleTools rules = new ModelingRuleTools(
        new KnowledgeRuleLoader(), analysis, compiler);
    return new ModelChangeReviewService(analysis, rules);
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
    private UnaryOperator<String> compileText = UnaryOperator.identity();
    private CompilationResult lastCompilation;

    CountingCompiler() {
      super(ConstraintFixtures.repositories());
    }

    @Override
    public CompilationResult compile(String text, String repositories, String prefix) {
      calls++;
      lastCompilation = super.compile(compileText.apply(text), repositories, prefix);
      return lastCompilation;
    }
  }
}
