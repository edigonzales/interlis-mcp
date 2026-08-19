package ch.so.agi.mcp.change;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class IliModelChangeRequest {

  @JsonProperty(required = true)
  private IliModelChangeOperation operation;

  @JsonProperty(required = false)
  private AddAttributeChange addAttribute;

  public IliModelChangeOperation getOperation() {
    return operation;
  }

  public void setOperation(IliModelChangeOperation operation) {
    this.operation = operation;
  }

  public AddAttributeChange getAddAttribute() {
    return addAttribute;
  }

  public void setAddAttribute(AddAttributeChange addAttribute) {
    this.addAttribute = addAttribute;
  }

  public IliModelChangeOperation requireOperation() {
    if (operation == null) {
      throw new IllegalArgumentException("Change operation is required.");
    }
    return operation;
  }

  public AddAttributeChange requireAddAttribute() {
    if (addAttribute == null) {
      throw new IllegalArgumentException("addAttribute payload is required for ADD_ATTRIBUTE.");
    }
    return addAttribute;
  }
}
