package ch.so.agi.mcp.model;

/** Canonical enum spelling shared by authoring, decision tables and ordered AST comparison. */
public final class EnumLiteralValue {
  public static final String PATTERN = "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*";
  private EnumLiteralValue() {}

  public static String normalize(Object raw, String path) {
    if (raw == null) throw new SpecValidationException("MISSING_FIELD", path,
        "ENUM.value is required.", "Use an enum string, for example Drainage or #Drainage.");
    if (!(raw instanceof String text)) throw invalid(path);
    String value = text.trim();
    if (value.startsWith("#")) value = value.substring(1);
    if (!value.matches(PATTERN)) throw invalid(path);
    return value;
  }

  private static SpecValidationException invalid(String path) {
    return new SpecValidationException("INVALID_LITERAL", path,
        "ENUM.value must be a nonempty enum string with dot-separated names and at most one leading #.",
        "Use Drainage or #Drainage; association arrows and repeated # are not enum syntax.");
  }
}
