package ch.so.agi.mcp.change;

import ch.interlis.ili2c.generator.Interlis2Generator;
import ch.interlis.ili2c.metamodel.AssociationDef;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.Constraint;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.Topic;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.View;
import ch.interlis.ili2c.metamodel.Viewable;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.constraint.ConstraintAstComparison;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.constraint.ConstraintSourceEditService;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.model.AttributeLineRequest;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.model.IliAuthoringResult.SemanticDiff;
import ch.so.agi.mcp.model.IliAuthoringResult.SourceEdit;
import ch.so.agi.mcp.model.IliAuthoringResult.Status;
import ch.so.agi.mcp.model.SpecValidationException;
import ch.so.agi.mcp.model.ConstraintSpecContract;
import ch.so.agi.mcp.model.IliConstraintSpec;
import ch.so.agi.mcp.model.IliSpecRenderer;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.ConstraintCaseGenerationTools;
import ch.so.agi.mcp.util.AnnotationRenderer;
import ch.so.agi.mcp.util.NameValidator;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Atomic, source-preserving implementation of applyIliModelChanges. */
@Service
public final class IliModelChangesService {

  private static final Pattern ILI_VERSION =
      Pattern.compile("(?m)^\\s*INTERLIS\\s+(2\\.3|2\\.4)\\s*;");

  private final IliCompilerService compilerService;
  private final ModelChangeReviewService reviewService;
  private final IliSpecRenderer renderer;
  private final ConstraintContextService constraintContextService;
  private final ConstraintSourceEditService constraintSourceEditService;
  private final ConstraintCaseGenerationTools caseGenerationTools;
  private final IliSourceLocator sourceLocator = new IliSourceLocator();

  public IliModelChangesService(
      IliCompilerService compilerService,
      ModelChangeReviewService reviewService,
      IliSpecRenderer renderer,
      ConstraintContextService constraintContextService,
      ConstraintSourceEditService constraintSourceEditService,
      ConstraintCaseGenerationTools caseGenerationTools) {
    this.compilerService = compilerService;
    this.reviewService = reviewService;
    this.renderer = renderer;
    this.constraintContextService = Objects.requireNonNull(constraintContextService);
    this.constraintSourceEditService = Objects.requireNonNull(constraintSourceEditService);
    this.caseGenerationTools = Objects.requireNonNull(caseGenerationTools);
  }

  public IliAuthoringResult apply(
      String modelText,
      IliModelChangesRequest request,
      @Nullable String modelRepositories,
      @Nullable ModelPurpose modelPurpose,
      @Nullable ModelingRuleProfile ruleProfile) {
    if (modelText == null || modelText.isBlank()) {
      return new IliAuthoringResult().reject(Status.INVALID_SPEC, "modelText is required.", null);
    }
    if (request == null) {
      return new IliAuthoringResult().reject(Status.INVALID_SPEC, "request is required.", null);
    }

    IliCompilerService.CompilationResult before = compilerService.compile(
        modelText, modelRepositories, "ili2c_model_changes_before_");
    if (!before.valid() || before.transferDescription() == null) {
      IliAuthoringResult result = new IliAuthoringResult().reject(
          Status.BEFORE_MODEL_INVALID, "The supplied before model must compile.", null);
      result.beforeCompilation = IliAuthoringResult.Compilation.from(before);
      return result;
    }

    try {
      List<IliModelChangeRequest> changes = request.requireChanges();
      changes.forEach(IliModelChangeRequest::requirePayload);
      return applyValidated(
          modelText,
          changes,
          request.allowsPotentiallyBreaking(),
          before,
          modelRepositories,
          ModelPurpose.normalize(modelPurpose),
          ModelingRuleProfile.normalize(ruleProfile));
    } catch (IllegalArgumentException ex) {
      IliAuthoringResult result = SpecValidationException.attach(new IliAuthoringResult().reject(Status.INVALID_SPEC, ex.getMessage(), null), ex);
      result.beforeCompilation = IliAuthoringResult.Compilation.from(before);
      return result;
    }
  }

  private void validateConstraintPayload(IliModelChangeRequest change, String version, String path) {
    switch (change.requireOperation()) {
      case ADD_TOPIC -> ConstraintSpecContract.topicConstraints(change.getAddTopic().topic, version, path + "/addTopic/topic");
      case ADD_CLASS -> {
        var clazz = change.getAddClass().clazz;
        if (clazz != null) ConstraintSpecContract.constraints(clazz.constraints, version, path + "/addClass/clazz/constraints");
      }
      case ADD_STRUCTURE -> {
        var structure = change.getAddStructure().structure;
        if (structure != null) ConstraintSpecContract.constraints(structure.constraints, version, path + "/addStructure/structure/constraints");
      }
      case ADD_ASSOCIATION -> {
        var association = change.getAddAssociation().association;
        if (association != null) ConstraintSpecContract.constraints(association.constraints, version, path + "/addAssociation/association/constraints");
      }
      case ADD_CONSTRAINT -> ConstraintSpecContract.validate(change.getAddConstraint().constraint, version, path + "/addConstraint/constraint");
      default -> { }
    }
  }

