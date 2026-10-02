package ch.so.agi.mcp.change;

import ch.so.agi.mcp.model.AttributeLineRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AddAttributeChange {

  @JsonProperty(required = true)
  private String containerFqn;

  @JsonProperty(required = true)
  private AttributeLineRequest attribute;

  public String getContainerFqn() {
    return containerFqn;
  }

  public void setContainerFqn(String containerFqn) {
    this.containerFqn = containerFqn;
  }

  public AttributeLineRequest getAttribute() {
    return attribute;
  }

  public void setAttribute(AttributeLineRequest attribute) {
    this.attribute = attribute;
  }

  public String requireContainerFqn() {
    if (containerFqn == null || containerFqn.isBlank()) {
      throw new IllegalArgumentException("addAttribute.containerFqn is required.");
    }
    return containerFqn.trim();
  }

  public AttributeLineRequest requireAttribute() {
    if (attribute == null) {
      throw new IllegalArgumentException("addAttribute.attribute is required.");
    }
    return attribute;
  }
}
