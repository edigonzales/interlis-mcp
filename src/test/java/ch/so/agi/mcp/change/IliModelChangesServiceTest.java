package ch.so.agi.mcp.change;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.knowledge.KnowledgeRuleLoader;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.model.IliConstraintSpec;
import ch.so.agi.mcp.model.IliModelSpec;
import ch.so.agi.mcp.model.IliSpecRenderer;
import ch.so.agi.mcp.model.BaseType;
import ch.so.agi.mcp.model.GeometryTypeSpec;
import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.model.TypeSpec;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.AttributeTools;
import ch.so.agi.mcp.tools.DomainTools;
import ch.so.agi.mcp.tools.ConstraintCaseGenerationTools;
import ch.so.agi.mcp.tools.ConstraintTestTools;
import java.util.List;
import java.math.BigDecimal;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class IliModelChangesServiceTest {

  @Test
  void updateAttributeIsHeldUntilBreakingChangeIsConfirmed() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangesService service = service(compiler);
    IliModelChangesRequest request = updateMandatory(false);

    IliAuthoringResult held = service.apply(model(), request, null, null, null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(held.status).isEqualTo(IliAuthoringResult.Status.BREAKING_CHANGE_REQUIRES_CONFIRMATION);
    assertThat(held.updatedModelText).isNull();
    assertThat(held.candidateModelText).contains("name : MANDATORY TEXT*20;");
    assertThat(held.requiresUserDecision).isTrue();

    request.allowPotentiallyBreaking = true;
    IliAuthoringResult released = service.apply(model(), request, null, null, null);
    assertThat(compiler.calls).isEqualTo(4);
    assertThat(released.status).isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(released.updatedModelText).contains("name : MANDATORY TEXT*20;");
  }

  @Test
  void removeAttributeRemovesOwnedAnnotationsAndPreservesCrLf() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangesService service = service(compiler);
    IliModelChangeRequest change = new IliModelChangeRequest();
    change.setOperation(IliModelChangeOperation.REMOVE_ATTRIBUTE);
    RemoveAttributeChange remove = new RemoveAttributeChange();
    remove.attributeFqn = "Demo.Data.Thing.name";
    change.setRemoveAttribute(remove);
    IliModelChangesRequest request = new IliModelChangesRequest();
    request.changes = List.of(change);
    request.allowPotentiallyBreaking = true;

    IliAuthoringResult result = service.apply(
        model().replace("\n", "\r\n"), request, null, null, null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(result.updatedModelText)
        .doesNotContain("attribute documentation")
        .doesNotContain("attrMeta")
        .doesNotContain("name :")
        .contains("other : TEXT*10;");
    assertThat(result.updatedModelText.replace("\r\n", "")).doesNotContain("\n");
  }

  @Test
  void updateAttributeChangesOnlyExplicitTypeCollectionAndAnnotations() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangesService service = service(compiler);
    UpdateAttributeChange.AttributePatch patch = new UpdateAttributeChange.AttributePatch();
    patch.typeSpec = textType(40);
    patch.collection = ch.so.agi.mcp.model.AttributeLineRequest.Collection.LIST_OF;
    patch.iliDocAction = UpdateAttributeChange.IliDocAction.SET;
    patch.iliDoc = "replacement documentation";
    patch.metaAttributesAction = UpdateAttributeChange.MetaAttributesAction.REPLACE;
    MetaAttributeSpec meta = new MetaAttributeSpec();
    meta.setName("newMeta");
    meta.setValue("new-value");
    patch.metaAttributes = List.of(meta);
    UpdateAttributeChange update = new UpdateAttributeChange();
    update.attributeFqn = "Demo.Data.Thing.name";
    update.patch = patch;

    IliAuthoringResult result = service.apply(
        model(), batch(change(IliModelChangeOperation.UPDATE_ATTRIBUTE, update, null, null), true),
        null, null, null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(result.updatedModelText)
        .contains("/** replacement documentation */")
        .contains("!!@ newMeta=\"new-value\"")
        .contains("name : LIST OF TEXT*40;")
        .doesNotContain("attribute documentation")
        .doesNotContain("attrMeta")
        .contains("other : TEXT*10;");
  }

  @Test
  void referencedAttributeRemovalReturnsOnlyInvalidCandidate() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangesService service = service(compiler);
    RemoveAttributeChange remove = new RemoveAttributeChange();
    remove.attributeFqn = "Demo.Data.Thing.name";

    IliAuthoringResult result = service.apply(
        model().replace("      other : TEXT*10;", "      other : TEXT*10;\n"
            + "      MANDATORY CONSTRAINT DEFINED(name);"),
        batch(change(IliModelChangeOperation.REMOVE_ATTRIBUTE, null, remove, null), true),
        null, null, null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.CANDIDATE_MODEL_INVALID);
    assertThat(result.updatedModelText).isNull();
    assertThat(result.candidateModelText)
        .doesNotContain("name :")
        .contains("DEFINED(name)");
  }

  @Test
  void externalFunctionConstraintWithholdsWholeCompiledBatch() {
    CountingCompiler compiler = new CountingCompiler();
    IliConstraintSpec.ExpressionSpec attribute = new IliConstraintSpec.ExpressionSpec();
    attribute.kind = IliConstraintSpec.ExpressionKind.ATTRIBUTE;
    attribute.name = "name";
    IliConstraintSpec.ExpressionSpec function = new IliConstraintSpec.ExpressionSpec();
    function.kind = IliConstraintSpec.ExpressionKind.FUNCTION;
    function.name = "Demo.custom";
    function.functionOrigin = IliConstraintSpec.FunctionOrigin.MODEL;
    function.children = List.of(attribute);
    IliConstraintSpec.Mandatory constraint = new IliConstraintSpec.Mandatory();
    constraint.name = "CustomRule";
    constraint.condition = function;
    IliModelChangeRequest.AddConstraintChange add =
        new IliModelChangeRequest.AddConstraintChange();
    add.containerFqn = "Demo.Data.Thing";
    add.constraint = constraint;
    IliModelChangeRequest change = new IliModelChangeRequest();
    change.setOperation(IliModelChangeOperation.ADD_CONSTRAINT);
    change.setAddConstraint(add);

    String source = model().replace(
        "  TOPIC Data =", "  FUNCTION custom(value: TEXT): BOOLEAN;\n  TOPIC Data =");
    IliAuthoringResult result = service(compiler).apply(
        source, batch(List.of(change), true), null, null, null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.EXTERNAL_FUNCTION_SEMANTICS_REQUIRED);
    assertThat(result.updatedModelText).isNull();
    assertThat(result.candidateModelText).contains("Demo.custom(name)");
    assertThat(result.proofVerified).isFalse();
  }

  @Test
  void sameAnchorInsertionsAreGroupedInBatchOrder() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangesService service = service(compiler);
    IliModelChangeRequest first = addAttribute("first", 10);
    IliModelChangeRequest second = addAttribute("second", 12);

    IliAuthoringResult result = service.apply(
        model(), batch(List.of(first, second), false), null, null, null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(result.sourceEdits).hasSize(1);
    assertThat(result.updatedModelText.indexOf("first : TEXT*10;"))
        .isLessThan(result.updatedModelText.indexOf("second : TEXT*12;"));
  }

  @Test
  void geometryChangeAddsOnlyItsDerivedImport() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangesService service = service(compiler);
    GeometryTypeSpec geometry = new GeometryTypeSpec();
    geometry.setProvider(GeometryTypeSpec.Provider.CHBASE);
    geometry.setChBaseType("Coord2");
    TypeSpec type = new TypeSpec();
    type.setGeometryType(geometry);
    ch.so.agi.mcp.model.AttributeLineRequest attribute =
        new ch.so.agi.mcp.model.AttributeLineRequest();
    attribute.setName("position");
    attribute.setTypeSpec(type);
    AddAttributeChange add = new AddAttributeChange();
    add.setContainerFqn("Demo.Data.Thing");
    add.setAttribute(attribute);
    IliModelChangeRequest operation = new IliModelChangeRequest();
    operation.setOperation(IliModelChangeOperation.ADD_ATTRIBUTE);
    operation.setAddAttribute(add);

    IliAuthoringResult result = service.apply(
        model(), batch(List.of(operation), false), null, null, null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(result.derivedImports).containsExactly("GeometryCHLV95_V2");
    assertThat(result.updatedModelText)
        .contains("IMPORTS GeometryCHLV95_V2;")
        .contains("position : GeometryCHLV95_V2.Coord2;");
  }

  @Test
  void mixedBatchIsHeldAndReleasedAsOneUnit() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangesService service = service(compiler);
    IliModelChangesRequest request = batch(
        List.of(addAttribute("optionalCode", 10), updateMandatory(false).changes.getFirst()),
        false);

    IliAuthoringResult held = service.apply(model(), request, null, null, null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(held.status).isEqualTo(IliAuthoringResult.Status.BREAKING_CHANGE_REQUIRES_CONFIRMATION);
    assertThat(held.updatedModelText).isNull();
    assertThat(held.candidateModelText)
        .contains("optionalCode : TEXT*10;")
        .contains("name : MANDATORY TEXT*20;");

    request.allowPotentiallyBreaking = true;
    IliAuthoringResult released = service.apply(model(), request, null, null, null);

    assertThat(compiler.calls).isEqualTo(4);
    assertThat(released.status).isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(released.updatedModelText)
        .contains("optionalCode : TEXT*10;")
        .contains("name : MANDATORY TEXT*20;");
  }

  @Test
  void addAttributeExpandsOneLineEmptyClassAtItsOwnEndMarker() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangesService service = service(compiler);
    String before = """
        INTERLIS 2.4;
        MODEL Compact (de) AT "https://example.org/compact" VERSION "2026-08-24" =
          TOPIC Data =
            CLASS First = END First;
            CLASS Second = END Second;
          END Data;
        END Compact.
        """;
    IliModelChangeRequest add = addAttribute("value", 20);
    add.getAddAttribute().setContainerFqn("Compact.Data.Second");

    IliAuthoringResult result = service.apply(
        before, batch(List.of(add), false), null, null, null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(result.updatedModelText)
        .contains("CLASS First = END First;")
        .contains("CLASS Second = \n      value : TEXT*20;\n    END Second;");
  }

  @Test
  void addConstraintReusesAfterCompileForItsProofAndReview() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangesService service = serviceWithProof(compiler);
    IliConstraintSpec.ExpressionSpec attribute = new IliConstraintSpec.ExpressionSpec();
    attribute.kind = IliConstraintSpec.ExpressionKind.ATTRIBUTE;
    attribute.name = "other";
    IliConstraintSpec.ExpressionSpec defined = new IliConstraintSpec.ExpressionSpec();
    defined.kind = IliConstraintSpec.ExpressionKind.DEFINED;
    defined.children = List.of(attribute);
    IliConstraintSpec.Mandatory constraint = new IliConstraintSpec.Mandatory();
    constraint.name = "OtherDefined";
    constraint.condition = defined;
    IliModelChangeRequest.AddConstraintChange add =
        new IliModelChangeRequest.AddConstraintChange();
    add.containerFqn = "Demo.Data.Thing";
    add.constraint = constraint;
    IliModelChangeRequest operation = new IliModelChangeRequest();
    operation.setOperation(IliModelChangeOperation.ADD_CONSTRAINT);
    operation.setAddConstraint(add);

    IliAuthoringResult result = service.apply(
        model(), batch(List.of(operation), true), null, null, null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.status).as("%s", result.reason).isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(result.proofVerified).isTrue();
    assertThat(result.constraintProofs).hasSize(1);
    assertThat(result.afterReview).isNotNull();
    assertThat(result.updatedModelText)
        .contains("CONSTRAINTS OF Demo.Data.Thing =")
        .contains("MANDATORY CONSTRAINT DEFINED(other);");
  }

  @Test
  void everyAddOperationProducesACompilableGuardedCandidate() {
    assertAddOperation(operation(IliModelChangeOperation.ADD_IMPORT, change -> {
      IliModelChangeRequest.AddImportChange add = new IliModelChangeRequest.AddImportChange();
      add.modelFqn = "Demo";
      add.importModel = "Units";
      change.setAddImport(add);
    }), "IMPORTS Units;");

    assertAddOperation(operation(IliModelChangeOperation.ADD_TOPIC, change -> {
      IliModelSpec.TopicSpec topic = new IliModelSpec.TopicSpec();
      topic.name = "MoreData";
      IliModelChangeRequest.AddTopicChange add = new IliModelChangeRequest.AddTopicChange();
      add.modelFqn = "Demo";
      add.topic = topic;
      change.setAddTopic(add);
    }), "TOPIC MoreData");

    assertAddOperation(operation(IliModelChangeOperation.ADD_DOMAIN, change -> {
      IliModelSpec.DomainSpec domain = new IliModelSpec.DomainSpec();
      domain.name = "Height";
      domain.kind = IliModelSpec.DomainKind.NUMERIC;
      domain.min = "0";
      domain.max = "100";
      IliModelChangeRequest.AddDomainChange add = new IliModelChangeRequest.AddDomainChange();
      add.containerFqn = "Demo.Data";
      add.domain = domain;
      change.setAddDomain(add);
    }), "Height = 0 .. 100;");

    assertAddOperation(operation(IliModelChangeOperation.ADD_UNIT, change -> {
      IliModelSpec.UnitSpec unit = new IliModelSpec.UnitSpec();
      unit.name = "Centimeter";
      unit.factor = new BigDecimal("0.01");
      unit.baseUnitFqn = "INTERLIS.m";
      IliModelChangeRequest.AddUnitChange add = new IliModelChangeRequest.AddUnitChange();
      add.containerFqn = "Demo";
      add.unit = unit;
      change.setAddUnit(add);
    }), "Centimeter = 0.01 [INTERLIS.m];");

    assertAddOperation(operation(IliModelChangeOperation.ADD_CLASS, change -> {
      IliModelSpec.ClassSpec clazz = new IliModelSpec.ClassSpec();
      clazz.name = "AddedClass";
      IliModelChangeRequest.AddClassChange add = new IliModelChangeRequest.AddClassChange();
      add.topicFqn = "Demo.Data";
      add.clazz = clazz;
      change.setAddClass(add);
    }), "CLASS AddedClass");

    assertAddOperation(operation(IliModelChangeOperation.ADD_STRUCTURE, change -> {
      IliModelSpec.StructureSpec structure = new IliModelSpec.StructureSpec();
      structure.name = "AddedStructure";
      IliModelChangeRequest.AddStructureChange add =
          new IliModelChangeRequest.AddStructureChange();
      add.topicFqn = "Demo.Data";
      add.structure = structure;
      change.setAddStructure(add);
    }), "STRUCTURE AddedStructure");

    assertAddOperation(operation(IliModelChangeOperation.ADD_ASSOCIATION, change -> {
      IliModelSpec.AssociationSpec association = new IliModelSpec.AssociationSpec();
      association.name = "ThingRelation";
      association.roles = List.of(
          role("left", "Demo.Data.Thing"), role("right", "Demo.Data.Thing"));
      IliModelChangeRequest.AddAssociationChange add =
          new IliModelChangeRequest.AddAssociationChange();
      add.topicFqn = "Demo.Data";
      add.association = association;
      change.setAddAssociation(add);
    }), "ASSOCIATION ThingRelation");
  }

  private void assertAddOperation(IliModelChangeRequest operation, String expectedText) {
    CountingCompiler compiler = new CountingCompiler();
    IliAuthoringResult result = service(compiler).apply(
        model(), batch(List.of(operation), true), null, null, null);
    assertThat(compiler.calls).as(operation.getOperation().name()).isEqualTo(2);
    assertThat(result.status).as("%s: %s", operation.getOperation(), result.reason)
        .isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(result.updatedModelText).contains(expectedText);
  }

  private IliModelChangeRequest operation(
      IliModelChangeOperation operation, Consumer<IliModelChangeRequest> payload) {
    IliModelChangeRequest change = new IliModelChangeRequest();
    change.setOperation(operation);
    payload.accept(change);
    return change;
  }

  private IliModelSpec.AssociationRoleSpec role(String name, String target) {
    IliModelSpec.CardinalitySpec cardinality = new IliModelSpec.CardinalitySpec();
    cardinality.min = 0;
    cardinality.max = "*";
    IliModelSpec.AssociationRoleSpec role = new IliModelSpec.AssociationRoleSpec();
    role.name = name;
    role.classFqn = target;
    role.cardinality = cardinality;
    return role;
  }

  private IliModelChangesRequest updateMandatory(boolean allow) {
    UpdateAttributeChange.AttributePatch patch = new UpdateAttributeChange.AttributePatch();
    patch.mandatory = true;
    UpdateAttributeChange update = new UpdateAttributeChange();
    update.attributeFqn = "Demo.Data.Thing.name";
    update.patch = patch;
    IliModelChangeRequest change = new IliModelChangeRequest();
    change.setOperation(IliModelChangeOperation.UPDATE_ATTRIBUTE);
    change.setUpdateAttribute(update);
    IliModelChangesRequest request = new IliModelChangesRequest();
    request.changes = List.of(change);
    request.allowPotentiallyBreaking = allow;
    return request;
  }

  private IliModelChangeRequest addAttribute(String name, int length) {
    ch.so.agi.mcp.model.AttributeLineRequest attribute =
        new ch.so.agi.mcp.model.AttributeLineRequest();
    attribute.setName(name);
    attribute.setTypeSpec(textType(length));
    AddAttributeChange add = new AddAttributeChange();
    add.setContainerFqn("Demo.Data.Thing");
    add.setAttribute(attribute);
    IliModelChangeRequest change = new IliModelChangeRequest();
    change.setOperation(IliModelChangeOperation.ADD_ATTRIBUTE);
    change.setAddAttribute(add);
    return change;
  }

  private TypeSpec textType(int length) {
    BaseType text = new BaseType();
    text.setKind(BaseType.Kind.TEXT);
    text.setLength(length);
    TypeSpec type = new TypeSpec();
    type.setBaseType(text);
    return type;
  }

  private IliModelChangeRequest change(
      IliModelChangeOperation operation,
      UpdateAttributeChange update,
      RemoveAttributeChange remove,
      AddAttributeChange add) {
    IliModelChangeRequest change = new IliModelChangeRequest();
    change.setOperation(operation);
    change.setUpdateAttribute(update);
    change.setRemoveAttribute(remove);
    change.setAddAttribute(add);
    return change;
  }

  private IliModelChangesRequest batch(IliModelChangeRequest change, boolean allow) {
    return batch(List.of(change), allow);
  }

  private IliModelChangesRequest batch(List<IliModelChangeRequest> changes, boolean allow) {
    IliModelChangesRequest request = new IliModelChangesRequest();
    request.changes = changes;
    request.allowPotentiallyBreaking = allow;
    return request;
  }

  private IliModelChangesService service(IliCompilerService compiler) {
    ModelAnalysisTools analysis = new ModelAnalysisTools(compiler);
    ModelingRuleTools rules = new ModelingRuleTools(new KnowledgeRuleLoader(), analysis, compiler);
    ModelChangeReviewService review = new ModelChangeReviewService(analysis, rules);
    IliSpecRenderer renderer = new IliSpecRenderer(new AttributeTools(), new DomainTools());
    return new IliModelChangesService(compiler, review, renderer);
  }

  private IliModelChangesService serviceWithProof(IliCompilerService compiler) {
    ModelAnalysisTools analysis = new ModelAnalysisTools(compiler);
    ModelingRuleTools rules = new ModelingRuleTools(new KnowledgeRuleLoader(), analysis, compiler);
    ModelChangeReviewService review = new ModelChangeReviewService(analysis, rules);
    IliSpecRenderer renderer = new IliSpecRenderer(new AttributeTools(), new DomainTools());
    ConstraintContextService contexts = new ConstraintContextService(compiler);
    ConstraintCaseGenerationTools cases = new ConstraintCaseGenerationTools(
        contexts, new ConstraintTestTools(compiler));
    return new IliModelChangesService(compiler, review, renderer, contexts, cases);
  }

  private String model() {
    return """
        INTERLIS 2.4;
        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-24" =
          TOPIC Data =
            CLASS Thing =
              /** attribute documentation */
              !!@ attrMeta="value"
              name :
                TEXT*20;
              other : TEXT*10;
            END Thing;
          END Data;
        END Demo.
        """;
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