  private IliAuthoringResult applyValidated(
      String modelText,
      List<IliModelChangeRequest> changes,
      boolean allowPotentiallyBreaking,
      IliCompilerService.CompilationResult before,
      @Nullable String modelRepositories,
      ModelPurpose modelPurpose,
      ModelingRuleProfile ruleProfile) {
    TransferDescription td = Objects.requireNonNull(before.transferDescription());
    Model localModel = singleLocalModel(td);
    String modelName = localModel.getName();
    String iliVersion = iliVersion(modelText);
    IliSourceDocument document = IliSourceDocument.of(modelText);

    List<IliTextPatch> replacements = new ArrayList<>();
    List<PendingInsertion> insertions = new ArrayList<>();
    List<ExpectedChange> expected = new ArrayList<>();
    LinkedHashSet<String> derivedImports = new LinkedHashSet<>();
    Map<String, IliConstraintSpec> requestedConstraints = new LinkedHashMap<>();

    for (int index = 0; index < changes.size(); index++) {
      validateConstraintPayload(changes.get(index), iliVersion, "/request/changes/" + index);
      prepareOperation(
          changes.get(index), index, td, localModel, document, iliVersion, modelName,
          replacements, insertions, expected, derivedImports, requestedConstraints, renderer.forProfile(ruleProfile));
    }
    addMissingDerivedImports(
        document, localModel, changes.size(), insertions, expected, derivedImports);

    Prepared prepared = prepare(document, groupInsertions(document, insertions, replacements));
    if (prepared.candidate().equals(modelText)) {
      IliAuthoringResult result = new IliAuthoringResult().reject(
          Status.INVALID_SPEC, "The requested batch changes no source text.", null);
      result.beforeCompilation = IliAuthoringResult.Compilation.from(before);
      result.sourceEdits = prepared.sourceEdits();
      return result;
    }

    IliCompilerService.CompilationResult after = compilerService.compile(
        prepared.candidate(), modelRepositories, "ili2c_model_changes_after_");
    Map<String, Object> review = reviewService.reviewCompiledChange(
        before, after, modelText, prepared.candidate(), modelPurpose, ruleProfile);
    IliAuthoringResult result = IliAuthoringResult.fromChangeReview(review, before, after);
    result.sourceEdits = prepared.sourceEdits();
    result.derivedImports = List.copyOf(derivedImports);

    if (!result.afterCompilation.valid()) {
      return result.reject(Status.CANDIDATE_MODEL_INVALID,
          "The complete batch candidate does not compile; no change was released.", prepared.candidate());
    }

    TransferDescription afterTd = Objects.requireNonNull(after.transferDescription());
    if (!ConstraintAstComparison.verify(requestedConstraints,
        fqn -> constraintContextService.resolveCompiled(prepared.candidate(), fqn, modelRepositories, after),
        result, prepared.candidate())) return result;

    String unexpected = unexpectedSemanticChange(Objects.requireNonNull(result.semanticDiff), expected, afterTd);
    if (unexpected != null) {
      return result.reject(Status.UNEXPECTED_SEMANTIC_CHANGE, unexpected, prepared.candidate());
    }

    Map<String, Integer> existing = new LinkedHashMap<>();
    for (String fqn : ConstraintContextService.declaredConstraintNames(td)) {
      existing.merge(fqn, 1, Integer::sum);
    }
    var required = new LinkedHashSet<String>();
    for (String fqn : ConstraintContextService.declaredConstraintNames(afterTd)) {
      if (existing.merge(fqn, -1, Integer::sum) < 0) required.add(fqn);
    }
    required.addAll(requestedConstraints.keySet());
    var proofs = required.stream().map(fqn -> {
      IliConstraintSpec requested = requestedConstraints.get(fqn);
      return caseGenerationTools.prove(fqn, constraintContextService.resolveCompiled(
          prepared.candidate(), fqn, modelRepositories, after),
          requested != null && requested.hasExternalFunctionSemantics());
    }).toList();
    if (result.rejectFailedProof(proofs, prepared.candidate())) return result;

    if (!result.semanticDiff.potentiallyBreakingChanges.isEmpty() && !allowPotentiallyBreaking) {
      result.addQuestion("Confirm all potentially breaking changes by setting allowPotentiallyBreaking=true.");
      return result.reject(Status.BREAKING_CHANGE_REQUIRES_CONFIRMATION,
          "The semantic review found potentially breaking changes.", prepared.candidate());
    }

    return result.release(Status.APPLIED, prepared.candidate());
  }

  private void addMissingDerivedImports(
      IliSourceDocument document,
      Model localModel,
      int operationIndex,
      List<PendingInsertion> insertions,
      List<ExpectedChange> expected,
      Set<String> derivedImports) {
    List<String> missing = derivedImports.stream().filter(imported -> !hasImport(localModel, imported)
        && expected.stream().noneMatch(item -> item.direction() == Direction.ADD
            && "IMPORT".equals(item.kind()) && imported.equals(item.scopedName()))).toList();
    if (!missing.isEmpty()) {
      var patch = sourceLocator.insertImports(document, locateBlock(document, localModel), missing,
          "Add derived import " + String.join(", ", missing));
      insertions.add(new PendingInsertion(patch, operationIndex, true));
      for (String imported : missing) {
        expected.add(new ExpectedChange(Direction.ADD, "IMPORT", imported, false));
      }
    }
  }

