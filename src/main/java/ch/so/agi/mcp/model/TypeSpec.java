package ch.so.agi.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TypeSpec {

  @Pattern(regexp = "^([A-Za-z][A-Za-z0-9_]*)(\\.[A-Za-z][A-Za-z0-9_]*)*$", message = "FQN must be dot-separated identifiers")
  @JsonProperty(required = false)
  private String domainFqn;
  @JsonProperty(required = false)
  private BaseType baseType;

  public String getDomainFqn() { return domainFqn; }
  public void setDomainFqn(String domainFqn) { this.domainFqn = domainFqn; }

  public BaseType getBaseType() { return baseType; }
  public void setBaseType(BaseType baseType) { this.baseType = baseType; }

  public void validateOneOf() {
    boolean hasDomain = domainFqn != null && !domainFqn.isBlank();
    boolean hasBase = baseType != null;
    if (hasDomain == hasBase) {
      throw new IllegalArgumentException("typeSpec must have EITHER 'domainFqn' OR 'baseType'.");
    }
  }
}
