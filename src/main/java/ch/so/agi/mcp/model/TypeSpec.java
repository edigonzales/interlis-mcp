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

  public void validateOneOf() {
    boolean hasDomain = domainFqn != null && !domainFqn.isBlank();
    boolean hasBase = baseType != null;
    boolean hasReference = referenceType != null;
    boolean hasBlackbox = blackboxType != null;
    boolean hasEnumTreeValue = enumTreeValueType != null;
    boolean hasBasket = basketType != null;
    boolean hasObject = objectType != null;
    boolean hasMetaobject = metaobjectType != null;

    int populated = 0;
    populated += hasDomain ? 1 : 0;
    populated += hasBase ? 1 : 0;
    populated += hasReference ? 1 : 0;
    populated += hasBlackbox ? 1 : 0;
    populated += hasEnumTreeValue ? 1 : 0;
    populated += hasBasket ? 1 : 0;
    populated += hasObject ? 1 : 0;
    populated += hasMetaobject ? 1 : 0;

    if (populated != 1) {
      throw new IllegalArgumentException(
          "typeSpec must define exactly one of 'domainFqn', 'baseType', 'referenceType', 'blackboxType', 'enumTreeValueType', 'basketType', 'objectType' or 'metaobjectType'.");
    }
  }
}
