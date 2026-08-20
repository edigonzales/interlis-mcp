package ch.so.agi.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TypeSpec {

  private static final String TYPE_SELECTION_ERROR =
      "typeSpec must define exactly one of 'domainFqn', 'structureFqn', 'baseType', 'referenceType', 'blackboxType', 'enumTreeValueType', 'basketType', 'objectType' or 'metaobjectType'.";

  @Pattern(regexp = "^([A-Za-z][A-Za-z0-9_]*)(\\.[A-Za-z][A-Za-z0-9_]*)*$", message = "FQN must be dot-separated identifiers")
  @JsonProperty(required = false)
  private String domainFqn;
  @Pattern(regexp = "^([A-Za-z][A-Za-z0-9_]*)(\\.[A-Za-z][A-Za-z0-9_]*)*$", message = "FQN must be dot-separated identifiers")
  @JsonProperty(required = false)
  private String structureFqn;
  @JsonProperty(required = false)
  private BaseType baseType;
  @JsonProperty(required = false)
  private ReferenceTypeSpec referenceType;
  @JsonProperty(required = false)
  private BlackboxTypeSpec blackboxType;
  @JsonProperty(required = false)
  private EnumTreeValueTypeSpec enumTreeValueType;
  @JsonProperty(required = false)
  private BasketTypeSpec basketType;
  @JsonProperty(required = false)
  private ObjectTypeSpec objectType;
  @JsonProperty(required = false)
  private MetaobjectTypeSpec metaobjectType;

  public String getDomainFqn() { return domainFqn; }
  public void setDomainFqn(String domainFqn) { this.domainFqn = domainFqn; }

  public String getStructureFqn() { return structureFqn; }
  public void setStructureFqn(String structureFqn) { this.structureFqn = structureFqn; }

  public BaseType getBaseType() { return baseType; }
  public void setBaseType(BaseType baseType) { this.baseType = baseType; }

  public ReferenceTypeSpec getReferenceType() { return referenceType; }
  public void setReferenceType(ReferenceTypeSpec referenceType) { this.referenceType = referenceType; }

  public BlackboxTypeSpec getBlackboxType() { return blackboxType; }
  public void setBlackboxType(BlackboxTypeSpec blackboxType) { this.blackboxType = blackboxType; }

  public EnumTreeValueTypeSpec getEnumTreeValueType() { return enumTreeValueType; }
  public void setEnumTreeValueType(EnumTreeValueTypeSpec enumTreeValueType) { this.enumTreeValueType = enumTreeValueType; }

  public BasketTypeSpec getBasketType() { return basketType; }
  public void setBasketType(BasketTypeSpec basketType) { this.basketType = basketType; }

  public ObjectTypeSpec getObjectType() { return objectType; }
  public void setObjectType(ObjectTypeSpec objectType) { this.objectType = objectType; }

  public MetaobjectTypeSpec getMetaobjectType() { return metaobjectType; }
  public void setMetaobjectType(MetaobjectTypeSpec metaobjectType) { this.metaobjectType = metaobjectType; }

  public Object requireSingleType() {
    Object selected = null;
    Object[] candidates = {
        domainFqn != null && !domainFqn.isBlank() ? new NamedType(domainFqn, false) : null,
        structureFqn != null && !structureFqn.isBlank() ? new NamedType(structureFqn, true) : null,
        baseType,
        referenceType,
        blackboxType,
        enumTreeValueType,
        basketType,
        objectType,
        metaobjectType
    };

    for (Object candidate : candidates) {
      if (candidate == null) {
        continue;
      }
      if (selected != null) {
        throw new IllegalArgumentException(TYPE_SELECTION_ERROR);
      }
      selected = candidate;
    }

    if (selected == null) {
      throw new IllegalArgumentException(TYPE_SELECTION_ERROR);
    }
    return selected;
  }

  public void validateOneOf() {
    requireSingleType();
  }

  public record NamedType(String fqn, boolean structure) {
  }
}
