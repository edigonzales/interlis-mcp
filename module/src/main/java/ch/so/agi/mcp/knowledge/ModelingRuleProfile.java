package ch.so.agi.mcp.knowledge;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum ModelingRuleProfile {
  CORE,
  SO,
  @JsonProperty("SO-DATA") SO_DATA;

  @JsonValue
  public String wireName() { return this == SO_DATA ? "SO-DATA" : name(); }

  @JsonCreator
  public static ModelingRuleProfile parse(String value) {
    return switch (value) { case "CORE" -> CORE; case "SO" -> SO; case "SO-DATA" -> SO_DATA; default -> throw new IllegalArgumentException("Unknown profile: " + value); };
  }

  @Override public String toString() { return wireName(); }

  public static ModelingRuleProfile normalize(@Nullable ModelingRuleProfile profile) {
    return profile == null ? CORE : profile;
  }
}