  private void prepareOperation(
      IliModelChangeRequest change,
      int operationIndex,
      TransferDescription td,
      Model localModel,
      IliSourceDocument document,
      String iliVersion,
      String modelName,
      List<IliTextPatch> replacements,
      List<PendingInsertion> insertions,
      List<ExpectedChange> expected,
      Set<String> derivedImports,
      Map<String, IliConstraintSpec> requestedConstraints,
      IliSpecRenderer renderer) {
    switch (change.requireOperation()) {
      case REMOVE_COMMENTS -> {
        replacements.addAll(IliComments.removalPatches(document));
        expected.add(new ExpectedChange(Direction.CHANGE, "COMMENTS", localModel.getScopedName(), true));
      }
      case ADD_IMPORT -> {
        IliModelChangeRequest.AddImportChange payload = change.getAddImport();
        String target = fqn(payload.modelFqn, "addImport.modelFqn");
        require(target.equals(localModel.getScopedName()),
            "ADD_IMPORT target must be the submitted model: " + target);
        String imported = ident(payload.importModel, "addImport.importModel");
        require(!hasImport(localModel, imported), "Import already exists: " + imported);
        require(expected.stream().noneMatch(item -> item.direction() == Direction.ADD
                && "IMPORT".equals(item.kind()) && imported.equals(item.scopedName())),
            "Import is requested more than once in the batch: " + imported);
        var patch = sourceLocator.insertImports(document, locateBlock(document, localModel),
            List.of(imported), "Add import " + imported);
        insertions.add(new PendingInsertion(patch, operationIndex, true));
        expected.add(new ExpectedChange(Direction.ADD, "IMPORT", imported, false));
      }
      case ADD_TOPIC -> {
        IliModelChangeRequest.AddTopicChange payload = change.getAddTopic();
        require(payload.topic != null, "addTopic.topic is required.");
        String target = fqn(payload.modelFqn, "addTopic.modelFqn");
        require(target.equals(localModel.getScopedName()),
            "ADD_TOPIC target must be the submitted model.");
        IliSpecRenderer.RenderedFragment rendered = renderer.renderTopic(
            payload.topic, iliVersion, modelName, derivedImports);
        payload.topic.collectConstraints(modelName, requestedConstraints);
        addBeforeEnd(document, locateBlock(document, localModel), rendered.text(),
            operationIndex, "Add topic " + payload.topic.name, insertions);
        expected.add(new ExpectedChange(
            Direction.ADD, "TOPIC", modelName + "." + ident(payload.topic.name, "topic.name"), true));
      }
      case ADD_DOMAIN -> {
        IliModelChangeRequest.AddDomainChange payload = change.getAddDomain();
        require(payload.domain != null, "addDomain.domain is required.");
        Element container = localContainer(td, payload.containerFqn, Model.class, Topic.class);
        IliSpecRenderer.RenderedFragment rendered = renderer.renderDomain(payload.domain);
        deriveFromFqn(payload.domain.unitFqn, modelName, derivedImports);
        if (payload.domain.axes != null) {
          for (var axis : payload.domain.axes) {
            if (axis != null) {
              deriveFromFqn(axis.getUnitFqn(), modelName, derivedImports);
            }
          }
        }
        addBeforeEnd(document, locateBlock(document, container), rendered.text(),
            operationIndex, "Add domain " + payload.domain.name, insertions);
        expected.add(new ExpectedChange(Direction.ADD, "DOMAIN",
            container.getScopedName() + "." + ident(payload.domain.name, "domain.name"), false));
      }
      case ADD_UNIT -> {
        IliModelChangeRequest.AddUnitChange payload = change.getAddUnit();
        require(payload.unit != null, "addUnit.unit is required.");
        Element container = localContainer(td, payload.containerFqn, Model.class, Topic.class);
        IliSpecRenderer.RenderedFragment rendered = renderer.renderUnit(payload.unit);
        deriveFromFqn(payload.unit.baseUnitFqn, modelName, derivedImports);
        addBeforeEnd(document, locateBlock(document, container), rendered.text(),
            operationIndex, "Add unit " + payload.unit.name, insertions);
        expected.add(new ExpectedChange(Direction.ADD, "UNIT",
            container.getScopedName() + "." + ident(payload.unit.name, "unit.name"), false));
      }
      case ADD_CLASS -> {
        IliModelChangeRequest.AddClassChange payload = change.getAddClass();
        require(payload.clazz != null, "addClass.clazz is required.");
        Topic topic = (Topic) localContainer(td, payload.topicFqn, Topic.class);
        IliSpecRenderer.RenderedFragment rendered = renderer.renderClass(
            payload.clazz, iliVersion, modelName, derivedImports);
        payload.clazz.collectConstraints(topic.getScopedName(), requestedConstraints);
        addBeforeEnd(document, locateBlock(document, topic), rendered.text(),
            operationIndex, "Add class " + payload.clazz.name, insertions);
        expected.add(new ExpectedChange(Direction.ADD, "CLASS",
            topic.getScopedName() + "." + ident(payload.clazz.name, "class.name"), true));
      }
      case ADD_STRUCTURE -> {
        IliModelChangeRequest.AddStructureChange payload = change.getAddStructure();
        require(payload.structure != null, "addStructure.structure is required.");
        Topic topic = (Topic) localContainer(td, payload.topicFqn, Topic.class);
        IliSpecRenderer.RenderedFragment rendered = renderer.renderStructure(
            payload.structure, iliVersion, modelName, derivedImports);
        payload.structure.collectConstraints(topic.getScopedName(), requestedConstraints);
        addBeforeEnd(document, locateBlock(document, topic), rendered.text(),
            operationIndex, "Add structure " + payload.structure.name, insertions);
        expected.add(new ExpectedChange(Direction.ADD, "STRUCTURE",
            topic.getScopedName() + "." + ident(payload.structure.name, "structure.name"), true));
      }
      case ADD_ASSOCIATION -> {
        IliModelChangeRequest.AddAssociationChange payload = change.getAddAssociation();
        require(payload.association != null, "addAssociation.association is required.");
        Topic topic = (Topic) localContainer(td, payload.topicFqn, Topic.class);
        IliSpecRenderer.RenderedFragment rendered = renderer.renderAssociation(
            payload.association, iliVersion, modelName, derivedImports);
        payload.association.collectConstraints(topic.getScopedName(), requestedConstraints);
        addBeforeEnd(document, locateBlock(document, topic), rendered.text(),
            operationIndex, "Add association " + payload.association.name, insertions);
        expected.add(new ExpectedChange(Direction.ADD, "ASSOCIATION",
            topic.getScopedName() + "." + ident(payload.association.name, "association.name"), true));
      }
      case ADD_ATTRIBUTE -> prepareAddAttribute(
          change.requireAddAttribute(), operationIndex, td, document, iliVersion, modelName,
          insertions, expected, derivedImports);
      case UPDATE_ATTRIBUTE -> prepareUpdateAttribute(
          change.getUpdateAttribute(), td, document, iliVersion, modelName,
          replacements, expected, derivedImports);
      case REMOVE_ATTRIBUTE -> prepareRemoveAttribute(
          change.getRemoveAttribute(), td, document, replacements, expected);
      case ADD_CONSTRAINT -> {
        IliModelChangeRequest.AddConstraintChange payload = change.getAddConstraint();
        require(payload.constraint != null, "addConstraint.constraint is required.");
        var context = (Viewable<?>) localContainer(
            td, payload.containerFqn, Table.class, AssociationDef.class, View.class);
        String constraintFqn = context.getScopedName() + "." + ident(payload.constraint.name, "constraint.name");
        require(td.getElement(constraintFqn) == null
                && expected.stream().noneMatch(e -> e.scopedName().equals(constraintFqn)),
            "DUPLICATE_CONSTRAINT_NAME: " + constraintFqn);
        ConstraintSpecContract.collectConstraint(requestedConstraints, constraintFqn, payload.constraint);
        String constraintText = renderer.renderConstraint(
            payload.constraint, iliVersion, modelName, derivedImports).text();
        IliTextPatch patch = constraintSourceEditService.planConstraintInsertion(
            document, context, constraintText);
        insertions.add(new PendingInsertion(IliTextPatch.replace(patch.span(), patch.replacement(),
            "Add constraint " + payload.constraint.name), operationIndex, false));
        expected.add(new ExpectedChange(Direction.ADD, constraintKind(payload.constraint.kind()),
            constraintFqn, false));
      }
    }
  }

