package ch.so.agi.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MetaobjectTypeSpec {

  @Pattern(regexp = "^([A-Za-z][A-Za-z0-9_]*)(\\.[A-Za-z][A-Za-z0-9_]*)*$", message = "FQN must be dot-separated identifiers")
  @JsonProperty(required = true)
  private String tableFqn;

  public String getTableFqn() {
    return tableFqn;
  }

  public void setTableFqn(String tableFqn) {
    this.tableFqn = tableFqn;
  }

  public void validate() {
    if (tableFqn == null || tableFqn.isBlank()) {
      throw new IllegalArgumentException("metaobjectType.tableFqn is required.");
    }
  }
}
