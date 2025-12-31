package ch.so.agi.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AttributeLineRequest {

  public enum Collection { NONE, LIST_OF, BAG_OF }

  @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$", message = "Identifier must match ^[A-Za-z][A-Za-z0-9_]*$")
  @JsonProperty(required = true)
  private String name;
  @JsonProperty(required = false)
  private Boolean mandatory;
  @JsonProperty(required = false)
  private Collection collection;
  @JsonProperty(required = true)
  private TypeSpec typeSpec;

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  public Boolean getMandatory() { return mandatory; }
  public void setMandatory(Boolean mandatory) { this.mandatory = mandatory; }

  public Collection getCollection() { return collection; }
  public void setCollection(Collection collection) { this.collection = collection; }

  public TypeSpec getTypeSpec() { return typeSpec; }
  public void setTypeSpec(TypeSpec typeSpec) { this.typeSpec = typeSpec; }
}