  private void prepareAddAttribute(
      AddAttributeChange payload,
      int operationIndex,
      TransferDescription td,
      IliSourceDocument document,
      String iliVersion,
      String modelName,
      List<PendingInsertion> insertions,
      List<ExpectedChange> expected,
      Set<String> derivedImports) {
    String containerFqn = fqn(payload.requireContainerFqn(), "addAttribute.containerFqn");
    Element element = localContainer(td, containerFqn, Table.class);
    require(element instanceof Table
            && !(element instanceof AssociationDef) && !(element instanceof View),
        "ADD_ATTRIBUTE supports CLASS and STRUCTURE only.");
    Table table = (Table) element;
    AttributeLineRequest attribute = payload.requireAttribute();
    String name = ident(attribute.getName(), "addAttribute.attribute.name");
    require(findAttributeInHierarchy(table, name) == null,
        "Attribute already exists in the hierarchy: " + containerFqn + "." + name);
    IliSpecRenderer.RenderedAttribute rendered = renderer.renderAttribute(
        attribute, iliVersion, modelName);
    derivedImports.addAll(rendered.derivedImports());
    IliSourceLocator.BlockLocation block = locateBlock(document, table);
    IliTextPatch patch = insertAttribute(document, table, block, rendered.text(),
        "Add attribute " + containerFqn + "." + name);
    insertions.add(new PendingInsertion(patch, operationIndex, false));
    expected.add(new ExpectedChange(
        Direction.ADD, "ATTRIBUTE", containerFqn + "." + name, false));
  }

