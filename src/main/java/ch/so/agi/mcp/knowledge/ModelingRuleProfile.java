package ch.so.agi.mcp.knowledge;

import org.jspecify.annotations.Nullable;

public enum ModelingRuleProfile {
  CORE,
  SO;

  public static ModelingRuleProfile normalize(@Nullable ModelingRuleProfile profile) {
    return profile == null ? CORE : profile;
  }
}
