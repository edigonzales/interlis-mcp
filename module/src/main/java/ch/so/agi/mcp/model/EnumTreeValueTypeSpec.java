package ch.so.agi.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnumTreeValueTypeSpec {

  @Pattern(regexp = "^([A-Za-z][A-Za-z0-9_]*)(\\.[A-Za-z][A-Za-z0-9_]*)*$", message = "FQN must be dot-separated identifiers")
  @JsonProperty(required = true)
  private String enumTreeDomainFqn;

  public String getEnumTreeDomainFqn() {
    return enumTreeDomainFqn;
  }

  public void setEnumTreeDomainFqn(String enumTreeDomainFqn) {
    this.enumTreeDomainFqn = enumTreeDomainFqn;
  }

  public void validate() {
    if (enumTreeDomainFqn == null || enumTreeDomainFqn.isBlank()) {
      throw new IllegalArgumentException("enumTreeValueType.enumTreeDomainFqn is required.");
    }
  }
}
