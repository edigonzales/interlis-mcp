package ch.so.agi.mcp.constraint;

import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.Topic;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Viewable;
import ch.so.agi.mcp.change.IliPatchApplier;
import ch.so.agi.mcp.change.IliSourceDocument;
import ch.so.agi.mcp.change.IliSourceLocator;
import ch.so.agi.mcp.change.IliTextPatch;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Source-preserving insertion of external CONSTRAINTS OF blocks into the owning topic. */
@Service
public class ConstraintSourceEditService {

  private final IliSourceLocator sourceLocator = new IliSourceLocator();

  public PreparedInsertion insertConstraintBlock(
      String modelText,
      IliCompilerService.CompilationResult beforeCompilation,
      String contextFqn,
      String constraintBlock) {
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
    if (constraintBlock == null || constraintBlock.isBlank()) {
      throw new IllegalArgumentException("constraintBlock is required.");
    }

    TransferDescription td = beforeCompilation.transferDescription();
    Element context = td.getElement(contextFqn.trim());
    if (!(context instanceof Viewable<?>)) {
      throw new IllegalArgumentException("Constraint context is not a CLASS/STRUCTURE/ASSOCIATION/VIEW: " + contextFqn);
    }
    if (!belongsToLastFile(td, context)) {
      throw new IllegalArgumentException("Constraint context is defined in an imported model: " + contextFqn);
    }

    Topic topic = containingTopic(context);
    if (topic == null) {
      throw new IllegalArgumentException("Constraint context is not contained in a TOPIC: " + contextFqn);
    }

    IliSourceDocument document = IliSourceDocument.of(modelText);
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

    int topicLineStart = document.lineStartOffset(topicBlock.headerSpan().startLine());
    String topicIndent = document.text().substring(topicLineStart, topicBlock.headerSpan().startOffset());
    String declarationIndent = topicIndent + "  ";
    String eol = document.lineSeparator();
    String indentedBlock = indentBlock(constraintBlock, declarationIndent, eol);
    int insertAt = document.lineStartOffset(topicBlock.endMarkerSpan().startLine());
    String insertion = indentedBlock + eol + eol;

    IliTextPatch patch = IliTextPatch.insert(
        document,
        insertAt,
        insertion,
        "Insert constraint block for " + contextFqn.trim());
    String updatedModelText = IliPatchApplier.apply(document, List.of(patch));
    SourceEdit sourceEdit = new SourceEdit(
        patch.span().startOffset(),
        patch.span().endOffset(),
        patch.span().startLine(),
        patch.span().endLine(),
        "",
        insertion,
        patch.description());
    return new PreparedInsertion(updatedModelText, sourceEdit);
  }

  private String indentBlock(String block, String indent, String eol) {
    String normalized = block.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = normalized.split("\n", -1);
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) {
        result.append(eol);
      }
      result.append(indent).append(lines[i]);
    }
    return result.toString();
  }

  private Topic containingTopic(Element element) {
    Element current = element;
    while (current != null) {
      if (current instanceof Topic topic) {
        return topic;
      }
      current = current.getContainer();
    }
    return null;
  }

  private boolean belongsToLastFile(TransferDescription td, Element element) {
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
      SourceEdit sourceEdit) {
    public PreparedInsertion {
      if (updatedModelText == null || updatedModelText.isBlank()) {
        throw new IllegalArgumentException("updatedModelText is required.");
      }
      Objects.requireNonNull(sourceEdit, "sourceEdit");
    }
  }
}
