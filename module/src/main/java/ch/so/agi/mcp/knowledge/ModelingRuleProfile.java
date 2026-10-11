package ch.so.agi.mcp.knowledge;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum ModelingRuleProfile {
  CORE,
  @JsonProperty("SO-GIS") SO_GIS,
  @JsonProperty("SO-DATA") SO_DATA;

  @JsonValue
  public String wireName() { return switch (this) { case SO_GIS -> "SO-GIS"; case SO_DATA -> "SO-DATA"; default -> name(); }; }

  @JsonCreator
  public static ModelingRuleProfile parse(String value) {
    return switch (value) { case "CORE" -> CORE; case "SO-GIS" -> SO_GIS; case "SO-DATA" -> SO_DATA; default -> throw new IllegalArgumentException("Unknown profile: " + value + "; use CORE, SO-GIS or SO-DATA"); };
  }

  @Override public String toString() { return wireName(); }

  public static ModelingRuleProfile normalize(@Nullable ModelingRuleProfile profile) {
    return profile == null ? CORE : profile;
  }
}