  private void prepareUpdateAttribute(
      UpdateAttributeChange payload,
      TransferDescription td,
      IliSourceDocument document,
      String iliVersion,
      String modelName,
      List<IliTextPatch> replacements,
      List<ExpectedChange> expected,
      Set<String> derivedImports) {
    require(payload != null, "updateAttribute is required.");
    String attributeFqn = fqn(payload.attributeFqn, "updateAttribute.attributeFqn");
    require(payload.patch != null, "updateAttribute.patch is required.");
    payload.patch.validate();
    AttributeDef attribute = localAttribute(td, attributeFqn);
    Table container = (Table) attribute.getContainer();
    IliSourceLocator.BlockLocation block = locateBlock(document, container);
    IliSourceLocator.AttributeLocation location = sourceLocator.locateAttributeDeclaration(
        document, block, attribute.getName(), Math.max(1, attribute.getSourceLine()));
    ExistingAttribute existing = parseExistingAttribute(document.slice(location.declarationSpan()));

    boolean mandatory = payload.patch.mandatory != null
        ? payload.patch.mandatory : existing.mandatory();
    AttributeLineRequest.Collection collection = payload.patch.collection != null
        ? payload.patch.collection : existing.collection();
    String declaration;
    if (payload.patch.typeSpec != null) {
      AttributeLineRequest request = new AttributeLineRequest();
      request.setName(attribute.getName());
      request.setMandatory(mandatory);
      request.setCollection(collection);
      request.setTypeSpec(payload.patch.typeSpec);
      IliSpecRenderer.RenderedAttribute rendered = renderer.renderAttribute(
          request, iliVersion, modelName);
      declaration = rendered.text();
      derivedImports.addAll(rendered.derivedImports());
    } else {
      declaration = attribute.getName() + " : "
          + (mandatory ? "MANDATORY " : "")
          + switch (collection) {
            case NONE -> "";
            case LIST_OF -> "LIST OF ";
            case BAG_OF -> "BAG OF ";
          }
          + existing.typeText() + ";";
    }

    UpdateAttributeChange.IliDocAction docAction = payload.patch.iliDocAction == null
        ? UpdateAttributeChange.IliDocAction.KEEP : payload.patch.iliDocAction;
    UpdateAttributeChange.MetaAttributesAction metaAction = payload.patch.metaAttributesAction == null
        ? UpdateAttributeChange.MetaAttributesAction.KEEP : payload.patch.metaAttributesAction;
    if (docAction == UpdateAttributeChange.IliDocAction.KEEP
        && metaAction == UpdateAttributeChange.MetaAttributesAction.KEEP) {
      replacements.add(IliTextPatch.replace(
          location.declarationSpan(), declaration, "Update attribute " + attributeFqn));
    } else {
      String annotations = updatedAnnotations(document, location, payload.patch, docAction, metaAction);
      String replacement = annotations + location.indent() + declaration;
      replacements.add(IliTextPatch.replace(
          document.span(location.annotationSpan().startOffset(), location.declarationSpan().endOffset()),
          replacement,
          "Update attribute and annotations " + attributeFqn));
    }
    expected.add(new ExpectedChange(Direction.CHANGE, "ATTRIBUTE", attributeFqn, false));
  }

  private void prepareRemoveAttribute(
      RemoveAttributeChange payload,
      TransferDescription td,
      IliSourceDocument document,
      List<IliTextPatch> replacements,
      List<ExpectedChange> expected) {
    require(payload != null, "removeAttribute is required.");
    String attributeFqn = fqn(payload.attributeFqn, "removeAttribute.attributeFqn");
    AttributeDef attribute = localAttribute(td, attributeFqn);
    Table container = (Table) attribute.getContainer();
    IliSourceLocator.BlockLocation block = locateBlock(document, container);
    IliSourceLocator.AttributeLocation location = sourceLocator.locateAttributeDeclaration(
        document, block, attribute.getName(), Math.max(1, attribute.getSourceLine()));
    replacements.add(IliTextPatch.replace(
        location.ownedSpan(), "", "Remove attribute " + attributeFqn));
    expected.add(new ExpectedChange(Direction.REMOVE, "ATTRIBUTE", attributeFqn, false));
  }

  private String updatedAnnotations(
      IliSourceDocument document,
      IliSourceLocator.AttributeLocation location,
      UpdateAttributeChange.AttributePatch patch,
      UpdateAttributeChange.IliDocAction docAction,
      UpdateAttributeChange.MetaAttributesAction metaAction) {
    String existing = document.slice(location.annotationSpan());
    String eol = document.lineSeparator();
    String indent = location.indent();
    String existingDoc = annotationLines(existing, true, eol);
    String existingMeta = annotationLines(existing, false, eol);
    String doc = switch (docAction) {
      case KEEP -> existingDoc;
      case SET -> indentAnnotations(
          AnnotationRenderer.renderAnnotations(patch.iliDoc, null), indent, eol);
      case REMOVE -> "";
    };
    String meta = switch (metaAction) {
      case KEEP -> existingMeta;
      case REPLACE -> indentAnnotations(
          AnnotationRenderer.renderAnnotations(null, patch.metaAttributes), indent, eol);
      case REMOVE -> "";
    };
    return doc + meta;
  }

  private String annotationLines(String text, boolean doc, String eol) {
    if (text.isEmpty()) return "";
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
    StringBuilder result = new StringBuilder();
    boolean inDoc = false;
    for (String line : normalized.split("\n", -1)) {
      String trimmed = line.trim();
      if (trimmed.startsWith("/**")) inDoc = true;
      if ((doc && inDoc) || (!doc && trimmed.startsWith("!!@"))) {
        result.append(line).append(eol);
      }
      if (inDoc && trimmed.endsWith("*/")) inDoc = false;
    }
    return result.toString();
  }

  private String indentAnnotations(String rendered, String indent, String eol) {
    if (rendered == null || rendered.isEmpty()) return "";
    String normalized = rendered.replace("\r\n", "\n").replace('\r', '\n');
    StringBuilder result = new StringBuilder();
    for (String line : normalized.split("\n", -1)) {
      if (line.isEmpty()) continue;
      result.append(indent).append(line).append(eol);
    }
    return result.toString();
  }

