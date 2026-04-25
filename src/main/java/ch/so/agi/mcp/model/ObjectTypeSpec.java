package ch.so.agi.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ObjectTypeSpec {

  @Pattern(regexp = "^([A-Za-z][A-Za-z0-9_]*)(\\.[A-Za-z][A-Za-z0-9_]*)*$", message = "FQN must be dot-separated identifiers")
  @JsonProperty(required = true)
  private String targetClassFqn;

  @JsonProperty(required = false)
  private Boolean objects;

  public String getTargetClassFqn() {
    return targetClassFqn;
  }

  public void setTargetClassFqn(String targetClassFqn) {
    this.targetClassFqn = targetClassFqn;
  }

  public Boolean getObjects() {
    return objects;
  }

  public void setObjects(Boolean objects) {
    this.objects = objects;
  }

  public void validate() {
    if (targetClassFqn == null || targetClassFqn.isBlank()) {
      throw new IllegalArgumentException("objectType.targetClassFqn is required.");
    }
  }
}
