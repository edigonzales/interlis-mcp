package ch.so.agi.mcp.analysis;

import org.jspecify.annotations.Nullable;

public enum ModelPurpose {
  ANY,
  CAPTURE,
  PUBLICATION,
  VALIDATION,
  UNKNOWN;

  public static ModelPurpose normalize(@Nullable ModelPurpose purpose) {
    return purpose == null ? UNKNOWN : purpose;
  }
}

