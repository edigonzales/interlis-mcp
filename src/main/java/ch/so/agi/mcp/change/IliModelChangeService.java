package ch.so.agi.mcp.change;

import ch.interlis.ili2c.metamodel.AssociationDef;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.Constraint;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.View;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.model.AttributeLineRequest;
import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.AttributeTools;
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
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class IliModelChangeService {

  private final IliCompilerService compilerService;
  private final ModelChangeReviewService reviewService;
  private final AttributeTools attributeTools;
  private final IliSourceLocator sourceLocator = new IliSourceLocator();

  @Autowired
  public IliModelChangeService(
      IliCompilerService compilerService,
      ModelChangeReviewService reviewService,
      AttributeTools attributeTools) {
    this.compilerService = compilerService;
    this.reviewService = reviewService;
    this.attributeTools = attributeTools;
  }

  IliModelChangeService(
      IliCompilerService compilerService,
      ModelChangeReviewService reviewService) {
    this(compilerService, reviewService, new AttributeTools());
  }

  public Map<String, Object> apply(
      String modelText,
      IliModelChangeRequest request,
      @Nullable String modelRepositories,
      @Nullable ModelPurpose modelPurpose,
      @Nullable ModelingRuleProfile ruleProfile) {
    requireModelText(modelText);
    Objects.requireNonNull(request, "request");
    IliModelChangeOperation operation = request.requireOperation();

    return switch (operation) {
      case ADD_ATTRIBUTE -> applyAddAttribute(
          modelText,
          request.requireAddAttribute(),
          modelRepositories,
          modelPurpose,
          ruleProfile);
    };
  }

  private Map<String, Object> applyAddAttribute(
      String modelText,
      AddAttributeChange change,
      @Nullable String modelRepositories,
      @Nullable ModelPurpose modelPurpose,
      @Nullable ModelingRuleProfile ruleProfile) {
    String containerFqn = change.requireContainerFqn();
    NameValidator.ascii().validateFqn(containerFqn, "addAttribute.containerFqn");

    AttributeLineRequest attribute = change.requireAttribute();
    String renderedAttribute = attributeTools.createAttributeLine(attribute).getIliSnippet();
    String attributeName = attribute.getName().trim();
    String targetFqn = containerFqn + "." + attributeName;

    IliCompilerService.CompilationResult beforeCompilation =
        compileBefore(modelText, modelRepositories);
    if (!beforeCompilation.valid()) {
      return withTarget(
          finalizePreparedChange(
              modelText,
              IliModelChangeOperation.ADD_ATTRIBUTE,
              beforeCompilation,
              new PreparedChange(modelText, List.of()),
              modelRepositories,
              modelPurpose,
              ruleProfile),
          targetFqn);
    }

    TransferDescription td = Objects.requireNonNull(beforeCompilation.transferDescription());
    Element element = td.getElement(containerFqn);
    if (element == null) {
      return operationFailure(
          IliModelChangeStatus.TARGET_NOT_FOUND,
          targetFqn,
          "Container not found: " + containerFqn,
          beforeCompilation);
    }
    if (!(element instanceof Table table)
        || element instanceof AssociationDef
        || element instanceof View) {
      return operationFailure(
          IliModelChangeStatus.WRONG_TARGET_KIND,
          targetFqn,
          "ADD_ATTRIBUTE supports CLASS and STRUCTURE containers only.",
          beforeCompilation);
    }
    if (!isFromLastFile(td, table)) {
      return operationFailure(
          IliModelChangeStatus.TARGET_IS_IMPORTED,
          targetFqn,
          "The target container is not defined in the submitted model text.",
          beforeCompilation);
    }

    AttributeDef existing = findAttributeInHierarchy(table, attributeName);
    if (existing != null) {
      return operationFailure(
          IliModelChangeStatus.NAME_ALREADY_EXISTS,
          targetFqn,
          "Attribute name already exists in the target class/structure hierarchy: "
              + existing.getScopedName(),
          beforeCompilation);
    }

    IliSourceDocument document = IliSourceDocument.of(modelText);
    IliSourceLocator.BlockKind blockKind =
        table.isIdentifiable()
            ? IliSourceLocator.BlockKind.CLASS
            : IliSourceLocator.BlockKind.STRUCTURE;
    IliSourceLocator.BlockLocation block = table.getSourceLine() > 0
        ? sourceLocator.locateNamedBlock(document, blockKind, table.getName(), table.getSourceLine())
        : sourceLocator.locateNamedBlock(document, blockKind, table.getName());

    InsertionAnchor anchor = insertionAnchor(document, table, block);
    String snippet = indentSnippet(renderedAttribute, anchor.indent(), document.lineSeparator());
    String insertion = anchor.afterExistingLine()
        ? document.lineSeparator() + snippet
        : snippet + document.lineSeparator();

    IliTextPatch patch = IliTextPatch.insert(
        document,
        anchor.offset(),
        insertion,
        "Add attribute " + targetFqn);
    PreparedChange prepared = preparePatchedChange(document, List.of(patch));

    Map<String, Object> response = finalizePreparedChange(
        modelText,
        IliModelChangeOperation.ADD_ATTRIBUTE,
        beforeCompilation,
        prepared,
        modelRepositories,
        modelPurpose,
        ruleProfile);
    response = withTarget(response, targetFqn);
    return guardAddAttributeChange(response, targetFqn, attribute.getMetaAttributes());
  }

  IliCompilerService.CompilationResult compileBefore(
      String modelText,
      @Nullable String modelRepositories) {
    requireModelText(modelText);
    return compilerService.compile(modelText, modelRepositories, "ili2c_model_change_before_");
  }

  PreparedChange preparePatchedChange(
      IliSourceDocument document,
      List<IliTextPatch> patches) {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(patches, "patches");

    List<IliTextPatch> ordered = new ArrayList<>(patches);
    ordered.sort(Comparator.comparingInt(patch -> patch.span().startOffset()));

    List<Map<String, Object>> sourceEdits = new ArrayList<>();
    for (IliTextPatch patch : ordered) {
      Objects.requireNonNull(patch, "patch");
      Map<String, Object> edit = new LinkedHashMap<>();
      edit.put("startOffset", patch.span().startOffset());
      edit.put("endOffset", patch.span().endOffset());
      edit.put("startLine", patch.span().startLine());
      edit.put("endLine", patch.span().endLine());
      edit.put("before", document.slice(patch.span()));
      edit.put("after", patch.replacement());
      edit.put("description", patch.description());
      sourceEdits.add(edit);
    }

    return new PreparedChange(
        IliPatchApplier.apply(document, ordered),
        List.copyOf(sourceEdits));
  }

  Map<String, Object> finalizePreparedChange(
      String beforeModelText,
      IliModelChangeOperation operation,
      IliCompilerService.CompilationResult beforeCompilation,
      PreparedChange preparedChange,
      @Nullable String modelRepositories,
      @Nullable ModelPurpose modelPurpose,
      @Nullable ModelingRuleProfile ruleProfile) {
    requireModelText(beforeModelText);
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(beforeCompilation, "beforeCompilation");
    Objects.requireNonNull(preparedChange, "preparedChange");

    ModelPurpose purpose = ModelPurpose.normalize(modelPurpose);
    ModelingRuleProfile profile = ModelingRuleProfile.normalize(ruleProfile);

    if (!beforeCompilation.valid()) {
      return beforeModelInvalidResponse(operation, beforeCompilation);
    }

    if (beforeModelText.equals(preparedChange.updatedModelText())) {
      return noChangeResponse(operation, beforeCompilation, preparedChange.sourceEdits());
    }

    IliCompilerService.CompilationResult afterCompilation =
        compilerService.compile(
            preparedChange.updatedModelText(),
            modelRepositories,
            "ili2c_model_change_after_");

    Map<String, Object> review = reviewService.reviewCompiledChange(
        beforeCompilation,
        afterCompilation,
        beforeModelText,
        preparedChange.updatedModelText(),
        purpose,
        profile);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("applied", afterCompilation.valid());
    response.put(
        "status",
        afterCompilation.valid()
            ? IliModelChangeStatus.APPLIED.name()
            : IliModelChangeStatus.AFTER_MODEL_INVALID.name());
    response.put("operation", operation.name());
    response.put("sourceEdits", preparedChange.sourceEdits());
    if (afterCompilation.valid()) {
      response.put("updatedModelText", preparedChange.updatedModelText());
    } else {
      response.put("candidateModelText", preparedChange.updatedModelText());
    }
    response.putAll(review);
    return response;
  }

  Map<String, Object> guardAddAttributeChange(
      Map<String, Object> response,
      String targetFqn,
      @Nullable List<MetaAttributeSpec> requestedMetaAttributes) {
    if (!IliModelChangeStatus.APPLIED.name().equals(response.get("status"))) {
      return response;
    }

    Set<String> expectedMetaNames = new LinkedHashSet<>();
    if (requestedMetaAttributes != null) {
      for (MetaAttributeSpec metaAttribute : requestedMetaAttributes) {
        if (metaAttribute != null && metaAttribute.getName() != null) {
          expectedMetaNames.add(metaAttribute.getName().trim());
        }
      }
    }

    List<Map<String, Object>> added = mapList(response.get("added"));
    List<Map<String, Object>> removed = mapList(response.get("removed"));
    List<Map<String, Object>> changed = mapList(response.get("changed"));
    int expectedAttributeCount = 0;
    Set<String> actualMetaNames = new LinkedHashSet<>();
    boolean unexpectedAdded = false;

    for (Map<String, Object> item : added) {
      String kind = String.valueOf(item.get("kind"));
      String scopedName = String.valueOf(item.get("scopedName"));
      if ("ATTRIBUTE".equals(kind) && targetFqn.equals(scopedName)) {
        expectedAttributeCount++;
        continue;
      }
      if ("META_ATTRIBUTE".equals(kind)
          && targetFqn.equals(String.valueOf(item.get("owner")))) {
        String name = String.valueOf(item.get("name"));
        if (expectedMetaNames.contains(name)) {
          actualMetaNames.add(name);
          continue;
        }
      }
      unexpectedAdded = true;
    }

    boolean expectedDiff = expectedAttributeCount == 1
        && actualMetaNames.equals(expectedMetaNames)
        && !unexpectedAdded
        && removed.isEmpty()
        && changed.isEmpty();
    if (expectedDiff) {
      return response;
    }

    Map<String, Object> guarded = new LinkedHashMap<>(response);
    Object candidate = guarded.remove("updatedModelText");
    guarded.put("applied", false);
    guarded.put("status", IliModelChangeStatus.UNEXPECTED_SEMANTIC_CHANGE.name());
    guarded.put(
        "message",
        "The source edit compiled, but the semantic diff contains changes outside the requested ADD_ATTRIBUTE operation.");
    if (candidate != null) {
      guarded.put("candidateModelText", candidate);
    }
    return guarded;
  }

  private InsertionAnchor insertionAnchor(
      IliSourceDocument document,
      Table table,
      IliSourceLocator.BlockLocation block) {
    AttributeDef lastAttribute = lastDirectAttribute(table);
    if (lastAttribute != null) {
      IliSourceSpan span = sourceLocator.locateAttribute(
          document,
          block,
          lastAttribute.getName(),
          Math.max(1, lastAttribute.getSourceLine()));
      String indent = document.text().substring(
          document.lineStartOffset(span.startLine()),
          span.startOffset());
      return new InsertionAnchor(
          document.lineEndOffset(span.endLine()),
          indent,
          true);
    }

    Constraint firstConstraint = firstDirectConstraint(table);
    if (firstConstraint != null && firstConstraint.getSourceLine() > 0) {
      int line = firstConstraint.getSourceLine();
      return new InsertionAnchor(
          document.lineStartOffset(line),
          leadingWhitespace(document.lineText(line)),
          false);
    }

    int endLine = block.endMarkerSpan().startLine();
    String headerIndent = document.text().substring(
        document.lineStartOffset(block.headerSpan().startLine()),
        block.headerSpan().startOffset());
    return new InsertionAnchor(
        document.lineStartOffset(endLine),
        headerIndent + "  ",
        false);
  }

  private AttributeDef lastDirectAttribute(Table table) {
    AttributeDef last = null;
    Iterator<?> iterator = table.iterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof AttributeDef attribute) || attribute.getContainer() != table) {
        continue;
      }
      if (last == null || attribute.getSourceLine() > last.getSourceLine()) {
        last = attribute;
      }
    }
    return last;
  }

  private Constraint firstDirectConstraint(Table table) {
    Constraint first = null;
    Iterator<?> iterator = table.iterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof Constraint constraint) || constraint.getContainer() != table) {
        continue;
      }
      if (constraint.getSourceLine() <= 0) {
        continue;
      }
      if (first == null || constraint.getSourceLine() < first.getSourceLine()) {
        first = constraint;
      }
    }
    return first;
  }

  private AttributeDef findAttributeInHierarchy(Table table, String name) {
    Element current = table;
    while (current instanceof Table currentTable) {
      Iterator<?> iterator = currentTable.iterator();
      while (iterator.hasNext()) {
        Object item = iterator.next();
        if (item instanceof AttributeDef attribute
            && attribute.getContainer() == currentTable
            && name.equals(attribute.getName())) {
          return attribute;
        }
      }
      current = currentTable.getExtending();
    }
    return null;
  }

  private boolean isFromLastFile(TransferDescription td, Element element) {
    Element current = element;
    while (current != null && !(current instanceof Model)) {
      current = current.getContainer();
    }
    if (!(current instanceof Model owner)) {
      return false;
    }
    for (Model model : td.getModelsFromLastFile()) {
      if (model == owner || Objects.equals(model.getScopedName(), owner.getScopedName())) {
        return true;
      }
    }
    return false;
  }

  private String indentSnippet(String snippet, String indent, String lineSeparator) {
    String normalized = snippet.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = normalized.split("\n", -1);
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) {
        result.append(lineSeparator);
      }
      result.append(indent).append(lines[i]);
    }
    return result.toString();
  }

  private String leadingWhitespace(String line) {
    int index = 0;
    while (index < line.length()) {
      char c = line.charAt(index);
      if (c != ' ' && c != '\t') {
        break;
      }
      index++;
    }
    return line.substring(0, index);
  }

  private Map<String, Object> operationFailure(
      IliModelChangeStatus status,
      String targetFqn,
      String message,
      IliCompilerService.CompilationResult beforeCompilation) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("applied", false);
    response.put("status", status.name());
    response.put("operation", IliModelChangeOperation.ADD_ATTRIBUTE.name());
    response.put("targetFqn", targetFqn);
    response.put("beforeCompilerValid", beforeCompilation.valid());
    response.put("beforeDiagnostics", beforeCompilation.messages());
    response.put("message", message);
    return response;
  }

  private Map<String, Object> withTarget(Map<String, Object> response, String targetFqn) {
    Map<String, Object> copy = new LinkedHashMap<>(response);
    copy.put("targetFqn", targetFqn);
    return copy;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> mapList(@Nullable Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        result.add((Map<String, Object>) map);
      }
    }
    return result;
  }

  private Map<String, Object> beforeModelInvalidResponse(
      IliModelChangeOperation operation,
      IliCompilerService.CompilationResult beforeCompilation) {
    return Map.of(
        "applied", false,
        "status", IliModelChangeStatus.BEFORE_MODEL_INVALID.name(),
        "operation", operation.name(),
        "beforeCompilerValid", false,
        "beforeDiagnostics", beforeCompilation.messages());
  }

  private Map<String, Object> noChangeResponse(
      IliModelChangeOperation operation,
      IliCompilerService.CompilationResult beforeCompilation,
      List<Map<String, Object>> sourceEdits) {
    return Map.ofEntries(
        Map.entry("applied", false),
        Map.entry("status", IliModelChangeStatus.NO_CHANGE.name()),
        Map.entry("operation", operation.name()),
        Map.entry("beforeCompilerValid", true),
        Map.entry("beforeDiagnostics", beforeCompilation.messages()),
        Map.entry("hasChanges", false),
        Map.entry("added", List.of()),
        Map.entry("removed", List.of()),
        Map.entry("changed", List.of()),
        Map.entry("potentiallyBreakingChanges", List.of()),
        Map.entry("impact", "NONE"),
        Map.entry("sourceEdits", sourceEdits));
  }

  private void requireModelText(String modelText) {
    if (modelText == null || modelText.isBlank()) {
      throw new IllegalArgumentException("Model text is required.");
    }
  }

  private record InsertionAnchor(
      int offset,
      String indent,
      boolean afterExistingLine) {}

  record PreparedChange(
      String updatedModelText,
      List<Map<String, Object>> sourceEdits) {
    PreparedChange {
      if (updatedModelText == null || updatedModelText.isBlank()) {
        throw new IllegalArgumentException("updatedModelText is required.");
      }
      sourceEdits = sourceEdits == null ? List.of() : List.copyOf(sourceEdits);
    }
  }
}
