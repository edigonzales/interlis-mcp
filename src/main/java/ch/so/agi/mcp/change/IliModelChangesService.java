package ch.so.agi.mcp.change;

import ch.interlis.ili2c.metamodel.AssociationDef;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.Constraint;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.Topic;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.View;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.model.AttributeLineRequest;
import ch.so.agi.mcp.model.IliAuthoringResult;
import ch.so.agi.mcp.model.IliConstraintSpec;
import ch.so.agi.mcp.model.IliModelSpec;
import ch.so.agi.mcp.model.IliSpecRenderer;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.ConstraintCaseGenerationTools;
import ch.so.agi.mcp.util.AnnotationRenderer;
import ch.so.agi.mcp.util.NameValidator;
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
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Atomic, source-preserving implementation of applyIliModelChanges. */
@Service
public final class IliModelChangesService {

  private static final Pattern ILI_VERSION =
      Pattern.compile("(?m)^\\s*INTERLIS\\s+(2\\.3|2\\.4)\\s*;");

  private final IliCompilerService compilerService;
  private final ModelChangeReviewService reviewService;
  private final IliSpecRenderer renderer;
  private final @Nullable ConstraintContextService constraintContextService;
  private final @Nullable ConstraintCaseGenerationTools caseGenerationTools;
  private final IliSourceLocator sourceLocator = new IliSourceLocator();

  public IliModelChangesService(
      IliCompilerService compilerService,
      ModelChangeReviewService reviewService,
      IliSpecRenderer renderer) {
    this(compilerService, reviewService, renderer, null, null);
  }

  @Autowired
  public IliModelChangesService(
      IliCompilerService compilerService,
      ModelChangeReviewService reviewService,
      IliSpecRenderer renderer,
      @Nullable ConstraintContextService constraintContextService,
      @Nullable ConstraintCaseGenerationTools caseGenerationTools) {
    this.compilerService = compilerService;
    this.reviewService = reviewService;
    this.renderer = renderer;
    this.constraintContextService = constraintContextService;
    this.caseGenerationTools = caseGenerationTools;
  }

