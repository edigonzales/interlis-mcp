package ch.so.agi.mcp.constraint;

import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.Topic;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.View;
import ch.interlis.ili2c.metamodel.Viewable;
import ch.so.agi.mcp.change.IliPatchApplier;
import ch.so.agi.mcp.change.IliSourceDocument;
import ch.so.agi.mcp.change.IliSourceLocator;
import ch.so.agi.mcp.change.IliTextPatch;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.util.NameValidator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Inserts constraints inside views or in external CONSTRAINTS OF blocks for other contexts. */
@Service
public class ConstraintSourceEditService {

  private final IliSourceLocator sourceLocator = new IliSourceLocator();

  public PreparedInsertion insertConstraint(
      String modelText,
      IliCompilerService.CompilationResult beforeCompilation,
      String contextFqn,
      String constraintText) {
    return insertConstraint(
        modelText, beforeCompilation, contextFqn, constraintText, Set.of());
  }

  public PreparedInsertion insertConstraint(
      String modelText,
      IliCompilerService.CompilationResult beforeCompilation,
      String contextFqn,
      String constraintText,
      Set<String> requiredImports) {
    if (modelText == null || modelText.isBlank()) {
      throw new IllegalArgumentException("modelText is required.");
    }
    Objects.requireNonNull(beforeCompilation, "beforeCompilation");
    if (!beforeCompilation.valid() || beforeCompilation.transferDescription() == null) {
      throw new IllegalArgumentException("Before model must compile before source insertion.");
    }
    if (contextFqn == null || contextFqn.isBlank()) {
      throw new IllegalArgumentException("contextFqn is required.");
    }
    if (constraintText == null || constraintText.isBlank()) {
      throw new IllegalArgumentException("constraintText is required.");
    }

    TransferDescription td = beforeCompilation.transferDescription();
    Element context = td.getElement(contextFqn.trim());
    if (!(context instanceof Viewable<?> viewable)) {
      throw new IllegalArgumentException("Constraint context is not a CLASS/STRUCTURE/ASSOCIATION/VIEW: " + contextFqn);
    }
    Model owner = (Model) context.getContainer(Model.class);
    if (owner == null || !belongsToLastFile(td, owner)) {
      throw new IllegalArgumentException("Constraint context is defined in an imported model: " + contextFqn);
    }

    IliSourceDocument document = IliSourceDocument.of(modelText);
    List<IliTextPatch> patches = new ArrayList<>();
    patches.add(planConstraintInsertion(document, viewable, constraintText));

    LinkedHashSet<String> missingImports = missingImports(owner, requiredImports);
    if (!missingImports.isEmpty()) {
      IliSourceLocator.BlockLocation modelBlock = owner.getSourceLine() > 0
          ? sourceLocator.locateNamedBlock(
              document, IliSourceLocator.BlockKind.MODEL, owner.getName(), owner.getSourceLine())
          : sourceLocator.locateNamedBlock(
              document, IliSourceLocator.BlockKind.MODEL, owner.getName());
      patches.add(sourceLocator.insertImports(document, modelBlock, missingImports,
          "Add derived imports for constraint " + contextFqn.trim()));
    }

    String updatedModelText = IliPatchApplier.apply(document, patches);
    List<SourceEdit> sourceEdits = patches.stream().map(patch -> new SourceEdit(
        patch.span().startOffset(),
        patch.span().endOffset(),
        patch.span().startLine(),
        patch.span().endLine(),
        "",
        patch.replacement(),
        patch.description())).toList();
    return new PreparedInsertion(updatedModelText, sourceEdits);
  }

  /** Plans an insertion in the original document for a validated compiled context. */
  public IliTextPatch planConstraintInsertion(
      IliSourceDocument document, Viewable<?> context, String constraintText) {
    String contextFqn = context.getScopedName();
    Topic topic = (Topic) context.getContainer(Topic.class);
    if (topic == null) {
      throw new IllegalArgumentException("Constraint context is not contained in a TOPIC: " + contextFqn);
    }
    IliSourceLocator.BlockLocation topicBlock = topic.getSourceLine() > 0
        ? sourceLocator.locateNamedBlock(
            document,
            IliSourceLocator.BlockKind.TOPIC,
            topic.getName(),
            topic.getSourceLine())
        : sourceLocator.locateNamedBlock(
            document,
            IliSourceLocator.BlockKind.TOPIC,
            topic.getName());

    boolean inline = context instanceof View;
    IliSourceLocator.BlockLocation insertionBlock = inline
        ? sourceLocator.locateNamedBlock(document, IliSourceLocator.BlockKind.VIEW,
            context.getName(), context.getSourceLine(), topicBlock)
        : topicBlock;
    String fragment = inline ? constraintText
        : "CONSTRAINTS OF " + contextFqn + " =\n"
            + IliSourceDocument.indentLines(constraintText, "  ", "\n") + "\nEND;";
    return document.insertBeforeEnd(insertionBlock, fragment, !inline, "Insert constraint for " + contextFqn);
  }

  private LinkedHashSet<String> missingImports(
      Model owner, Set<String> requiredImports) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    if (requiredImports == null) return result;
    for (String requiredImport : requiredImports) {
      if (requiredImport == null || requiredImport.isBlank()) continue;
      String imported = requiredImport.trim();
      NameValidator.ascii().validateIdent(imported, "derived import");
      if (!owner.getName().equals(imported)
          && !"INTERLIS".equals(imported)
          && !hasImport(owner, imported)) {
        result.add(imported);
      }
    }
    return result;
  }

  private boolean hasImport(Model model, String imported) {
    for (Model dependency : model.getImporting()) {
      if (dependency != null && imported.equals(dependency.getName())) return true;
    }
    return false;
  }

  private boolean belongsToLastFile(TransferDescription td, Model owner) {
    for (Model model : td.getModelsFromLastFile()) {
      if (model == owner || Objects.equals(model.getScopedName(), owner.getScopedName())) {
        return true;
      }
    }
    return false;
  }

  public record SourceEdit(
      int startOffset,
      int endOffset,
      int startLine,
      int endLine,
      String before,
      String after,
      String description) {
  }

  public record PreparedInsertion(
      String updatedModelText,
      List<SourceEdit> sourceEdits) {
    public PreparedInsertion {
      if (updatedModelText == null || updatedModelText.isBlank()) {
        throw new IllegalArgumentException("updatedModelText is required.");
      }
      sourceEdits = List.copyOf(Objects.requireNonNull(sourceEdits, "sourceEdits"));
      if (sourceEdits.isEmpty()) {
        throw new IllegalArgumentException("sourceEdits are required.");
      }
    }

    public SourceEdit sourceEdit() {
      return sourceEdits.getFirst();
    }
  }
}
