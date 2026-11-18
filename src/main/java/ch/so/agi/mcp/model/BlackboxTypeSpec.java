package ch.so.agi.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BlackboxTypeSpec {

  public enum Kind { XML, BINARY }

  @JsonProperty(required = true)
  private Kind kind;

  public Kind getKind() {
    return kind;
  }

  public void setKind(Kind kind) {
    this.kind = kind;
  }

  public void validate() {
    if (kind == null) {
      throw new IllegalArgumentException("blackboxType.kind is required.");
    }
  }
}
