package ch.so.agi.mcp.change;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class IliModelChangeRequest {

  @JsonProperty(required = true)
  private IliModelChangeOperation operation;

  public IliModelChangeOperation getOperation() {
    return operation;
  }

  public void setOperation(IliModelChangeOperation operation) {
    this.operation = operation;
  }

  public IliModelChangeOperation requireOperation() {
    if (operation == null) {
      throw new IllegalArgumentException("Change operation is required.");
    }
    return operation;
  }
}
