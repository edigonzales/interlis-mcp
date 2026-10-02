package ch.so.agi.mcp.util;

import ch.so.agi.mcp.model.MetaAttributeSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class AnnotationRenderer {

  private AnnotationRenderer() {
  }

  public static String renderAnnotations(@Nullable String iliDoc, @Nullable List<MetaAttributeSpec> metaAttributes) {
    List<String> lines = new ArrayList<>();

    String renderedDoc = renderIliDoc(iliDoc);
    if (!renderedDoc.isEmpty()) {
      lines.add(renderedDoc);
    }

    for (MetaAttributeSpec metaAttribute : normalizeMetaAttributes(metaAttributes, false, true)) {
      String rendered = renderMetaAttribute(metaAttribute);
      if (!rendered.isEmpty()) {
        lines.add(rendered);
      }
    }

    if (lines.isEmpty()) {
      return "";
    }

    return String.join("\n", lines) + "\n";
  }

  public static String indentBlock(String text, String indent) {
    if (text == null || text.isEmpty()) {
      return "";
    }

    return splitLines(text).stream()
        .map(line -> indent + line)
        .reduce((left, right) -> left + "\n" + right)
        .orElse("");
  }

  public static List<MetaAttributeSpec> mergeMetaAttributes(
      @Nullable List<MetaAttributeSpec> defaults,
      @Nullable List<MetaAttributeSpec> overrides) {
    Map<String, MetaAttributeSpec> merged = new LinkedHashMap<>();
    for (MetaAttributeSpec item : normalizeMetaAttributes(defaults, false, true)) {
      merged.put(item.getName(), item);
    }
    for (MetaAttributeSpec item : normalizeMetaAttributes(overrides, false, true)) {
      merged.put(item.getName(), item);
    }
    return new ArrayList<>(merged.values());
  }

  public static String renderMetaAttributeBlock(
      @Nullable List<MetaAttributeSpec> metaAttributes,
      boolean requireNonEmpty,
      boolean rejectDuplicates) {
    List<MetaAttributeSpec> normalized = normalizeMetaAttributes(metaAttributes, requireNonEmpty, rejectDuplicates);
    if (normalized.isEmpty()) {
      return "";
    }

    return normalized.stream()
        .map(AnnotationRenderer::renderMetaAttribute)
        .reduce((left, right) -> left + "\n" + right)
        .orElse("");
  }

  public static List<MetaAttributeSpec> normalizeMetaAttributes(
      @Nullable List<MetaAttributeSpec> metaAttributes,
      boolean requireNonEmpty,
      boolean rejectDuplicates) {
    if (metaAttributes == null || metaAttributes.isEmpty()) {
      if (requireNonEmpty) {
        throw new IllegalArgumentException("At least one meta attribute is required.");
      }
      return List.of();
    }

    List<MetaAttributeSpec> normalized = new ArrayList<>();
    Map<String, Boolean> seenNames = rejectDuplicates ? new LinkedHashMap<>() : null;
    for (MetaAttributeSpec metaAttribute : metaAttributes) {
      if (metaAttribute == null) {
        throw new IllegalArgumentException("Meta attribute entry must not be null.");
      }
      String name = metaAttribute.getName();
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("Meta attribute name is required.");
      }
      NameValidator.ascii().validateFqn(name.trim(), "Meta attribute name");

      boolean hasValue = metaAttribute.getValue() != null;
      boolean hasRawValue = metaAttribute.getRawValue() != null;
      if (hasValue == hasRawValue) {
        throw new IllegalArgumentException(
            "Meta attribute '" + name.trim() + "' must define exactly one of 'value' or 'rawValue'.");
      }

      MetaAttributeSpec copy = new MetaAttributeSpec();
      copy.setName(name.trim());
      copy.setValue(metaAttribute.getValue());
      copy.setRawValue(metaAttribute.getRawValue());
      if (seenNames != null && seenNames.put(copy.getName(), Boolean.TRUE) != null) {
        throw new IllegalArgumentException("Duplicate meta attribute '" + copy.getName() + "' is not allowed.");
      }
      normalized.add(copy);
    }
    return normalized;
  }

  private static String renderIliDoc(@Nullable String iliDoc) {
    if (iliDoc == null || iliDoc.isBlank()) {
      return "";
    }

    List<String> lines = splitLines(iliDoc.strip());
    if (lines.size() == 1) {
      return "/** " + lines.getFirst() + " */";
    }

    StringBuilder sb = new StringBuilder();
    sb.append("/** ").append(lines.getFirst()).append("\n");
    for (int i = 1; i < lines.size(); i++) {
      sb.append(" ").append(lines.get(i)).append("\n");
    }
    sb.append(" */");
    return sb.toString();
  }

  private static String renderMetaAttribute(MetaAttributeSpec metaAttribute) {
    String value = metaAttribute.getValue() != null
        ? quote(metaAttribute.getValue())
        : metaAttribute.getRawValue().trim();
    return "!!@ " + metaAttribute.getName() + "=" + value;
  }

  private static String quote(String value) {
    String escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n");
    return "\"" + escaped + "\"";
  }

  private static List<String> splitLines(String text) {
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
    return normalized.lines().toList();
  }
}
