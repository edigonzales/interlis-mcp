package ch.so.agi.mcp.util;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class McpInputLimits {

  public static final int MAX_MODEL_BYTES = 2 * 1024 * 1024;
  public static final int MAX_XTF_BYTES = 20 * 1024 * 1024;
  public static final int MAX_CONSTRAINT_CASES = 100;

  private McpInputLimits() {}

  public static void requireModelText(String modelText) {
    requireText(modelText, "Model text", MAX_MODEL_BYTES);
  }

  public static void requireXtfText(String xtfText) {
    requireText(xtfText, "XTF text", MAX_XTF_BYTES);
  }

  public static void requireConstraintCases(List<?> cases) {
    if (cases == null || cases.isEmpty()) {
      throw new IllegalArgumentException("At least one explicit constraint test case is required.");
    }
    if (cases.size() > MAX_CONSTRAINT_CASES) {
      throw new IllegalArgumentException("At most " + MAX_CONSTRAINT_CASES + " constraint test cases are allowed.");
    }
  }

  private static void requireText(String text, String label, int maxBytes) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException(label + " is required.");
    }
    if (text.length() > maxBytes || text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
      throw new IllegalArgumentException(label + " exceeds the " + maxBytes + " byte limit.");
    }
  }
}
