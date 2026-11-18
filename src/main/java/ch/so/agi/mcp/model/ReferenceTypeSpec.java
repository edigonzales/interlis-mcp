package ch.so.agi.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReferenceTypeSpec {

  @Pattern(regexp = "^([A-Za-z][A-Za-z0-9_]*)(\\.[A-Za-z][A-Za-z0-9_]*)*$", message = "FQN must be dot-separated identifiers")
  @JsonProperty(required = true)
  private String targetClassFqn;

  @JsonProperty(required = false)
  private Boolean external;

  public String getTargetClassFqn() {
    return targetClassFqn;
  }

  public void setTargetClassFqn(String targetClassFqn) {
    this.targetClassFqn = targetClassFqn;
  }

  public Boolean getExternal() {
    return external;
  }

  public void setExternal(Boolean external) {
    this.external = external;
  }

  public void validate() {
    if (targetClassFqn == null || targetClassFqn.isBlank()) {
      throw new IllegalArgumentException("referenceType.targetClassFqn is required.");
    }
  }
}