  public IliAuthoringResult apply(
      String modelText,
      IliModelChangesRequest request,
      @Nullable String modelRepositories,
      @Nullable ModelPurpose modelPurpose,
      @Nullable ModelingRuleProfile ruleProfile) {
    if (modelText == null || modelText.isBlank()) {
      return failure("INVALID_SPEC", "modelText is required.");
    }
    if (request == null) {
      return failure("INVALID_SPEC", "request is required.");
    }

    IliCompilerService.CompilationResult before = compilerService.compile(
        modelText, modelRepositories, "ili2c_model_changes_before_");
    if (!before.valid() || before.transferDescription() == null) {
      IliAuthoringResult result = failure(
          "BEFORE_MODEL_INVALID", "The supplied before model must compile.");
      result.beforeDiagnostics = IliAuthoringResult.diagnostics(before.messages());
      result.compilerDiagnostics = IliAuthoringResult.diagnostics(before.messages());
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
      IliAuthoringResult result = failure("INVALID_SPEC", ex.getMessage());
      result.beforeDiagnostics = IliAuthoringResult.diagnostics(before.messages());
      result.compilerDiagnostics = IliAuthoringResult.diagnostics(before.messages());
      return result;
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

    for (int index = 0; index < changes.size(); index++) {
      prepareOperation(
          changes.get(index), index, td, localModel, document, iliVersion, modelName,
          replacements, insertions, expected, derivedImports);
    }
    addMissingDerivedImports(
        document, localModel, changes.size(), insertions, expected, derivedImports);

    List<IliTextPatch> patches = new ArrayList<>(replacements);
    patches.addAll(groupInsertions(document, insertions));
    Prepared prepared = prepare(document, patches);
    if (prepared.candidate().equals(modelText)) {
      IliAuthoringResult result = failure(
          "INVALID_SPEC", "The requested batch changes no source text.");
      result.beforeDiagnostics = IliAuthoringResult.diagnostics(before.messages());
      result.sourceEdits = IliAuthoringResult.sourceEdits(prepared.sourceEdits());
      return result;
    }

    IliCompilerService.CompilationResult after = compilerService.compile(
        prepared.candidate(), modelRepositories, "ili2c_model_changes_after_");
    Map<String, Object> review = reviewService.reviewCompiledChange(
        before, after, modelText, prepared.candidate(), modelPurpose, ruleProfile);
    IliAuthoringResult result = reviewResult(review);
    result.applied = false;
    result.complete = false;
    result.beforeDiagnostics = IliAuthoringResult.diagnostics(before.messages());
    result.afterDiagnostics = IliAuthoringResult.diagnostics(after.messages());
    result.compilerDiagnostics = IliAuthoringResult.diagnostics(after.messages());
    result.sourceEdits = IliAuthoringResult.sourceEdits(prepared.sourceEdits());
    result.derivedImports = List.copyOf(derivedImports);

    if (!after.valid()) {
      result.status = IliAuthoringResult.Status.CANDIDATE_MODEL_INVALID;
      result.reasonCode = "CANDIDATE_MODEL_INVALID";
      result.reason = "The complete batch candidate does not compile; no change was released.";
      result.candidateModelText = prepared.candidate();
      return result;
    }

    String unexpected = unexpectedSemanticChange(result, expected);
    if (unexpected != null) {
      result.status = IliAuthoringResult.Status.UNEXPECTED_SEMANTIC_CHANGE;
      result.reasonCode = "UNEXPECTED_SEMANTIC_CHANGE";
      result.reason = unexpected;
      result.candidateModelText = prepared.candidate();
      return result;
    }

    if (changes.stream().anyMatch(this::addsExternalFunctionConstraint)) {
      result.status = IliAuthoringResult.Status.EXTERNAL_FUNCTION_SEMANTICS_REQUIRED;
      result.reasonCode = result.status.name();
      result.reason = "The batch adds a constraint with model-defined or validator-extension function semantics that cannot be proved automatically.";
      result.candidateModelText = prepared.candidate();
      result.proofVerified = false;
      result.requiresUserDecision = true;
      result.openQuestions = List.of(IliAuthoringResult.openQuestion(
          "Provide independently verified validator cases for every external function in the batch."));
      return result;
    }

    if (constraintContextService != null && caseGenerationTools != null
        && !proveAddedConstraints(result, prepared.candidate(), modelRepositories, after)) {
      boolean incomplete = result.constraintProofs.stream().anyMatch(
          proof -> !Boolean.TRUE.equals(proof.coverageComplete));
      result.status = incomplete
          ? IliAuthoringResult.Status.PROOF_INCOMPLETE
          : IliAuthoringResult.Status.PROOF_FAILED;
      result.reasonCode = result.status.name();
      result.reason = "At least one added constraint could not be fully proved.";
      result.candidateModelText = prepared.candidate();
      return result;
    }

    if (!result.potentiallyBreakingChanges.isEmpty() && !allowPotentiallyBreaking) {
      result.status = IliAuthoringResult.Status.BREAKING_CHANGE_REQUIRES_CONFIRMATION;
      result.reasonCode = "BREAKING_CHANGE_REQUIRES_CONFIRMATION";
      result.reason = "The semantic review found potentially breaking changes. Repeat the same batch with allowPotentiallyBreaking=true to release it.";
      result.candidateModelText = prepared.candidate();
      result.requiresUserDecision = true;
      result.openQuestions = List.of(IliAuthoringResult.openQuestion(
          "Confirm all potentially breaking changes by setting allowPotentiallyBreaking=true."));
      return result;
    }

    result.status = IliAuthoringResult.Status.APPLIED;
    result.complete = true;
    result.applied = true;
    result.updatedModelText = prepared.candidate();
    return result;
  }

  private boolean addsExternalFunctionConstraint(IliModelChangeRequest change) {
    return switch (change.requireOperation()) {
      case ADD_CONSTRAINT -> change.getAddConstraint().constraint.hasExternalFunctionSemantics();
      case ADD_CLASS -> hasExternalFunctions(change.getAddClass().clazz.constraints);
      case ADD_STRUCTURE -> hasExternalFunctions(change.getAddStructure().structure.constraints);
      case ADD_ASSOCIATION -> hasExternalFunctions(change.getAddAssociation().association.constraints);
      case ADD_TOPIC -> hasExternalFunctions(change.getAddTopic().topic);
      default -> false;
    };
  }

  private boolean hasExternalFunctions(IliModelSpec.TopicSpec topic) {
    for (IliModelSpec.ClassSpec clazz : safe(topic.classes)) {
      if (hasExternalFunctions(clazz.constraints)) return true;
    }
    for (IliModelSpec.StructureSpec structure : safe(topic.structures)) {
      if (hasExternalFunctions(structure.constraints)) return true;
    }
    for (IliModelSpec.AssociationSpec association : safe(topic.associations)) {
      if (hasExternalFunctions(association.constraints)) return true;
    }
    return false;
  }

  private boolean hasExternalFunctions(@Nullable List<IliConstraintSpec> constraints) {
    return safe(constraints).stream().anyMatch(IliConstraintSpec::hasExternalFunctionSemantics);
  }

  private <T> List<T> safe(@Nullable List<T> values) {
    return values == null ? List.of() : values;
  }

  private void addMissingDerivedImports(
      IliSourceDocument document,
      Model localModel,
      int operationIndex,
      List<PendingInsertion> insertions,
      List<ExpectedChange> expected,
      Set<String> derivedImports) {
    Set<String> scheduled = new LinkedHashSet<>();
    for (ExpectedChange item : expected) {
      if (item.direction() == Direction.ADD && "IMPORT".equals(item.kind())) {
        scheduled.add(item.scopedName());
      }
    }
    IliSourceLocator.BlockLocation block = locateBlock(document, localModel);
    int anchor = afterHeaderAnchor(document, block);
    String indent = blockIndent(document, block) + "  ";
    int offset = 0;
    for (String imported : derivedImports) {
      if (hasImport(localModel, imported) || !scheduled.add(imported)) continue;
      insertions.add(new PendingInsertion(
          anchor,
          indent + "IMPORTS " + imported + ";" + document.lineSeparator(),
          operationIndex + offset++,
          "Add derived import " + imported));
      expected.add(new ExpectedChange(Direction.ADD, "IMPORT", imported, false));
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
      Set<String> derivedImports) {
    switch (change.requireOperation()) {
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
        IliSourceLocator.BlockLocation block = locateBlock(document, localModel);
        int anchor = afterHeaderAnchor(document, block);
        String indent = blockIndent(document, block) + "  ";
        insertions.add(new PendingInsertion(
            anchor, indent + "IMPORTS " + imported + ";" + document.lineSeparator(),
            operationIndex, "Add import " + imported));
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
        Element context = localContainer(
            td, payload.containerFqn, Table.class, AssociationDef.class, View.class);
        String blockText = renderer.renderExternalConstraintBlock(
            context.getScopedName(), payload.constraint, iliVersion, modelName, derivedImports);
        Topic topic = containingTopic(context);
        require(topic != null, "Constraint context must be inside a TOPIC.");
        addBeforeEnd(document, locateBlock(document, topic), blockText,
            operationIndex, "Add constraint " + payload.constraint.name, insertions);
        expected.add(new ExpectedChange(Direction.ADD, constraintKind(payload.constraint.kind()),
            context.getScopedName() + "." + ident(payload.constraint.name, "constraint.name"), false));
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
    InsertionAnchor anchor = attributeInsertionAnchor(document, table, block);
    String snippet = indent(rendered.text(), anchor.indent(), document.lineSeparator());
    String insertion = (anchor.leadingLineSeparator() ? document.lineSeparator() : "")
        + snippet
        + (anchor.trailingLineSeparator() ? document.lineSeparator() : "")
        + (anchor.indentFollowingText() ? blockIndent(document, block) : "");
    insertions.add(new PendingInsertion(anchor.offset(), insertion, operationIndex,
        "Add attribute " + containerFqn + "." + name));
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
      IliSourceDocument document, List<PendingInsertion> pending) {
    Map<Integer, List<PendingInsertion>> byAnchor = new LinkedHashMap<>();
    pending.stream()
        .sorted(Comparator.comparingInt(PendingInsertion::operationIndex))
        .forEach(insertion -> byAnchor.computeIfAbsent(
            insertion.anchor(), ignored -> new ArrayList<>()).add(insertion));
    List<IliTextPatch> result = new ArrayList<>();
    for (Map.Entry<Integer, List<PendingInsertion>> entry : byAnchor.entrySet()) {
      String text = entry.getValue().stream().map(PendingInsertion::text)
          .reduce("", String::concat);
      String description = entry.getValue().stream().map(PendingInsertion::description)
          .reduce((left, right) -> left + "; " + right).orElse("Insert model elements");
      result.add(IliTextPatch.insert(document, entry.getKey(), text, description));
    }
    return result;
  }

  private Prepared prepare(IliSourceDocument document, List<IliTextPatch> patches) {
    List<IliTextPatch> ordered = patches.stream()
        .sorted(Comparator.comparingInt(patch -> patch.span().startOffset())).toList();
    List<Map<String, Object>> edits = new ArrayList<>();
    for (IliTextPatch patch : ordered) {
      Map<String, Object> edit = new LinkedHashMap<>();
      edit.put("startOffset", patch.span().startOffset());
      edit.put("endOffset", patch.span().endOffset());
      edit.put("startLine", patch.span().startLine());
      edit.put("endLine", patch.span().endLine());
      edit.put("before", document.slice(patch.span()));
      edit.put("after", patch.replacement());
      edit.put("description", patch.description());
      edits.add(edit);
    }
    return new Prepared(IliPatchApplier.apply(document, ordered), List.copyOf(edits));
  }

  private IliAuthoringResult reviewResult(Map<String, Object> review) {
    IliAuthoringResult result = new IliAuthoringResult();
    result.semanticDiff = IliAuthoringResult.semanticDiff(review);
    result.afterReview = IliAuthoringResult.modelReview(review.get("afterReview"));
    if (result.afterReview != null) {
      result.openQuestions = result.afterReview.openQuestions;
      result.requiresUserDecision = !result.openQuestions.isEmpty();
    }
    result.added = IliAuthoringResult.semanticChanges(review.get("added"));
    result.removed = IliAuthoringResult.semanticChanges(review.get("removed"));
    result.changed = IliAuthoringResult.semanticChanges(review.get("changed"));
    result.potentiallyBreakingChanges = IliAuthoringResult.semanticChanges(
        review.get("potentiallyBreakingChanges"));
    result.impact = string(review.get("impact"));
    return result;
  }

  private boolean proveAddedConstraints(
      IliAuthoringResult result,
      String candidate,
      @Nullable String modelRepositories,
      IliCompilerService.CompilationResult after) {
    List<IliAuthoringResult.ConstraintProof> proofs = new ArrayList<>();
    boolean verified = true;
    for (IliAuthoringResult.SemanticChange item : result.added) {
      String kind = String.valueOf(item.get("kind"));
      if (!kind.endsWith("_CONSTRAINT")) continue;
      String fqn = String.valueOf(item.get("scopedName"));
      var resolution = constraintContextService.resolveCompiled(
          candidate, fqn, modelRepositories, after);
      if (!resolution.available()) {
        IliAuthoringResult.ConstraintProof entry = IliAuthoringResult.constraintProof(
            fqn, false, null);
        entry.reasonCode = resolution.reasonCode();
        entry.reason = resolution.reason();
        proofs.add(entry);
        verified = false;
      } else {
        Map<String, Object> proof = caseGenerationTools.generateCompiledConstraintCases(
            resolution.context());
        boolean proofVerified = Boolean.TRUE.equals(proof.get("generationVerified"))
            && Boolean.TRUE.equals(proof.get("coverageComplete"));
        proofs.add(IliAuthoringResult.constraintProof(fqn, proofVerified, proof));
        verified &= proofVerified;
      }
    }
    result.constraintProofs = List.copyOf(proofs);
    result.proofVerified = proofs.isEmpty() ? null : verified;
    return verified;
  }

  private @Nullable String unexpectedSemanticChange(
      IliAuthoringResult result, List<ExpectedChange> expected) {
    for (IliAuthoringResult.SemanticChange item : result.added) {
      if (!matchesExpected(item, Direction.ADD, expected)) {
        return "Semantic diff contains an unexpected addition: " + item;
      }
    }
    for (IliAuthoringResult.SemanticChange item : result.removed) {
      if (!matchesExpected(item, Direction.REMOVE, expected)) {
        return "Semantic diff contains an unexpected removal: " + item;
      }
    }
    for (IliAuthoringResult.SemanticChange item : result.changed) {
      if (!matchesExpected(item, Direction.CHANGE, expected)) {
        return "Semantic diff contains an unexpected change: " + item;
      }
    }
    for (ExpectedChange item : expected) {
      if (item.direction() == Direction.CHANGE) continue;
      List<IliAuthoringResult.SemanticChange> actual = item.direction() == Direction.ADD
          ? result.added : result.removed;
      boolean found = actual.stream().anyMatch(diff -> item.matches(diff));
      if (!found) return "Semantic diff does not contain expected "
          + item.direction() + " for " + item.scopedName() + ".";
    }
    return null;
  }

  private boolean matchesExpected(
      IliAuthoringResult.SemanticChange item,
      Direction direction,
      List<ExpectedChange> expected) {
    String kind = String.valueOf(item.get("kind"));
    String scoped = String.valueOf(item.get("scopedName"));
    if ("META_ATTRIBUTE".equals(kind)) {
      scoped = String.valueOf(item.get("owner"));
    }
    for (ExpectedChange candidate : expected) {
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

  private void addBeforeEnd(
      IliSourceDocument document,
      IliSourceLocator.BlockLocation block,
      String fragment,
      int operationIndex,
      String description,
      List<PendingInsertion> insertions) {
    int anchor = document.lineStartOffset(block.endMarkerSpan().startLine());
    String indent = blockIndent(document, block) + "  ";
    String text = indent(fragment, indent, document.lineSeparator())
        + document.lineSeparator() + document.lineSeparator();
    insertions.add(new PendingInsertion(anchor, text, operationIndex, description));
  }

  private int afterHeaderAnchor(
      IliSourceDocument document, IliSourceLocator.BlockLocation block) {
    int line = block.headerSpan().endLine();
    return line < document.lineCount()
        ? document.lineStartOffset(line + 1)
        : block.bodySpan().startOffset();
  }

  private InsertionAnchor attributeInsertionAnchor(
      IliSourceDocument document,
      Table table,
      IliSourceLocator.BlockLocation block) {
    AttributeDef last = lastDirectAttribute(table);
    if (last != null) {
      IliSourceSpan span = sourceLocator.locateAttribute(
          document, block, last.getName(), Math.max(1, last.getSourceLine()));
      String indent = document.text().substring(
          document.lineStartOffset(span.startLine()), span.startOffset());
      return new InsertionAnchor(
          document.lineEndOffset(span.endLine()), indent, true, false, false);
    }
    Constraint first = firstDirectConstraint(table);
    if (first != null && first.getSourceLine() > 0) {
      int line = first.getSourceLine();
      return new InsertionAnchor(
          document.lineStartOffset(line), leadingWhitespace(document.lineText(line)), false, true, false);
    }
    if (block.headerSpan().startLine() == block.endMarkerSpan().startLine()) {
      return new InsertionAnchor(
          block.endMarkerSpan().startOffset(), blockIndent(document, block) + "  ", true, true, true);
    }
    return new InsertionAnchor(
        document.lineStartOffset(block.endMarkerSpan().startLine()),
        blockIndent(document, block) + "  ", false, true, false);
  }

  private AttributeDef lastDirectAttribute(Table table) {
    AttributeDef result = null;
    Iterator<?> iterator = table.iterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (item instanceof AttributeDef attribute && attribute.getContainer() == table
          && (result == null || attribute.getSourceLine() > result.getSourceLine())) result = attribute;
    }
    return result;
  }

  private Constraint firstDirectConstraint(Table table) {
    Constraint result = null;
    Iterator<?> iterator = table.iterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (item instanceof Constraint constraint && constraint.getContainer() == table
          && constraint.getSourceLine() > 0
          && (result == null || constraint.getSourceLine() < result.getSourceLine())) result = constraint;
    }
    return result;
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

  private @Nullable Topic containingTopic(Element element) {
    Element current = element;
    while (current != null) {
      if (current instanceof Topic topic) return topic;
      current = current.getContainer();
    }
    return null;
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

  private String blockIndent(
      IliSourceDocument document, IliSourceLocator.BlockLocation block) {
    int lineStart = document.lineStartOffset(block.headerSpan().startLine());
    return document.text().substring(lineStart, block.headerSpan().startOffset());
  }

  private String leadingWhitespace(String line) {
    int index = 0;
    while (index < line.length() && Character.isWhitespace(line.charAt(index))) index++;
    return line.substring(0, index);
  }

  private String indent(String snippet, String indent, String eol) {
    String normalized = snippet.replace("\r\n", "\n").replace('\r', '\n');
    return normalized.lines().map(line -> indent + line)
        .reduce((left, right) -> left + eol + right).orElse("");
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

  private IliAuthoringResult failure(String status, @Nullable String reason) {
    IliAuthoringResult result = new IliAuthoringResult();
    result.status = IliAuthoringResult.Status.valueOf(status);
    result.complete = false;
    result.applied = false;
    result.proofVerified = false;
    result.reasonCode = status;
    result.reason = reason == null ? "" : reason;
    return result;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> mapList(@Nullable Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : list) if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
    return List.copyOf(result);
  }

  @SuppressWarnings("unchecked")
  private @Nullable Map<String, Object> map(@Nullable Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
  }

  private @Nullable String string(@Nullable Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private List<String> reviewQuestions(@Nullable Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    List<String> result = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map && map.get("question") != null) {
        result.add(String.valueOf(map.get("question")));
      } else if (item != null) {
        result.add(String.valueOf(item));
      }
    }
    return List.copyOf(result);
  }

  private enum Direction { ADD, CHANGE, REMOVE }

  private record ExpectedChange(
      Direction direction, String kind, String scopedName, boolean includeDescendants) {
    boolean matches(Map<String, Object> item) {
      return matches(String.valueOf(item.get("kind")), String.valueOf(item.get("scopedName")));
    }
    boolean matches(IliAuthoringResult.SemanticChange item) {
      return matches(String.valueOf(item.get("kind")), String.valueOf(item.get("scopedName")));
    }
    boolean matches(String actualKind, String actualScopedName) {
      if ("META_ATTRIBUTE".equals(actualKind)) {
        return actualScopedName.equals(scopedName)
            || (includeDescendants && actualScopedName.startsWith(scopedName + "."));
      }
      boolean nameMatches = actualScopedName.equals(scopedName)
          || (includeDescendants && actualScopedName.startsWith(scopedName + "."));
      return nameMatches && (actualKind.equals(kind) || includeDescendants);
    }
  }

  private record PendingInsertion(
      int anchor, String text, int operationIndex, String description) {}
  private record Prepared(String candidate, List<Map<String, Object>> sourceEdits) {}
  private record ExistingAttribute(
      boolean mandatory, AttributeLineRequest.Collection collection, String typeText) {}
  private record InsertionAnchor(
      int offset,
      String indent,
      boolean leadingLineSeparator,
      boolean trailingLineSeparator,
      boolean indentFollowingText) {}
}
