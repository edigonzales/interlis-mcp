package ch.so.agi.mcp.change;

import java.util.Objects;

public record IliTextPatch(
    IliSourceSpan span,
    String replacement,
    String description) {

  public IliTextPatch {
    Objects.requireNonNull(span, "span");
    Objects.requireNonNull(replacement, "replacement");
    description = description == null ? "" : description;
  }

  public static IliTextPatch replace(
      IliSourceSpan span,
      String replacement,
      String description) {
    return new IliTextPatch(span, replacement, description);
  }

  public static IliTextPatch insert(
      IliSourceDocument document,
      int offset,
      String text,
      String description) {
    Objects.requireNonNull(document, "document");
    return new IliTextPatch(document.insertionPoint(offset), text, description);
  }
}