  private ExistingAttribute parseExistingAttribute(String declaration) {
    int colon = declaration.indexOf(':');
    int semicolon = declaration.lastIndexOf(';');
    require(colon >= 0 && semicolon > colon, "Unable to parse existing attribute declaration.");
    String rhs = declaration.substring(colon + 1, semicolon).trim();
    boolean mandatory = consumePrefix(rhs, "MANDATORY ");
    if (mandatory) rhs = rhs.substring("MANDATORY ".length()).stripLeading();
    AttributeLineRequest.Collection collection = AttributeLineRequest.Collection.NONE;
    if (consumePrefix(rhs, "LIST OF ")) {
      collection = AttributeLineRequest.Collection.LIST_OF;
      rhs = rhs.substring("LIST OF ".length()).stripLeading();
    } else if (consumePrefix(rhs, "BAG OF ")) {
      collection = AttributeLineRequest.Collection.BAG_OF;
      rhs = rhs.substring("BAG OF ".length()).stripLeading();
    }
    require(!rhs.isBlank(), "Existing attribute type is empty.");
    return new ExistingAttribute(mandatory, collection, rhs);
  }

  private boolean consumePrefix(String text, String prefix) {
    return text.regionMatches(true, 0, prefix, 0, prefix.length());
  }

  private List<IliTextPatch> groupInsertions(
      IliSourceDocument document, List<PendingInsertion> pending, List<IliTextPatch> replacements) {
    Map<Integer, List<IliTextPatch>> byAnchor = new LinkedHashMap<>();
    // In an empty model, imports and declarations can share an offset. IMPORTS must come first.
    pending.stream()
        .sorted(Comparator.comparing(PendingInsertion::importInsertion).reversed()
            .thenComparingInt(PendingInsertion::operationIndex))
        .map(PendingInsertion::patch)
        .forEach(patch -> byAnchor.computeIfAbsent(patch.span().startOffset(), ignored -> new ArrayList<>()).add(patch));
    List<IliTextPatch> result = new ArrayList<>(replacements);
    String eol = document.lineSeparator();
    for (var entry : byAnchor.entrySet()) {
      StringBuilder text = new StringBuilder();
      for (IliTextPatch patch : entry.getValue()) {
        String fragment = patch.replacement();
        if (!text.isEmpty() && fragment.startsWith(eol)) {
          // Split a compact line once, with only the final patch's indentation before the original text.
          text.setLength(text.lastIndexOf(eol) + eol.length());
          text.append(fragment, eol.length(), fragment.length());
        } else {
          text.append(fragment);
        }
      }
      String description = entry.getValue().stream().map(IliTextPatch::description)
          .collect(Collectors.joining("; "));
      int offset = entry.getKey();
      // An insertion immediately before a replaced range consumes none of that range.
      var following = replacements.stream()
          .filter(patch -> !patch.span().isEmpty() && patch.span().startOffset() == offset)
          .findFirst();
      if (following.isPresent()) {
        IliTextPatch patch = following.get();
        result.remove(patch);
        result.add(IliTextPatch.replace(patch.span(), text.append(patch.replacement()).toString(),
            description + "; " + patch.description()));
      } else {
        result.add(IliTextPatch.insert(document, offset, text.toString(), description));
      }
    }
    return result;
  }

  private Prepared prepare(IliSourceDocument document, List<IliTextPatch> patches) {
    List<IliTextPatch> ordered = patches.stream()
        .sorted(Comparator.comparingInt(patch -> patch.span().startOffset())).toList();
    List<SourceEdit> edits = ordered.stream().map(patch -> IliAuthoringResult.sourceEdit(
        patch.span().startOffset(), patch.span().endOffset(), patch.span().startLine(),
        patch.span().endLine(), document.slice(patch.span()), patch.replacement(), patch.description())).toList();
    return new Prepared(IliPatchApplier.apply(document, ordered), edits);
  }

  private @Nullable String unexpectedSemanticChange(
      SemanticDiff diff, List<ExpectedChange> expected, TransferDescription after) {
    for (IliAuthoringResult.SemanticChange item : diff.added) {
      if (!matchesExpected(item, Direction.ADD, expected)) {
        return "Semantic diff contains an unexpected addition of " + describeSemanticChange(item);
      }
    }
    for (IliAuthoringResult.SemanticChange item : diff.removed) {
      if (!matchesExpected(item, Direction.REMOVE, expected)) {
        return "Semantic diff contains an unexpected removal of " + describeSemanticChange(item);
      }
    }
    for (IliAuthoringResult.SemanticChange item : diff.changed) {
      if (!matchesExpected(item, Direction.CHANGE, expected)
          && !viewDefinitionMatchesConstraintAdditions(item, expected, after)) {
        return "Semantic diff contains an unexpected change of " + describeSemanticChange(item);
      }
    }
    for (ExpectedChange item : expected) {
      if (item.direction() == Direction.CHANGE) continue;
      List<IliAuthoringResult.SemanticChange> actual = item.direction() == Direction.ADD
          ? diff.added : diff.removed;
      boolean found = actual.stream().anyMatch(item::matches);
      if (!found) return "Semantic diff does not contain expected "
          + item.direction() + " for " + item.scopedName() + ".";
    }
    return null;
  }

