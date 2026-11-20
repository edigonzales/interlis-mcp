package ch.so.agi.mcp.change;

import static org.assertj.core.api.Assertions.assertThat;

import ch.interlis.ili2c.metamodel.MandatoryConstraint;
import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.constraint.ConstraintFixtures;
import ch.so.agi.mcp.constraint.ConstraintSourceEditService;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class IliModelChangesServiceTest {

@Test
void removeCommentsPreservesModelSemanticsAndReportsEdits() {
  var compiler = new CountingCompiler();
  var change = new IliModelChangeRequest();
  change.setOperation(IliModelChangeOperation.REMOVE_COMMENTS);
  var request = new IliModelChangesRequest();
  request.changes = List.of(change);
  request.allowPotentiallyBreaking = true;
  var original = model().replace("other : TEXT*10;", "other : TEXT*10;\n!!@ name=\"NamedRule\"\nMANDATORY CONSTRAINT DEFINED(other);");
  var result = service(compiler).apply(original, request, null, null, null);
  assertThat(result.status).as("%s %s", result.reason, result.semanticDiff.changed.stream().map(c -> c.changedFields + " before=" + c.before + " after=" + c.after).toList()).isEqualTo(IliAuthoringResult.Status.APPLIED);
  assertThat(IliComments.spans(result.updatedModelText)).isEmpty();
  assertThat(result.sourceEdits).isNotEmpty();
  assertThat(result.updatedModelText).contains("name :", "other :");
}

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
    assertThat(held.openQuestions).anySatisfy(q -> assertThat(q.decisionImpact).isEqualTo("BLOCKING"));

    request.allowPotentiallyBreaking = true;
    IliAuthoringResult released = service.apply(model(), request, null, null, null);
    assertThat(compiler.calls).isEqualTo(4);
    assertThat(released.status).isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(released.updatedModelText).contains("name : MANDATORY TEXT*20;");
    assertThat(released.constraintProofs).isEmpty();
  }

  @Test
  void addingOptionalTextBeforeAssociationPassesSemanticGate() {
    var compiler = new CountingCompiler();
    String before = modelWithAssociation();
    var result = service(compiler).apply(before, batch(addAttribute("label", 20), false), null, null, null);

    assertThat(result.afterCompilation.valid()).isTrue();
    assertThat(result.status).as("%s", result.reason).isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(result.semanticDiff.added).singleElement()
        .satisfies(change -> assertThat(change.scopedName).isEqualTo("Demo.Data.Thing.label"));
    assertThat(result.semanticDiff.changed).isEmpty();
    assertThat(compiler.calls).isEqualTo(2);
  }

  @Test
  void rejectsUnrequestedRoleCardinalityChangeInCompiledCandidate() {
    var compiler = new IliCompilerService() {
      @Override
      public CompilationResult compile(String text, String repositories, String prefix) {
        // Compile a candidate with two changes to exercise the gate with a real semantic diff.
        if (text.contains("label :")) {
          text = text.replace("others -- {0..*}", "others -- {1..*}");
        }
        return super.compile(text, repositories, prefix);
      }
    };
    var result = service(compiler).apply(modelWithAssociation(),
        batch(addAttribute("label", 20), true), null, null, null);

    assertThat(result.afterCompilation.valid()).isTrue();
    assertThat(result.status).as("%s", result.reason).isEqualTo(IliAuthoringResult.Status.UNEXPECTED_SEMANTIC_CHANGE);
    assertThat(result.semanticDiff.changed).singleElement().satisfies(change -> {
      assertThat(change.scopedName).isEqualTo("Demo.Data.Link");
      assertThat(change.changedFields).containsExactly("roles");
    });
    assertThat(result.reason).contains("unexpected change", "ASSOCIATION", "Demo.Data.Link", "roles");
    assertThat(result.updatedModelText).isNull();
  }

  private String modelWithAssociation() {
    return model().replace("  END Data;", """
            CLASS Other = END Other;
            ASSOCIATION Link =
              thing -- {1} Thing;
              others -- {0..*} Other;
            END Link;
          END Data;""");
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
    assertThat(result.constraintProofs).singleElement().satisfies(proof -> {
      assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.EXTERNAL_FUNCTION_SEMANTICS_REQUIRED);
      assertThat(proof.reasonCode).isEqualTo("EXTERNAL_FUNCTION_SEMANTICS_REQUIRED");
      assertThat(proof.verification).isNull();
    });
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
    IliModelChangesService service = service(compiler);
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
    assertThat(result.constraintProofs).isNotEmpty().allSatisfy(proof -> assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.PASSED));
    assertThat(result.constraintProofs).hasSize(1);
    assertThat(result.afterReview).isNotNull();
    assertThat(result.updatedModelText)
        .contains("CONSTRAINTS OF Demo.Data.Thing =")
        .contains("MANDATORY CONSTRAINT DEFINED(other);");
    var repeated = service.apply(result.updatedModelText, batch(List.of(operation), true), null, null, null);
    assertThat(repeated.status).isEqualTo(IliAuthoringResult.Status.INVALID_SPEC);
    assertThat(repeated.reason).contains("DUPLICATE_CONSTRAINT_NAME");
    var twice = service.apply(model(), batch(List.of(operation, operation), true), null, null, null);
    assertThat(twice.status).isEqualTo(IliAuthoringResult.Status.INVALID_SPEC);
    assertThat(twice.reason).contains("DUPLICATE_CONSTRAINT_NAME");

    var wrongContext = service.apply(model(), batch(addConstraint("Demo.Data", constraint), true), null, null, null);
    assertThat(wrongContext.status).isEqualTo(IliAuthoringResult.Status.INVALID_SPEC);
    assertThat(wrongContext.reason).contains("wrong element kind");
    assertThat(wrongContext.updatedModelText).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"2.3", "2.4"})
  void batchAndSingleAuthoringInsertTheSameConstraintInAView(String version) {
    String source = viewModel(version).replace("  ", "\t").replace("\n", "\r\n");
    String context = "Demo.Checks.Selected";
    var spec = ageConstraint("Positive", ">", 0);
    CountingCompiler compiler = new CountingCompiler();
    var result = new IliModelChangeTools(service(compiler)).applyIliModelChanges(
        source, batch(addConstraint(context, spec), true), null, null);

    assertThat(result.status).as("%s %s", result.reason, result.afterCompilation)
        .isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(compiler.calls).isEqualTo(2);
    assertThat(compiler.lastCompilation.valid()).isTrue();
    assertThat(compiler.lastCompilation.transferDescription().getElement(context + ".Positive"))
        .isInstanceOfSatisfying(MandatoryConstraint.class,
            constraint -> assertThat(constraint.getContainer().getScopedName()).isEqualTo(context));
    String insertion = "\t\t  !!@ name=\"Positive\"\r\n"
        + "\t\t  MANDATORY CONSTRAINT (age > 0);\r\n";
    int anchor = source.indexOf("\t\tEND Selected;");
    assertThat(result.updatedModelText).isEqualTo(source.substring(0, anchor)
        + insertion + source.substring(anchor)).doesNotContain("CONSTRAINTS OF");
    assertThat(result.sourceEdits).singleElement().satisfies(edit -> {
      assertThat(edit.startOffset).isEqualTo(anchor);
      assertThat(edit.endOffset).isEqualTo(anchor);
      assertThat(edit.before).isEmpty();
      assertThat(edit.after).isEqualTo(insertion);
    });
    assertThat(result.constraintProofs).singleElement().satisfies(proof -> {
      assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.PASSED);
      assertThat(proof.coverageComplete).isTrue();
      assertThat(proof.verification.cases).allSatisfy(test -> {
        assertThat(test.fixtureValid).isTrue();
        assertThat(test.constraintExercised).isTrue();
      }).anySatisfy(test -> assertThat(test.targetViolationCount).isZero())
          .anySatisfy(test -> assertThat(test.targetViolationCount).isEqualTo(1));
    });

    var single = ConstraintFixtures.authoring(compiler).authorIliConstraint(source, context, spec, null, null, null);
    assertThat(single.status).as(single.reason).isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(compiler.calls).isEqualTo(4);
    assertThat(single.updatedModelText).isEqualTo(result.updatedModelText);
    assertThat(compiler.lastCompilation.transferDescription().getElement(context + ".Positive"))
        .isInstanceOfSatisfying(MandatoryConstraint.class,
            constraint -> assertThat(constraint.getContainer().getScopedName()).isEqualTo(context));
  }

  @Test
  void batchGroupsTwoInlineConstraintsAtAViewEndOnTheSameLine() {
    String source = viewModel("2.4").replace("ALL OF Base; !! Keep this comment.\n    END Selected;",
        "ALL OF Base; END Selected;");
    String context = "Demo.Checks.Selected";
    var upperBound = ageConstraint("BelowMax", "<", 100);
    upperBound.iliDoc = "Keep the age below the maximum.\nThe maximum itself is excluded.";
    var compiler = new CountingCompiler();
    var result = service(compiler).apply(source, batch(List.of(
        addConstraint(context, ageConstraint("Positive", ">", 0)),
        addConstraint(context, upperBound)), true), null, null, null);

    assertThat(result.status).as(result.reason).isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.constraintProofs).hasSize(2).allSatisfy(proof ->
        assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.PASSED));
    int anchor = source.indexOf("END Selected;");
    assertThat(result.sourceEdits).singleElement().satisfies(edit -> {
      assertThat(edit.startOffset).isEqualTo(anchor);
      assertThat(edit.endOffset).isEqualTo(anchor);
      assertThat(edit.before).isEmpty();
      assertThat(edit.after).containsSubsequence("MANDATORY CONSTRAINT (age > 0);",
          "The maximum itself is excluded.", "MANDATORY CONSTRAINT (age < 100);");
      assertThat(result.updatedModelText).isEqualTo(source.substring(0, anchor)
          + edit.after + source.substring(anchor));
    });
  }

  @Test
  void rejectsAViewFilterChangeInAdditionToTheRequestedConstraint() {
    var compiler = new IliCompilerService() {
      @Override
      public CompilationResult compile(String text, String repositories, String prefix) {
        if (text.contains("MANDATORY CONSTRAINT (age > 0);")) {
          text = text.replace("PROJECTION OF Base ~ Demo.Data.Thing;",
              "PROJECTION OF Base ~ Demo.Data.Thing;\n      WHERE Base->age < 50;");
        }
        return super.compile(text, repositories, prefix);
      }
    };
    var operation = addConstraint("Demo.Checks.Selected", ageConstraint("Positive", ">", 0));
    var result = service(compiler).apply(viewModel("2.4"), batch(operation, true), null, null, null);

    assertThat(result.afterCompilation.valid()).isTrue();
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.UNEXPECTED_SEMANTIC_CHANGE);
    assertThat(result.reason).contains("unexpected change of VIEW Demo.Checks.Selected");
    assertThat(result.updatedModelText).isNull();
  }

  @Test
  void associationConstraintCompilesInAnExternalBlockAndStillRequiresAProof() {
    String source = modelWithAssociation().replace("others -- {0..*} Other;",
        "others -- {0..*} Other;\n      age : MANDATORY 0 .. 100;");
    var compiler = new CountingCompiler();
    var operation = addConstraint("Demo.Data.Link", ageConstraint("Positive", ">", 0));
    var result = service(compiler).apply(source, batch(operation, true), null, null, null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.afterCompilation.valid()).isTrue();
    assertThat(result.candidateModelText).contains("CONSTRAINTS OF Demo.Data.Link =");
    assertThat(compiler.lastCompilation.transferDescription().getElement("Demo.Data.Link.Positive"))
        .isInstanceOfSatisfying(MandatoryConstraint.class,
            constraint -> assertThat(constraint.getContainer().getScopedName()).isEqualTo("Demo.Data.Link"));
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.PROOF_INCOMPLETE);
    assertThat(result.updatedModelText).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"derived", "explicit", "existing"})
  void mixedBatchGroupsExternalConstraintsAndAddsMathImportOnce(String importMode) {
    String source = modelWithAssociation().replace("INTERLIS 2.4", "INTERLIS 2.3")
        .replace("other : TEXT*10;", "measure : MANDATORY -100 .. 100;")
        .replace("CLASS Other = END Other;", "CLASS Other = measure : MANDATORY -100 .. 100; END Other;");
    String importLine = "  IMPORTS Math;\n";
    int modelBody = source.indexOf("  TOPIC Data =");
    if (importMode.equals("existing")) {
      source = source.substring(0, modelBody) + importLine + source.substring(modelBody);
    }
    var operations = new ArrayList<IliModelChangeRequest>();
    operations.add(addConstraint("Demo.Data.Thing", absoluteMinimum("First")));
    operations.add(operation(IliModelChangeOperation.ADD_CLASS, change -> {
      var add = new IliModelChangeRequest.AddClassChange();
      add.topicFqn = "Demo.Data";
      add.clazz = new IliModelSpec.ClassSpec();
      add.clazz.name = "Extra";
      change.setAddClass(add);
    }));
    operations.add(addConstraint("Demo.Data.Other", absoluteMinimum("Second")));
    if (importMode.equals("explicit")) {
      operations.add(operation(IliModelChangeOperation.ADD_IMPORT, change -> {
        var add = new IliModelChangeRequest.AddImportChange();
        add.modelFqn = "Demo";
        add.importModel = "Math";
        change.setAddImport(add);
      }));
    }

    var compiler = new CountingCompiler();
    var result = new IliModelChangeTools(service(compiler)).applyIliModelChanges(
        source, batch(operations, true), null, null);
    assertThat(result.status).as("%s %s", result.reason, compiler.lastCompilation.messages())
        .isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.derivedImports).containsExactly("Math");
    assertThat(result.constraintProofs).hasSize(2).allSatisfy(proof ->
        assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.PASSED));

    int topicEnd = source.indexOf("END Data;");
    String additions = """
            CONSTRAINTS OF Demo.Data.Thing =
              !!@ name="First"
              MANDATORY CONSTRAINT (Math.abs(measure) >= 10);
            END;

            CLASS Extra =
            END Extra;

            CONSTRAINTS OF Demo.Data.Other =
              !!@ name="Second"
              MANDATORY CONSTRAINT (Math.abs(measure) >= 10);
            END;

        """;
    boolean addsImport = !importMode.equals("existing");
    assertThat(result.sourceEdits).hasSize(addsImport ? 2 : 1);
    var grouped = result.sourceEdits.getLast();
    assertThat(grouped.startOffset).isEqualTo(topicEnd);
    assertThat(grouped.endOffset).isEqualTo(topicEnd);
    assertThat(grouped.before).isEmpty();
    assertThat(grouped.after).isEqualTo(additions);
    if (addsImport) {
      var imported = result.sourceEdits.getFirst();
      assertThat(imported.startOffset).isEqualTo(modelBody);
      assertThat(imported.endOffset).isEqualTo(modelBody);
      assertThat(imported.before).isEmpty();
      assertThat(imported.after).isEqualTo(importLine);
    }
    assertThat(result.updatedModelText).isEqualTo(source.substring(0, modelBody)
        + (addsImport ? importLine : "") + source.substring(modelBody, topicEnd)
        + additions + source.substring(topicEnd));
  }

  @Test
  void batchKeepsActualBeforeAndAfterDiagnostics() {
    var compiler = new CountingCompiler();
    var service = service(compiler);
    var request = batch(addAttribute("extension", 20), true);
    var invalidBefore = service.apply("INTERLIS 2.4; broken", request, null, null, null);
    assertThat(invalidBefore.status).isEqualTo(IliAuthoringResult.Status.BEFORE_MODEL_INVALID);
    assertThat(invalidBefore.beforeCompilation.valid()).isFalse();
    assertThat(invalidBefore.beforeCompilation.diagnostics()).isNotEmpty();
    assertThat(invalidBefore.afterCompilation).isNull();
    assertThat(invalidBefore.constraintProofs).isNull();
    assertThat(invalidBefore.updatedModelText).isNull();

    var clazz = new IliModelSpec.ClassSpec();
    clazz.name = "Broken";
    clazz.extendsFqn = "Demo.Data.Missing";
    var invalidAfter = service.apply(model(), batch(operation(IliModelChangeOperation.ADD_CLASS, change -> {
      var add = new IliModelChangeRequest.AddClassChange();
      add.topicFqn = "Demo.Data";
      add.clazz = clazz;
      change.setAddClass(add);
    }), true), null, null, null);
    assertThat(invalidAfter.status).isEqualTo(IliAuthoringResult.Status.CANDIDATE_MODEL_INVALID);
    assertThat(invalidAfter.beforeCompilation.valid()).isTrue();
    assertThat(invalidAfter.afterCompilation.valid()).isFalse();
    assertThat(invalidAfter.afterCompilation.diagnostics()).anySatisfy(d -> assertThat(d.message).contains("Missing"));
    assertThat(invalidAfter.constraintProofs).isNull();
    assertThat(invalidAfter.candidateModelText).contains("Demo.Data.Missing");
    assertThat(invalidAfter.updatedModelText).isNull();
    assertThat(compiler.calls).isEqualTo(3);
  }

  @Test
  void nestedConstraintsAreAllConsideredAndMissingProofBlocksRelease() throws Exception {
    var mapper = new tools.jackson.databind.ObjectMapper();
    var constraint = """
        {"kind":"MANDATORY","name":"ValuePresent","condition":{"kind":"DEFINED","children":[{"kind":"ATTRIBUTE","name":"value"}]}}
        """;
    var item = """
        {"name":"%s","attributes":[{"name":"value","typeSpec":{"baseType":{"kind":"TEXT","length":20}}}],"constraints":[%s]}
        """;
    var clazz = item.formatted("AddedClass", constraint);
    var structure = item.formatted("Orphan", constraint);
    var association = """
        {"name":"Relation","roles":[
          {"name":"left","classFqn":"Demo.Data.Thing","cardinality":{"min":0,"max":"*"}},
          {"name":"right","classFqn":"Demo.Data.Thing","cardinality":{"min":0,"max":"*"}}],
          "attributes":[{"name":"value","typeSpec":{"baseType":{"kind":"TEXT","length":20}}}],"constraints":[%s]}
        """.formatted(constraint);
    var topic = """
        {"name":"Extra","classes":[%s],"structures":[%s]}
        """.formatted(item.formatted("NestedClass", constraint), item.formatted("NestedOrphan", constraint));
    var request = mapper.readValue("""
        {"allowPotentiallyBreaking":true,"changes":[
          {"operation":"ADD_CLASS","addClass":{"topicFqn":"Demo.Data","clazz":%s}},
          {"operation":"ADD_ASSOCIATION","addAssociation":{"topicFqn":"Demo.Data","association":%s}},
          {"operation":"ADD_STRUCTURE","addStructure":{"topicFqn":"Demo.Data","structure":%s}},
          {"operation":"ADD_TOPIC","addTopic":{"modelFqn":"Demo","topic":%s}}]}
        """.formatted(clazz, association, structure, topic), IliModelChangesRequest.class);
    var compiler = new CountingCompiler();
    var result = service(compiler).apply(model(), request, null, null, null);
    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.status).as("%s", result).isIn(
        IliAuthoringResult.Status.PROOF_INCOMPLETE, IliAuthoringResult.Status.PROOF_FAILED);
    assertThat(result.afterCompilation.valid()).isTrue();
    assertThat(result.updatedModelText).isNull();
    assertThat(result.candidateModelText).contains("CLASS NestedClass", "ASSOCIATION Relation");
    assertThat(result.constraintProofs).extracting(proof -> proof.constraintFqn).containsExactlyInAnyOrder(
        "Demo.Data.AddedClass.ValuePresent", "Demo.Data.Relation.ValuePresent", "Demo.Data.Orphan.ValuePresent",
        "Demo.Extra.NestedClass.ValuePresent", "Demo.Extra.NestedOrphan.ValuePresent");
    assertThat(result.constraintProofs).filteredOn(proof -> proof.constraintFqn.contains("Orphan"))
        .allSatisfy(proof -> {
          assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.INCOMPLETE);
          assertThat(proof.reasonCode).isEqualTo("STRUCTURE_OWNER_NOT_FOUND");
        });
    assertThat(result.constraintProofs).filteredOn(proof -> proof.constraintFqn.contains("Class"))
        .allSatisfy(proof -> {
          assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.PASSED);
          assertThat(proof.verification.cases).anySatisfy(test -> {
            assertThat(test.expectedConstraintValid).isFalse();
            assertThat(test.actualConstraintValid).isFalse();
            assertThat(test.constraintExercised).isTrue();
            assertThat(test.fixtureValid).isTrue();
            assertThat(test.targetViolationCount).isPositive();
          });
        });
  }

  @Test
  void everyAddOperationProducesACompilableGuardedCandidate(@TempDir Path repository) throws IOException {
    Files.writeString(repository.resolve("Dependency.ili"), """
        INTERLIS 2.4;
        MODEL Dependency (en) AT "https://example.org/import" VERSION "1" = END Dependency.
        """);
    assertAddOperation(repository, operation(IliModelChangeOperation.ADD_IMPORT, change -> {
      IliModelChangeRequest.AddImportChange add = new IliModelChangeRequest.AddImportChange();
      add.modelFqn = "Demo";
      add.importModel = "Dependency";
      change.setAddImport(add);
    }), "IMPORTS Dependency;");

    assertAddOperation(repository, operation(IliModelChangeOperation.ADD_TOPIC, change -> {
      IliModelSpec.TopicSpec topic = new IliModelSpec.TopicSpec();
      topic.name = "MoreData";
      IliModelChangeRequest.AddTopicChange add = new IliModelChangeRequest.AddTopicChange();
      add.modelFqn = "Demo";
      add.topic = topic;
      change.setAddTopic(add);
    }), "TOPIC MoreData");

    assertAddOperation(repository, operation(IliModelChangeOperation.ADD_DOMAIN, change -> {
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

    assertAddOperation(repository, operation(IliModelChangeOperation.ADD_UNIT, change -> {
      IliModelSpec.UnitSpec unit = new IliModelSpec.UnitSpec();
      unit.name = "Centimeter";
      unit.factor = new BigDecimal("0.01");
      unit.baseUnitFqn = "INTERLIS.m";
      IliModelChangeRequest.AddUnitChange add = new IliModelChangeRequest.AddUnitChange();
      add.containerFqn = "Demo";
      add.unit = unit;
      change.setAddUnit(add);
    }), "Centimeter = 0.01 [INTERLIS.m];");

    assertAddOperation(repository, operation(IliModelChangeOperation.ADD_CLASS, change -> {
      IliModelSpec.ClassSpec clazz = new IliModelSpec.ClassSpec();
      clazz.name = "AddedClass";
      IliModelChangeRequest.AddClassChange add = new IliModelChangeRequest.AddClassChange();
      add.topicFqn = "Demo.Data";
      add.clazz = clazz;
      change.setAddClass(add);
    }), "CLASS AddedClass");

    assertAddOperation(repository, operation(IliModelChangeOperation.ADD_STRUCTURE, change -> {
      IliModelSpec.StructureSpec structure = new IliModelSpec.StructureSpec();
      structure.name = "AddedStructure";
      IliModelChangeRequest.AddStructureChange add =
          new IliModelChangeRequest.AddStructureChange();
      add.topicFqn = "Demo.Data";
      add.structure = structure;
      change.setAddStructure(add);
    }), "STRUCTURE AddedStructure");

    assertAddOperation(repository, operation(IliModelChangeOperation.ADD_ASSOCIATION, change -> {
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

  private void assertAddOperation(Path repository, IliModelChangeRequest operation, String expectedText) {
    CountingCompiler compiler = new CountingCompiler(repository.toString());
    IliAuthoringResult result = service(compiler).apply(
        model(), batch(List.of(operation), true), null, null, null);
    assertThat(compiler.calls).as(operation.getOperation().name()).isEqualTo(2);
    assertThat(result.status).as("%s: %s (%s)", operation.getOperation(), result.reason, result.afterCompilation)
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

  private IliModelChangeRequest addConstraint(String context, IliConstraintSpec spec) {
    return operation(IliModelChangeOperation.ADD_CONSTRAINT, change -> {
      var add = new IliModelChangeRequest.AddConstraintChange();
      add.containerFqn = context;
      add.constraint = spec;
      change.setAddConstraint(add);
    });
  }

  private IliConstraintSpec absoluteMinimum(String name) {
    return new ObjectMapper().readValue("""
        {"kind":"MANDATORY","name":"%s","condition":{"kind":"COMPARE","operator":">=","children":[
          {"kind":"FUNCTION","name":"NUMERIC_ABS","functionOrigin":"STANDARD",
           "children":[{"kind":"ATTRIBUTE","name":"measure"}]},
          {"kind":"NUMERIC","value":10}]}}
        """.formatted(name), IliConstraintSpec.class);
  }

  private IliConstraintSpec.Mandatory ageConstraint(String name, String operator, int limit) {
    return new ObjectMapper().readValue("""
        {"kind":"MANDATORY","name":"%s","condition":{"kind":"COMPARE","operator":"%s",
         "children":[{"kind":"ATTRIBUTE","name":"age"},{"kind":"NUMERIC","value":%d}]}}
        """.formatted(name, operator, limit), IliConstraintSpec.Mandatory.class);
  }

  private String viewModel(String version) {
    return """
        INTERLIS %s;
        MODEL Demo (en) AT "https://example.org/demo" VERSION "1" =
          TOPIC Data =
            CLASS Thing =
              age : MANDATORY 0 .. 100;
            END Thing;
          END Data;
          VIEW TOPIC Checks =
            DEPENDS ON Data;
            /** Keep this documentation. */
            !!@ review="preserve"
            VIEW Selected PROJECTION OF Base ~ Demo.Data.Thing;
            =
              ALL OF Base; !! Keep this comment.
            END Selected;
          END Checks;
        END Demo.
        """.formatted(version);
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
    ConstraintContextService contexts = new ConstraintContextService(compiler);
    ConstraintCaseGenerationTools cases = new ConstraintCaseGenerationTools(
        contexts, new ConstraintTestTools(compiler));
    return new IliModelChangesService(compiler, review, renderer, contexts,
        new ConstraintSourceEditService(), cases);
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
    CountingCompiler() {
      super(ConstraintFixtures.repositories());
    }

    CountingCompiler(String repositories) {
      super(repositories);
    }

    private int calls;
    private CompilationResult lastCompilation;

    @Override
    public CompilationResult compile(String text, String repositories, String prefix) {
      calls++;
      lastCompilation = super.compile(text, repositories, prefix);
      return lastCompilation;
    }
  }
}
