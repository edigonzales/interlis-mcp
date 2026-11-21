package ch.so.agi.mcp.change;

import static org.assertj.core.api.Assertions.assertThat;

import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.CoordType;
import ch.interlis.ili2c.metamodel.Domain;
import ch.interlis.ili2c.metamodel.MandatoryConstraint;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.SurfaceType;
import ch.interlis.ili2c.metamodel.Unit;
import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.constraint.ConstraintFixtures;
import ch.so.agi.mcp.constraint.ConstraintSourceEditService;
import ch.so.agi.mcp.knowledge.KnowledgeRuleLoader;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.model.AttributeLineRequest;
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
import java.util.Comparator;
import java.util.List;
import java.math.BigDecimal;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
  void rejectsOverlappingBatchEditsBeforeCompilingTheCandidate() {
    var compiler = new CountingCompiler();
    var remove = new RemoveAttributeChange();
    remove.attributeFqn = "Demo.Data.Thing.name";
    var result = service(compiler).apply(model(), batch(List.of(
        operation(IliModelChangeOperation.REMOVE_COMMENTS, change -> {}),
        change(IliModelChangeOperation.REMOVE_ATTRIBUTE, null, remove, null)), true),
        null, null, null);
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.INVALID_SPEC);
    assertThat(result.reason).contains("overlap");
    assertThat(result.updatedModelText).isNull();
    assertThat(compiler.calls).isOne();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void groupsAttributeInsertionsAtTheStartOfARemovedComment(boolean compact) {
    String comment = "/** Keep the edits separate. */";
    String source = model().replace("other : TEXT*10;\n",
        "other : TEXT*10;" + (compact ? " " : "\n") + comment + "\n");
    var compiler = new CountingCompiler();
    var result = service(compiler).apply(source, batch(List.of(
        operation(IliModelChangeOperation.REMOVE_COMMENTS, change -> {}),
        addAttribute("extra", 10), addAttribute("later", 20)), true), null, null, null);
    assertThat(result.status).as(result.reason).isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(compiler.calls).isEqualTo(2);
    var td = compiler.lastCompilation.transferDescription();
    assertThat(td.getElement("Demo.Data.Thing.extra")).isNotNull();
    assertThat(td.getElement("Demo.Data.Thing.later")).isNotNull();
    int offset = source.indexOf(comment);
    assertThat(result.sourceEdits).anySatisfy(edit -> {
      assertThat(edit.startOffset).isEqualTo(offset);
      assertThat(edit.endOffset).isEqualTo(offset + comment.length());
      assertThat(edit.startLine).isEqualTo(compact ? 9 : 10);
      assertThat(edit.endLine).isEqualTo(edit.startLine);
      assertThat(edit.before).isEqualTo(comment);
      assertThat(edit.after).isEqualTo((compact ? "\n" : "")
          + "      extra : TEXT*10;\n      later : TEXT*20;\n" + " ".repeat(comment.length()));
    });
    assertReplay(source, result);
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
    assertThat(result.semanticDiff.astRoundTripVerified).isFalse();
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

  @ParameterizedTest
  @CsvSource({
      "2.3,ADD_ATTRIBUTE,false", "2.4,UPDATE_ATTRIBUTE,false",
      "2.4,ADD_CLASS,false", "2.3,ADD_TOPIC,false",
      "2.3,ADD_ATTRIBUTE,true", "2.4,ADD_ATTRIBUTE,true"
  })
  void explicitSurfaceDerivesOrReusesImportInBatch(
      String version, IliModelChangeOperation kind, boolean existingImport, @TempDir Path repository)
      throws IOException {
    Files.writeString(repository.resolve("CoordLib.ili"), """
        INTERLIS %s;
        MODEL CoordLib AT "https://example.org" VERSION "1" =
          DOMAIN Coord2 = COORD 0.000 .. 100.000 [INTERLIS.m], 0.000 .. 100.000 [INTERLIS.m];
        END CoordLib.
        """.formatted(version));
    String source = """
        INTERLIS %s;
        MODEL Demo AT "https://example.org" VERSION "1" = %sTOPIC Data =
          CLASS Thing = %sEND Thing; END Data; !! preserve
        END Demo.
        """.formatted(version, existingImport ? "IMPORTS CoordLib; " : "",
            kind == IliModelChangeOperation.UPDATE_ATTRIBUTE ? "\n    footprint : TEXT;\n  " : "");

    GeometryTypeSpec geometry = new GeometryTypeSpec();
    geometry.setProvider(GeometryTypeSpec.Provider.INTERLIS);
    geometry.setInterlisKind(GeometryTypeSpec.InterlisKind.SURFACE);
    geometry.setCoordDomainFqn("CoordLib.Coord2");
    geometry.setArcs(false);
    geometry.setOverlapMm(BigDecimal.ONE);
    TypeSpec type = new TypeSpec();
    type.setGeometryType(geometry);
    AttributeLineRequest footprint = new AttributeLineRequest();
    footprint.setName("footprint");
    footprint.setTypeSpec(type);

    String classFqn = switch (kind) {
      case ADD_CLASS -> "Demo.Data.Parcel";
      case ADD_TOPIC -> "Demo.Extra.Parcel";
      default -> "Demo.Data.Thing";
    };
    IliModelChangeRequest change = switch (kind) {
      case ADD_ATTRIBUTE -> {
        var add = addAttribute("footprint", 20);
        add.getAddAttribute().setAttribute(footprint);
        yield add;
      }
      case UPDATE_ATTRIBUTE -> operation(kind, request -> {
        var update = new UpdateAttributeChange();
        update.attributeFqn = classFqn + ".footprint";
        update.patch = new UpdateAttributeChange.AttributePatch();
        update.patch.typeSpec = type;
        request.setUpdateAttribute(update);
      });
      case ADD_CLASS -> {
        var add = addClass("Parcel");
        add.getAddClass().clazz.attributes = List.of(footprint);
        yield add;
      }
      case ADD_TOPIC -> {
        var add = addTopic("Extra");
        var parcel = new IliModelSpec.ClassSpec();
        parcel.name = "Parcel";
        parcel.attributes = List.of(footprint);
        add.getAddTopic().topic.classes = List.of(parcel);
        yield add;
      }
      default -> throw new IllegalArgumentException(kind.name());
    };
    var compiler = new CountingCompiler(repository.toString());
    var result = new IliModelChangeTools(service(compiler)).applyIliModelChanges(
        source, batch(change, true), null, null, null);

    assertThat(result.status).as("%s %s", result.reason, compiler.lastCompilation.messages())
        .isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.derivedImports).containsExactly("CoordLib");
    assertThat(result.updatedModelText).containsOnlyOnce("IMPORTS CoordLib;").contains("!! preserve");
    var attribute = (AttributeDef) compiler.lastCompilation.transferDescription()
        .getElement(classFqn + ".footprint");
    assertThat(attribute.getDomain()).isInstanceOfSatisfying(SurfaceType.class, surface ->
        assertThat(surface.getControlPointDomain().getScopedName()).isEqualTo("CoordLib.Coord2"));
    assertThat(result.sourceEdits).hasSize(existingImport ? 1 : 2);
    if (!existingImport) {
      assertInsertion(result.sourceEdits.getFirst(), source.indexOf("TOPIC Data"), 2,
          "\n  IMPORTS CoordLib;\n");
    }
    if (kind == IliModelChangeOperation.ADD_ATTRIBUTE) {
      var edit = result.sourceEdits.getLast();
      assertThat(edit.startOffset).isEqualTo(source.indexOf("END Thing;"));
      assertThat(edit.endOffset).isEqualTo(edit.startOffset);
      assertThat(edit.after).contains("footprint : SURFACE WITH (STRAIGHTS)");
    }
    assertReplay(source, result);
  }

  @Test
  void fullModelAndBatchDeriveOnlyRenderedUnitAndDomainImportsInOrder(
      @TempDir Path repository) throws IOException {
    for (String library : List.of("LengthLib", "HeightLib", "AxisLib")) {
      Files.writeString(repository.resolve(library + ".ili"), """
          INTERLIS 2.4;
          MODEL %s AT "https://example.org" VERSION "1" =
            UNIT BaseLength = 2 [INTERLIS.m];
          END %s.
          """.formatted(library, library));
    }
    var mapper = new ObjectMapper();
    var spec = mapper.readValue("""
        {"name":"Demo","uri":"https://example.org","version":"1","iliVersion":"2.4",
         "units":[{"name":"Length","factor":3,"baseUnitFqn":"LengthLib.BaseLength"}],
         "domains":[
           {"name":"Height","kind":"NUMERIC","min":"0","max":"10","unitFqn":"HeightLib.BaseLength"},
           {"name":"Position","kind":"COORD","axes":[
             {"min":0,"max":100,"unitFqn":"AxisLib.BaseLength"},
             {"min":0,"max":100,"unitFqn":"AxisLib.BaseLength"},
             {"min":0,"max":100,"unitFqn":"INTERLIS.m"}]}]}
        """, IliModelSpec.class);

    var addUnit = operation(IliModelChangeOperation.ADD_UNIT, change -> {
      var payload = new IliModelChangeRequest.AddUnitChange();
      payload.containerFqn = "Demo";
      payload.unit = spec.units.getFirst();
      change.setAddUnit(payload);
    });
    var changes = new ArrayList<>(List.of(addUnit));
    for (var domain : spec.domains) {
      changes.add(operation(IliModelChangeOperation.ADD_DOMAIN, change -> {
        var payload = new IliModelChangeRequest.AddDomainChange();
        payload.containerFqn = "Demo";
        payload.domain = domain;
        change.setAddDomain(payload);
      }));
    }

    var renderer = new IliSpecRenderer(new AttributeTools(), new DomainTools());
    var rendered = renderer.renderModel(spec);
    var compiler = new CountingCompiler(repository.toString());
    var full = compiler.compile(rendered.modelText(), null);
    var batch = service(compiler).apply("""
        INTERLIS 2.4;
        MODEL Demo AT "https://example.org" VERSION "1" = END Demo.
        """, batch(changes, true), null, null, null);

    assertThat(full.valid()).as("%s", full.messages()).isTrue();
    assertThat(batch.status).as("%s %s", batch.reason, compiler.lastCompilation.messages())
        .isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(compiler.calls).isEqualTo(3);
    assertThat(rendered.derivedImports()).containsExactly("LengthLib", "HeightLib", "AxisLib");
    assertThat(batch.derivedImports).isEqualTo(rendered.derivedImports());
    for (var compilation : List.of(full, compiler.lastCompilation)) {
      var td = compilation.transferDescription();
      var unit = (Unit) td.getElement("Demo.Length");
      var height = (NumericType) ((Domain) td.getElement("Demo.Height")).getType();
      var position = (CoordType) ((Domain) td.getElement("Demo.Position")).getType();
      assertThat(unit.getExtending().getScopedName()).isEqualTo("LengthLib.BaseLength");
      assertThat(height.getUnit().getScopedName()).isEqualTo("HeightLib.BaseLength");
      assertThat(position.getDimensions()).extracting(axis -> axis.getUnit().getScopedName())
          .containsExactly("AxisLib.BaseLength", "AxisLib.BaseLength", "INTERLIS.m");
    }
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void addsAttributeBetweenTheClassDeclarationsAndItsCompactConstraint(boolean hasAttributes) {
    String previous = hasAttributes ? "first : TEXT; second : TEXT;" : "OID AS INTERLIS.UUIDOID; ATTRIBUTE";
    String source = """
        INTERLIS 2.4;
        MODEL Demo (de) AT "https://example.org/demo" VERSION "1" =
          TOPIC Data =
            CLASS Thing = %s /** Existing rule. */ MANDATORY CONSTRAINT 1 == 1; END Thing;
          END Data;
        END Demo.
        """.formatted(previous);
    var compiler = new CountingCompiler();
    var result = service(compiler).apply(
        source, batch(addAttribute("extra", 10), false), null, null, null);
    assertThat(result.status).as("%s %s", result.reason, compiler.lastCompilation.messages())
        .isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(compiler.calls).isEqualTo(2);
    assertThat(compiler.lastCompilation.transferDescription().getElement("Demo.Data.Thing.extra")).isNotNull();
    assertInsertion(result.sourceEdits.getFirst(), source.indexOf("/** Existing rule. */"), 4,
        "\n      extra : TEXT*10;\n");
    assertReplay(source, result);
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

    var missingContext = service.apply(
        model(), batch(addConstraint("Demo.Data.Missing", constraint), true), null, null, null);
    assertThat(missingContext.status).isEqualTo(IliAuthoringResult.Status.INVALID_SPEC);
    assertThat(missingContext.reason).contains("Target not found");
    assertThat(missingContext.updatedModelText).isNull();
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
  void withholdsWholeBatchWhenAnEmbeddedConstraintCompilesWithAChangedBoundary() {
    String source = model().replace("other : TEXT*10;", "other : TEXT*10;\n      age : MANDATORY 0..100;");
    var addFirst = addConstraint("Demo.Data.Thing", ageConstraint("FirstBoundary", ">", 40));
    var clazz = new ObjectMapper().readValue("""
        {"operation":"ADD_CLASS","addClass":{"topicFqn":"Demo.Data","clazz":{"name":"Added",
          "attributes":[{"name":"age","mandatory":true,"typeSpec":{"baseType":{"kind":"NUM_RANGE","min":0,"max":100}}}]}}}
        """, IliModelChangeRequest.class);
    clazz.getAddClass().clazz.constraints = List.of(ageConstraint("SecondBoundary", ">", 18));
    var request = batch(List.of(addFirst, clazz), true);

    var compiler = new CountingCompiler();
    compiler.afterText = text -> {
      assertThat(text).contains("age > 18");
      return text.replace("age > 18", "age >= 18");
    };
    var result = service(compiler).apply(source, request, null, null, null);

    assertThat(result.status).as("%s (%s)", result.reason, result.afterCompilation)
        .isEqualTo(IliAuthoringResult.Status.AST_ROUND_TRIP_FAILED);
    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.reason).contains("Demo.Data.Added.SecondBoundary", "MANDATORY condition differs");
    assertThat(result.beforeCompilation.valid()).isTrue();
    assertThat(result.afterCompilation.valid()).isTrue();
    assertThat(result.semanticDiff.astRoundTripVerified).isFalse();
    assertThat(result.constraintProofs).isNull();
    assertThat(result.updatedModelText).isNull();
    assertThat(result.candidateModelText).contains("age > 40", "CLASS Added", "age > 18")
        .doesNotContain("age >= 18");

    // The exact compiled semantics pass both proofs when they are what was requested.
    clazz.getAddClass().clazz.constraints = List.of(ageConstraint("SecondBoundary", ">=", 18));
    var control = service(new CountingCompiler()).apply(source, request, null, null, null);
    assertThat(control.status).as("%s (%s)", control.reason, control.afterCompilation)
        .isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(control.semanticDiff.astRoundTripVerified).isTrue();
    assertThat(control.updatedModelText).isEqualTo(result.candidateModelText.replace("age > 18", "age >= 18"));
    assertThat(control.constraintProofs).hasSize(2).allSatisfy(proof -> {
      assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.PASSED);
      assertThat(proof.coverageComplete).isTrue();
    });
  }

  @Test
  void withholdsBatchWhenRequestedConstraintIsMissingFromValidAst() {
    String source = viewModel("2.4");
    var requested = addConstraint("Demo.Data.Thing", ageConstraint("AdultAge", ">", 18));
    var compiler = new CountingCompiler();
    compiler.afterText = ignored -> source;
    var result = service(compiler).apply(source, batch(requested, true), null, null, null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.afterCompilation.valid()).isTrue();
    assertThat(result.status).isEqualTo(IliAuthoringResult.Status.AST_ROUND_TRIP_FAILED);
    assertThat(result.reason).contains("Demo.Data.Thing.AdultAge", "Constraint not found");
    assertThat(result.semanticDiff.astRoundTripVerified).isFalse();
    assertThat(result.constraintProofs).isNull();
    assertThat(result.updatedModelText).isNull();
    assertThat(result.candidateModelText).contains("age > 18");
  }

  @ParameterizedTest
  @CsvSource({"2.3, false", "2.4, true"})
  void batchGroupsTwoInlineConstraintsAtAViewEndOnTheSameLine(String version, boolean crlf) {
    String eol = crlf ? "\r\n" : "\n";
    String endIndent = crlf ? "\t\t" : "    ";
    String source = viewModel(version).replace("ALL OF Base; !! Keep this comment.\n    END Selected;",
        "ALL OF Base; END Selected;");
    if (crlf) source = source.replace("  ", "\t").replace("\n", eol);
    String context = "Demo.Checks.Selected";
    var upperBound = ageConstraint("BelowMax", "<", 100);
    upperBound.iliDoc = "Keep the age below the maximum.\nThe maximum itself is excluded.";
    var compiler = new CountingCompiler();
    var result = new IliModelChangeTools(service(compiler)).applyIliModelChanges(source, batch(List.of(
        addConstraint(context, ageConstraint("Positive", ">", 0)),
        addConstraint(context, upperBound)), true), null, null);

    assertThat(result.status).as(result.reason).isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(compiler.calls).isEqualTo(2);
    assertThat(result.constraintProofs).hasSize(2).allSatisfy(proof ->
        assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.PASSED));
    int anchor = source.indexOf("END Selected;");
    String indent = endIndent + "  ";
    String insertion = String.join(eol, "",
        indent + "!!@ name=\"Positive\"",
        indent + "MANDATORY CONSTRAINT (age > 0);",
        indent + "/** Keep the age below the maximum.",
        indent + " The maximum itself is excluded.",
        indent + " */",
        indent + "!!@ name=\"BelowMax\"",
        indent + "MANDATORY CONSTRAINT (age < 100);",
        endIndent);
    assertThat(result.sourceEdits).singleElement().satisfies(edit -> {
      assertThat(edit.startOffset).isEqualTo(anchor);
      assertThat(edit.endOffset).isEqualTo(anchor);
      assertThat(edit.startLine).isEqualTo(14);
      assertThat(edit.endLine).isEqualTo(14);
      assertThat(edit.before).isEmpty();
      assertThat(edit.after).isEqualTo(insertion);
    });
    assertThat(result.updatedModelText).isEqualTo(source.substring(0, anchor)
        + insertion + source.substring(anchor));
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
  @CsvSource({
      "2.3, false, false, false", "2.3, true, false, false",
      "2.3, false, true, false", "2.3, true, true, false", "2.3, false, false, true",
      "2.4, false, false, false", "2.4, true, false, false",
      "2.4, false, true, false", "2.4, true, true, false", "2.4, false, false, true"
  })
  void batchAndSingleAuthoringInsertMathAtTheSameHeaderBoundary(
      String version, boolean contracted, boolean compact, boolean existing, @TempDir Path repository)
      throws IOException {
    String math = version.equals("2.3") ? "Math" : "Math_V2";
    Files.writeString(repository.resolve("Math_V2.ili"), """
        INTERLIS 2.4;
        CONTRACTED TYPE MODEL Math_V2 (en) AT "http://www.interlis.ch/models" VERSION "1" =
          FUNCTION abs(a: NUMERIC): NUMERIC;
        END Math_V2.
        """);
    String eol = contracted && compact ? "\r\n" : "\n";
    String indent = eol.equals("\r\n") ? "\t" : "  ";
    String header = (contracted ? "CONTRACTED " : "")
        + "MODEL Demo (de) AT \"https://example.org/demo\" VERSION \"1\" =";
    if (!compact && !contracted) header += " !! Keep with the model header.";
    String topic = compact ? " /** Topic documentation. */ TOPIC Data ="
        : eol + (existing ? indent + "IMPORTS " + math + ";" + eol : "")
            + indent + "/** Topic documentation. */" + eol
            + indent + "!!@ topicMeta=\"keep\"" + eol + indent + "TOPIC Data =";
    String source = String.join(eol, "INTERLIS " + version + ";", header + topic,
        indent.repeat(2) + "CLASS Thing = measure : MANDATORY -100 .. 100; END Thing;",
        indent + "END Data;", "END Demo.", "");
    int importAt = source.indexOf(header) + header.length() + (compact ? 1 : eol.length());
    String importText = (compact ? eol : "") + "  IMPORTS " + math + ";" + eol;
    String context = "Demo.Data.Thing";
    var spec = absoluteMinimum("Minimum");
    var compiler = new CountingCompiler(ConstraintFixtures.repositories() + ";" + repository);
    var single = ConstraintFixtures.authoring(compiler).authorIliConstraint(source, context, spec, null, null, null);
    assertThat(single.status).as("%s %s", single.reason, compiler.lastCompilation.messages())
        .isEqualTo(IliAuthoringResult.Status.GENERATED);
    assertThat(compiler.calls).isEqualTo(2);
    var batch = new IliModelChangeTools(service(compiler)).applyIliModelChanges(
        source, batch(addConstraint(context, spec), true), null, null);
    assertThat(batch.status).as("%s %s", batch.reason, compiler.lastCompilation.messages())
        .isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(compiler.calls).isEqualTo(4);
    assertThat(batch.updatedModelText).isEqualTo(single.updatedModelText);
    Model model = (Model) compiler.lastCompilation.transferDescription().getElement("Demo");
    assertThat(model.getImporting()).extracting(Model::getName).containsExactly(math);
    var data = compiler.lastCompilation.transferDescription().getElement("Demo.Data");
    assertThat(data.getDocumentation()).isEqualTo("Topic documentation.");
    if (!compact) assertThat(data.getMetaValues().getValue("topicMeta")).isEqualTo("keep");
    assertThat(compiler.lastCompilation.transferDescription().getElement(context + ".Minimum"))
        .isInstanceOfSatisfying(MandatoryConstraint.class,
            constraint -> assertThat(constraint.getContainer().getScopedName()).isEqualTo(context));
    for (var result : List.of(single, batch)) {
      assertReplay(source, result);
      assertThat(result.sourceEdits).hasSize(existing ? 1 : 2);
      if (!existing) {
        assertThat(result.sourceEdits).anySatisfy(edit ->
            assertInsertion(edit, importAt, compact ? 2 : 3, importText));
      }
      assertThat(result.constraintProofs).singleElement().satisfies(proof -> {
        assertThat(proof.status).isEqualTo(IliAuthoringResult.ProofStatus.PASSED);
        assertThat(proof.coverageComplete).isTrue();
        assertThat(proof.verification.cases).allSatisfy(test -> {
          assertThat(test.fixtureValid).isTrue();
          assertThat(test.constraintExercised).isTrue();
        }).anySatisfy(test -> assertThat(test.targetViolationCount).isZero())
            .anySatisfy(test -> assertThat(test.targetViolationCount).isEqualTo(1));
      });
    }
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
    operations.add(addClass("Extra"));
    operations.add(addConstraint("Demo.Data.Other", absoluteMinimum("Second")));
    if (importMode.equals("explicit")) {
      operations.add(addImport("Math"));
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
    assertReplay(source, result);
  }

  @Test
  void groupsTwoImportsWithoutMovingTheCompactFollowingTopic(@TempDir Path repository) throws IOException {
    for (String name : List.of("FirstDependency", "SecondDependency")) {
      Files.writeString(repository.resolve(name + ".ili"), """
          INTERLIS 2.4;
          MODEL %s (en) AT "https://example.org/import" VERSION "1" = END %s.
          """.formatted(name, name));
    }
    String source = """
        INTERLIS 2.4;
        CONTRACTED MODEL Demo (de) AT "https://example.org/demo" VERSION "1" = TOPIC Data =
          CLASS Thing = END Thing; END Data;
        END Demo.
        """.replace("\n", "\r\n");
    var compiler = new CountingCompiler(repository.toString());
    var result = service(compiler).apply(source, batch(List.of(
        addImport("SecondDependency"), addClass("Extra"), addImport("FirstDependency")), true),
        null, null, null);
    assertThat(result.status).as(result.reason).isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(compiler.calls).isEqualTo(2);
    var td = compiler.lastCompilation.transferDescription();
    assertThat(((Model) td.getElement("Demo")).getImporting()).extracting(Model::getName)
        .containsExactlyInAnyOrder("FirstDependency", "SecondDependency");
    assertThat(td.getElement("Demo.Data.Extra").getContainer()).isSameAs(td.getElement("Demo.Data"));
    assertThat(result.sourceEdits).hasSize(2);
    assertInsertion(result.sourceEdits.getFirst(), source.indexOf("TOPIC Data"), 2,
        "\r\n  IMPORTS SecondDependency;\r\n  IMPORTS FirstDependency;\r\n");
    assertReplay(source, result);
  }

  @ParameterizedTest
  @ValueSource(strings = {"2.3", "2.4"})
  void insertsIntoNestedCompactBlocksInBatchOrder(String version) {
    String source = """
        INTERLIS %s;
        CONTRACTED MODEL Demo (de) AT "https://example.org/demo" VERSION "1" =
        \tTOPIC Data = CLASS Thing = END Thing; END Data; END Demo.
        """.formatted(version);
    var compiler = new CountingCompiler();
    var result = service(compiler).apply(source, batch(List.of(
        addClass("Earlier"), addTopic("ExtraData"), addClass("Later")), true), null, null, null);
    assertThat(result.status).as(result.reason).isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(compiler.calls).isEqualTo(2);
    var td = compiler.lastCompilation.transferDescription();
    assertThat(td.getElement("Demo.Data.Earlier").getContainer()).isSameAs(td.getElement("Demo.Data"));
    assertThat(td.getElement("Demo.Data.Later").getContainer()).isSameAs(td.getElement("Demo.Data"));
    assertThat(td.getElement("Demo.ExtraData").getContainer()).isSameAs(td.getElement("Demo"));
    assertThat(result.sourceEdits).hasSize(2);
    assertInsertion(result.sourceEdits.getFirst(), source.indexOf("END Data;"), 3,
        "\n\t  CLASS Earlier =\n\t  END Earlier;\n\t  CLASS Later =\n\t  END Later;\n\t");
    assertInsertion(result.sourceEdits.getLast(), source.indexOf("END Demo."), 3,
        "\n  TOPIC ExtraData =\n  END ExtraData;\n");
    assertReplay(source, result);
  }

  @ParameterizedTest
  @CsvSource({"2.3, false", "2.4, true"})
  void declarationsDoNotInheritAnnotationsBeforeEnd(String version, boolean compact) {
    String annotationLine = compact ? " " : "\n    ";
    String source = """
        INTERLIS %s;
        MODEL Demo (de) AT "https://example.org/demo" VERSION "1" =
          TOPIC Data =
            CLASS Thing = END Thing;%s/** Trailing documentation. */
            !!@ note="keep"
          END Data;
        END Demo.
        """.formatted(version, annotationLine);
    var compiler = new CountingCompiler();
    var result = service(compiler).apply(source, batch(addClass("Extra"), true), null, null, null);
    assertThat(result.status).as(result.reason).isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(compiler.calls).isEqualTo(2);
    var extra = compiler.lastCompilation.transferDescription().getElement("Demo.Data.Extra");
    assertThat(extra.getDocumentation()).isNull();
    assertThat(extra.getMetaValue("note")).isNull();
    assertThat(result.sourceEdits).singleElement().satisfies(edit -> {
      int commentAt = source.indexOf("/** Trailing documentation. */");
      assertInsertion(edit, compact ? commentAt : commentAt - 4, compact ? 4 : 5,
          compact ? "\n    CLASS Extra =\n    END Extra;\n  "
              : "    CLASS Extra =\n    END Extra;\n\n");
    });
    assertReplay(source, result);
  }

  @ParameterizedTest
  @CsvSource({"false, ''", "false, '  '", "true, ' '"})
  void importsFollowModelSettingsAndPrecedeDeclarationsAtAnEmptyModelEnd(
      boolean settings, String gap, @TempDir Path repository) throws IOException {
    Files.writeString(repository.resolve("Dependency.ili"), """
        INTERLIS 2.4;
        MODEL Dependency (en) AT "https://example.org/import" VERSION "1" = END Dependency.
        """);
    String prefix = settings ? "CHARSET \"UTF-8\"; XMLNS \"https://example.org/ns\";" : "";
    String source = """
        INTERLIS 2.4;
        MODEL Demo (de) AT "https://example.org/demo" VERSION "1" // stable = yes //
          /* outer /* inner */ = ignored */ =%s%sEND Demo.
        """.formatted(prefix, gap);
    var compiler = new CountingCompiler(repository.toString());
    var result = service(compiler).apply(source, batch(List.of(addTopic("Data"), addImport("Dependency")), true),
        null, null, null);
    assertThat(result.status).as("%s %s", result.reason, compiler.lastCompilation.messages())
        .isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(compiler.calls).isEqualTo(2);
    var td = compiler.lastCompilation.transferDescription();
    assertThat(((Model) td.getElement("Demo")).getImporting()).extracting(Model::getName)
        .containsExactly("Dependency");
    assertThat(td.getElement("Demo.Data").getContainer()).isSameAs(td.getElement("Demo"));
    assertThat(result.sourceEdits).singleElement().satisfies(edit ->
        assertInsertion(edit, source.indexOf("END Demo."), 3,
            "\n  IMPORTS Dependency;\n  TOPIC Data =\n  END Data;\n"));
    assertReplay(source, result);
  }

  private void assertInsertion(IliAuthoringResult.SourceEdit edit, int offset, int line, String text) {
    assertThat(edit.startOffset).isEqualTo(offset);
    assertThat(edit.endOffset).isEqualTo(offset);
    assertThat(edit.startLine).isEqualTo(line);
    assertThat(edit.endLine).isEqualTo(line);
    assertThat(edit.before).isEmpty();
    assertThat(edit.after).isEqualTo(text);
  }

  private void assertReplay(String original, IliAuthoringResult result) {
    StringBuilder replay = new StringBuilder(original);
    result.sourceEdits.stream().sorted(Comparator.comparingInt(
        (IliAuthoringResult.SourceEdit edit) -> edit.startOffset).reversed()).forEach(edit -> {
          assertThat(original.substring(edit.startOffset, edit.endOffset)).isEqualTo(edit.before);
          replay.replace(edit.startOffset, edit.endOffset, edit.after);
        });
    assertThat(result.updatedModelText).isEqualTo(replay.toString());
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
    assertThat(result.semanticDiff.astRoundTripVerified).isTrue();
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

    var duplicate = service(compiler).apply(model(),
        batch(List.of(request.changes.getFirst(), request.changes.getFirst()), true), null, null, null);
    assertThat(duplicate.status).isEqualTo(IliAuthoringResult.Status.INVALID_SPEC);
    assertThat(duplicate.reason).contains("DUPLICATE_CONSTRAINT_NAME", "Demo.Data.AddedClass.ValuePresent");
    assertThat(compiler.calls).isEqualTo(3);
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
    assertAddOperation(repository, model(), addImport("Dependency"), "IMPORTS Dependency;", "Demo");

    assertAddOperation(repository, model(), addTopic("MoreData"), "TOPIC MoreData", "Demo.MoreData");

    var domainOperation = operation(IliModelChangeOperation.ADD_DOMAIN, change -> {
      IliModelSpec.DomainSpec domain = new IliModelSpec.DomainSpec();
      domain.name = "Height";
      domain.kind = IliModelSpec.DomainKind.NUMERIC;
      domain.min = "0";
      domain.max = "100";
      IliModelChangeRequest.AddDomainChange add = new IliModelChangeRequest.AddDomainChange();
      add.containerFqn = "Demo";
      add.domain = domain;
      change.setAddDomain(add);
    });
    assertAddOperation(repository, model(), domainOperation, "Height = 0 .. 100;", "Demo.Height");
    assertAddOperation(repository, model().replace("MODEL Demo", "CONTRACTED MODEL Demo"),
        domainOperation, "Height = 0 .. 100;", "Demo.Height");

    assertAddOperation(repository, model().replace("END Data;\nEND Demo.", "END Data; END Demo."),
        operation(IliModelChangeOperation.ADD_UNIT, change -> {
      IliModelSpec.UnitSpec unit = new IliModelSpec.UnitSpec();
      unit.name = "Centimeter";
      unit.factor = new BigDecimal("0.01");
      unit.baseUnitFqn = "INTERLIS.m";
      IliModelChangeRequest.AddUnitChange add = new IliModelChangeRequest.AddUnitChange();
      add.containerFqn = "Demo";
      add.unit = unit;
      change.setAddUnit(add);
    }), "Centimeter = 0.01 [INTERLIS.m];", "Demo.Centimeter");

    assertAddOperation(repository, model(), addClass("AddedClass"), "CLASS AddedClass", "Demo.Data.AddedClass");

    assertAddOperation(repository, model(), operation(IliModelChangeOperation.ADD_STRUCTURE, change -> {
      IliModelSpec.StructureSpec structure = new IliModelSpec.StructureSpec();
      structure.name = "AddedStructure";
      IliModelChangeRequest.AddStructureChange add =
          new IliModelChangeRequest.AddStructureChange();
      add.topicFqn = "Demo.Data";
      add.structure = structure;
      change.setAddStructure(add);
    }), "STRUCTURE AddedStructure", "Demo.Data.AddedStructure");

    assertAddOperation(repository, model(), operation(IliModelChangeOperation.ADD_ASSOCIATION, change -> {
      IliModelSpec.AssociationSpec association = new IliModelSpec.AssociationSpec();
      association.name = "ThingRelation";
      association.roles = List.of(
          role("left", "Demo.Data.Thing"), role("right", "Demo.Data.Thing"));
      IliModelChangeRequest.AddAssociationChange add =
          new IliModelChangeRequest.AddAssociationChange();
      add.topicFqn = "Demo.Data";
      add.association = association;
      change.setAddAssociation(add);
    }), "ASSOCIATION ThingRelation", "Demo.Data.ThingRelation");
  }

  private void assertAddOperation(Path repository, String source, IliModelChangeRequest operation,
      String expectedText, String expectedFqn) {
    CountingCompiler compiler = new CountingCompiler(repository.toString());
    IliAuthoringResult result = service(compiler).apply(
        source, batch(List.of(operation), true), null, null, null);
    assertThat(compiler.calls).as(operation.getOperation().name()).isEqualTo(2);
    assertThat(result.status).as("%s: %s (%s)", operation.getOperation(), result.reason, result.afterCompilation)
        .isEqualTo(IliAuthoringResult.Status.APPLIED);
    assertThat(result.updatedModelText).contains(expectedText);
    assertThat(compiler.lastCompilation.transferDescription().getElement(expectedFqn)).isNotNull();
    assertReplay(source, result);
  }

  private IliModelChangeRequest addTopic(String name) {
    return operation(IliModelChangeOperation.ADD_TOPIC, change -> {
      var add = new IliModelChangeRequest.AddTopicChange();
      add.modelFqn = "Demo";
      add.topic = new IliModelSpec.TopicSpec();
      add.topic.name = name;
      change.setAddTopic(add);
    });
  }

  private IliModelChangeRequest addClass(String name) {
    return operation(IliModelChangeOperation.ADD_CLASS, change -> {
      var add = new IliModelChangeRequest.AddClassChange();
      add.topicFqn = "Demo.Data";
      add.clazz = new IliModelSpec.ClassSpec();
      add.clazz.name = name;
      change.setAddClass(add);
    });
  }

  private IliModelChangeRequest addImport(String name) {
    return operation(IliModelChangeOperation.ADD_IMPORT, change -> {
      var add = new IliModelChangeRequest.AddImportChange();
      add.modelFqn = "Demo";
      add.importModel = name;
      change.setAddImport(add);
    });
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
    private UnaryOperator<String> afterText = UnaryOperator.identity();

    @Override
    public CompilationResult compile(String text, String repositories, String prefix) {
      calls++;
      lastCompilation = super.compile(
          prefix.equals("ili2c_model_changes_after_") ? afterText.apply(text) : text, repositories, prefix);
      return lastCompilation;
    }
  }
}