  private boolean viewDefinitionMatchesConstraintAdditions(
      IliAuthoringResult.SemanticChange item, List<ExpectedChange> expected, TransferDescription after) {
    if (!"VIEW".equals(item.kind) || !item.changedFields.equals(List.of("definitionText"))
        || item.before == null || item.after == null) return false;

    // ili2c prints each inline constraint both in its own diff entry and in the enclosing view.
    StringWriter additions = new StringWriter();
    var generator = Interlis2Generator.generateElements(additions, after);
    for (ExpectedChange change : expected) {
      if (change.direction() == Direction.ADD && !change.includeDescendants()
          && change.scopedName().startsWith(item.scopedName + ".")
          && after.getElement(change.scopedName()) instanceof Constraint constraint
          && item.scopedName.equals(constraint.getContainer().getScopedName())) {
        generator.printConstraint(constraint);
      }
    }
    if (additions.getBuffer().isEmpty()) return false;
    String beforeDefinition = (String) item.before.get("definitionText");
    int end = beforeDefinition.lastIndexOf('\n') + 1;
    String eol = System.lineSeparator();
    String block = IliSourceDocument.indentLines(additions.toString(), "  ", eol) + eol;
    return (beforeDefinition.substring(0, end) + block + beforeDefinition.substring(end))
        .equals(item.after.get("definitionText"));
  }

  private String describeSemanticChange(IliAuthoringResult.SemanticChange item) {
    String name = "META_ATTRIBUTE".equals(item.kind) && item.owner != null
        ? item.owner + "!!@" + item.name : item.scopedName;
    String fields = item.changedFields.isEmpty() ? "" : " (fields " + String.join(", ", item.changedFields) + ")";
    return item.kind + " " + name + fields + ".";
  }

  private boolean matchesExpected(
      IliAuthoringResult.SemanticChange item,
      Direction direction,
      List<ExpectedChange> expected) {
    String kind = item.kind;
    String scoped = item.scopedName;
    if ("META_ATTRIBUTE".equals(kind)) {
      scoped = item.owner;
    }
    for (ExpectedChange candidate : expected) {
      if ("COMMENTS".equals(candidate.kind()) && direction == Direction.CHANGE
      && (scoped.equals(candidate.scopedName()) || scoped.startsWith(candidate.scopedName() + "."))
      && !item.changedFields.isEmpty() && item.changedFields.stream().allMatch(f -> Set.of("documentation", "metaAttributes").contains(f))) return true;
      if ("COMMENTS".equals(candidate.kind()) && direction == Direction.CHANGE
          && nativeConstraintNameOnly(item)) return true;

      if (candidate.direction() == direction && candidate.matches(kind, scoped)) {
        return true;
      }
      if (candidate.direction() == Direction.CHANGE
          && "META_ATTRIBUTE".equals(kind)
          && candidate.matches(kind, scoped)) {
        return true;
      }
    }
    return false;
  }

  private boolean nativeConstraintNameOnly(IliAuthoringResult.SemanticChange item) {
    if (item.before == null || item.after == null
        || !item.changedFields.equals(List.of("definitionText"))) return false;
    Object name = item.before.get("name");
    if (name == null || !name.equals(item.after.get("name"))) return false;
    String before = String.valueOf(item.before.get("definitionText"));
    String after = String.valueOf(item.after.get("definitionText"));
    var prefix = Pattern.compile("^(MANDATORY CONSTRAINT|EXISTENCE CONSTRAINT|SET CONSTRAINT(?: \\(BASKET\\))?|UNIQUE(?: \\(BASKET\\))?|CONSTRAINT)(?=\\s)").matcher(before);
    return prefix.find() && after.equals(before.substring(0, prefix.end()) + " " + name + ":" + before.substring(prefix.end()));
  }

  private void addBeforeEnd(
      IliSourceDocument document,
      IliSourceLocator.BlockLocation block,
      String fragment,
      int operationIndex,
      String description,
      List<PendingInsertion> insertions) {
    insertions.add(new PendingInsertion(document.insertBeforeEnd(block, fragment, true, description),
        operationIndex, false));
  }

  private IliTextPatch insertAttribute(
      IliSourceDocument document, Table table, IliSourceLocator.BlockLocation block,
      String fragment, String description) {
    AttributeDef last = null;
    for (Iterator<?> elements = table.iterator(); elements.hasNext(); ) {
      Object item = elements.next();
      if (item instanceof AttributeDef attribute && attribute.getContainer() == table) last = attribute;
    }
    String indent = document.bodyIndent(block);
    if (last != null) {
      IliSourceSpan span = sourceLocator.locateAttribute(
          document, block, last.getName(), Math.max(1, last.getSourceLine()));
      String existingIndent = document.leadingIndent(span.startLine());
      if (document.lineStartOffset(span.startLine()) + existingIndent.length() == span.startOffset()) {
        indent = existingIndent;
      }
      return document.insertAfter(span, fragment, indent, description);
    }

    IliSourceSpan prefix = sourceLocator.attributeSectionStart(document, block);
    if (document.text().substring(prefix.endOffset(), block.endPreludeOffset()).isBlank()) {
      return document.insertBeforeEnd(block, fragment, false, description);
    }
    return document.insertAfter(prefix, fragment, indent, description);
  }

  private AttributeDef findAttributeInHierarchy(Table table, String name) {
    Element current = table;
    while (current instanceof Table currentTable) {
      Iterator<?> iterator = currentTable.iterator();
      while (iterator.hasNext()) {
        Object item = iterator.next();
        if (item instanceof AttributeDef attribute && attribute.getContainer() == currentTable
            && name.equals(attribute.getName())) return attribute;
      }
      current = currentTable.getExtending();
    }
    return null;
  }

