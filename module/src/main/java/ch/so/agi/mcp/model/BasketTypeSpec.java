package ch.so.agi.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BasketTypeSpec {

  public enum Kind { DATA, VIEW, BASE, GRAPHIC }

  @JsonProperty(required = false)
  private Kind kind;

  @Pattern(regexp = "^([A-Za-z][A-Za-z0-9_]*)(\\.[A-Za-z][A-Za-z0-9_]*)*$", message = "FQN must be dot-separated identifiers")
  @JsonProperty(required = true)
  private String topicFqn;

  public Kind getKind() {
    return kind;
  }

  public void setKind(Kind kind) {
    this.kind = kind;
  }

  public String getTopicFqn() {
    return topicFqn;
  }

  public void setTopicFqn(String topicFqn) {
    this.topicFqn = topicFqn;
  }

  public void validate() {
    if (topicFqn == null || topicFqn.isBlank()) {
      throw new IllegalArgumentException("basketType.topicFqn is required.");
    }
  }
}
