package ch.so.agi.mcp.change;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class IliPatchApplier {

  private IliPatchApplier() {}

  public static String apply(IliSourceDocument document, List<IliTextPatch> patches) {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(patches, "patches");
    if (patches.isEmpty()) {
      return document.text();
    }

    List<IliTextPatch> ordered = new ArrayList<>(patches);
    for (IliTextPatch patch : ordered) {
      Objects.requireNonNull(patch, "patch");
      if (patch.span().endOffset() > document.length()) {
        throw new IllegalArgumentException("Patch span exceeds document length: " + patch.description());
      }
    }

    ordered.sort(Comparator.comparingInt(patch -> patch.span().startOffset()));
    for (int i = 1; i < ordered.size(); i++) {
      IliSourceSpan previous = ordered.get(i - 1).span();
      IliSourceSpan current = ordered.get(i).span();
      if (current.startOffset() < previous.endOffset()
          || current.startOffset() == previous.startOffset()) {
        throw new IllegalArgumentException("Patches overlap or have an ambiguous shared start offset.");
      }
    }

    ordered.sort(Comparator.comparingInt((IliTextPatch patch) -> patch.span().startOffset()).reversed());
    StringBuilder result = new StringBuilder(document.text());
    for (IliTextPatch patch : ordered) {
      result.replace(patch.span().startOffset(), patch.span().endOffset(), patch.replacement());
    }
    return result.toString();
  }
}
