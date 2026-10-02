package ch.so.agi.mcp.change;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class RemoveAttributeChange {
  @JsonProperty(required = true) public String attributeFqn;
}