  private AttributeDef localAttribute(TransferDescription td, String fqn) {
    Element element = localContainer(td, fqn, AttributeDef.class);
    require(element.getContainer() instanceof Table,
        "Attribute is not owned by a CLASS/STRUCTURE: " + fqn);
    return (AttributeDef) element;
  }

  @SafeVarargs
  private final Element localContainer(
      TransferDescription td, String rawFqn, Class<? extends Element>... allowedKinds) {
    String target = fqn(rawFqn, "target FQN");
    Element element = td.getElement(target);
    require(element != null, "Target not found: " + target);
    boolean allowed = false;
    for (Class<? extends Element> kind : allowedKinds) {
      if (kind.isInstance(element)) allowed = true;
    }
    require(allowed, "Target has the wrong element kind: " + target);
    require(isFromLastFile(td, element), "Target is imported and cannot be changed: " + target);
    return element;
  }

  private IliSourceLocator.BlockLocation locateBlock(
      IliSourceDocument document, Element element) {
    IliSourceLocator.BlockKind kind = switch (element) {
      case Model ignored -> IliSourceLocator.BlockKind.MODEL;
      case Topic ignored -> IliSourceLocator.BlockKind.TOPIC;
      case AssociationDef ignored -> IliSourceLocator.BlockKind.ASSOCIATION;
      case View ignored -> IliSourceLocator.BlockKind.VIEW;
      case Table table -> table.isIdentifiable()
          ? IliSourceLocator.BlockKind.CLASS : IliSourceLocator.BlockKind.STRUCTURE;
      default -> throw new IllegalArgumentException(
          "No source block locator for " + element.getClass().getSimpleName() + ".");
    };
    return element.getSourceLine() > 0
        ? sourceLocator.locateNamedBlock(document, kind, element.getName(), element.getSourceLine())
        : sourceLocator.locateNamedBlock(document, kind, element.getName());
  }

  private Model singleLocalModel(TransferDescription td) {
    List<Model> models = new ArrayList<>();
    for (Model model : td.getModelsFromLastFile()) models.add(model);
    require(models.size() == 1,
        "Exactly one submitted MODEL is required; got " + models.size() + ".");
    return models.getFirst();
  }

  private boolean isFromLastFile(TransferDescription td, Element element) {
    Element current = element;
    while (current != null && !(current instanceof Model)) current = current.getContainer();
    if (!(current instanceof Model owner)) return false;
    for (Model model : td.getModelsFromLastFile()) {
      if (model == owner || Objects.equals(model.getScopedName(), owner.getScopedName())) return true;
    }
    return false;
  }

  private boolean hasImport(Model model, String imported) {
    for (Model dependency : model.getImporting()) {
      if (dependency != null && imported.equals(dependency.getName())) return true;
    }
    return false;
  }

  private String iliVersion(String modelText) {
    Matcher matcher = ILI_VERSION.matcher(modelText);
    require(matcher.find(), "modelText must declare INTERLIS 2.3 or 2.4.");
    return matcher.group(1);
  }

  private String constraintKind(IliConstraintSpec.Kind kind) {
    return switch (kind) {
      case UNIQUE -> "UNIQUENESS_CONSTRAINT";
      case MANDATORY -> "MANDATORY_CONSTRAINT";
      case EXISTENCE -> "EXISTENCE_CONSTRAINT";
      case PLAUSIBILITY -> "PLAUSIBILITY_CONSTRAINT";
      case SET -> "SET_CONSTRAINT";
    };
  }

  private String ident(String value, String label) {
    require(value != null && !value.isBlank(), label + " is required.");
    String normalized = value.trim();
    NameValidator.ascii().validateIdent(normalized, label);
    return normalized;
  }

  private String fqn(String value, String label) {
    require(value != null && !value.isBlank(), label + " is required.");
    String normalized = value.trim();
    NameValidator.ascii().validateFqn(normalized, label);
    return normalized;
  }

  private void deriveFromFqn(@Nullable String value, String currentModel, Set<String> imports) {
    if (value == null || value.isBlank() || !value.contains(".")) return;
    String normalized = fqn(value, "referenced FQN");
    String model = normalized.substring(0, normalized.indexOf('.'));
    if (!model.equals(currentModel) && !model.equals("INTERLIS")) imports.add(model);
  }

  private void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }

  private enum Direction { ADD, CHANGE, REMOVE }

  private record ExpectedChange(
      Direction direction, String kind, String scopedName, boolean includeDescendants) {
    boolean matches(IliAuthoringResult.SemanticChange item) {
      return matches(item.kind, item.scopedName);
    }
    boolean matches(String actualKind, String actualScopedName) {
      if ("COMMENTS".equals(kind)) return "META_ATTRIBUTE".equals(actualKind) && (actualScopedName.equals(scopedName) || actualScopedName.startsWith(scopedName + "."));
      if ("META_ATTRIBUTE".equals(actualKind)) {
        return actualScopedName.equals(scopedName)
            || (includeDescendants && actualScopedName.startsWith(scopedName + "."));
      }
      boolean nameMatches = actualScopedName.equals(scopedName)
          || (includeDescendants && actualScopedName.startsWith(scopedName + "."));
      return nameMatches && (actualKind.equals(kind) || includeDescendants);
    }
  }

  private record PendingInsertion(IliTextPatch patch, int operationIndex, boolean importInsertion) {}
  private record Prepared(String candidate, List<SourceEdit> sourceEdits) {}
  private record ExistingAttribute(
      boolean mandatory, AttributeLineRequest.Collection collection, String typeText) {}
}
