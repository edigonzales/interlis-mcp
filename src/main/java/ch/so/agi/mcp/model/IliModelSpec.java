package ch.so.agi.mcp.model;

import ch.so.agi.mcp.tools.AssociationTools;
import ch.so.agi.mcp.tools.DomainTools;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Typed, complete input tree shared by full-model and change authoring. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IliModelSpec {

  @JsonProperty(required = true) public String name;
  @JsonProperty(required = true) public String uri;
  @JsonProperty(required = true) public String version;
  @JsonProperty(required = true) public String iliVersion;
  @JsonProperty(required = false) public @Nullable String language;
  @JsonProperty(required = false) public @Nullable String iliDoc;
  @JsonProperty(required = false) public @Nullable List<MetaAttributeSpec> metaAttributes;
  @JsonProperty(required = false) public @Nullable List<String> imports;
  @JsonProperty(required = false) public @Nullable List<UnitSpec> units;
  @JsonProperty(required = false) public @Nullable List<DomainSpec> domains;
  @JsonProperty(required = false) public @Nullable List<TopicSpec> topics;

  public static class AnnotatedSpec {
    @JsonProperty(required = true) public String name;
    @JsonProperty(required = false) public @Nullable String iliDoc;
    @JsonProperty(required = false) public @Nullable List<MetaAttributeSpec> metaAttributes;
  }

  public static class UnitSpec extends AnnotatedSpec {
    @JsonProperty(required = true) public BigDecimal factor;
    @JsonProperty(required = true) public String baseUnitFqn;
  }

  public enum DomainKind { NUMERIC, ENUM, ENUM_TREE, COORD }

  public static class DomainSpec extends AnnotatedSpec {
    @JsonProperty(required = true) public DomainKind kind;
    @JsonProperty(required = false) public @Nullable String min;
    @JsonProperty(required = false) public @Nullable String max;
    @JsonProperty(required = false) public @Nullable String unitFqn;
    @JsonProperty(required = false) public @Nullable List<EnumValueItem> enumItems;
    @JsonProperty(required = false) public @Nullable List<EnumTreeItem> enumTreeItems;
    @JsonProperty(required = false) public @Nullable List<DomainTools.CoordinateAxis> axes;
    @JsonProperty(required = false) public @Nullable Integer rotationFrom;
    @JsonProperty(required = false) public @Nullable Integer rotationTo;
  }

  public static class TopicSpec extends AnnotatedSpec {
    @JsonProperty(required = false) public @Nullable Boolean isAbstract;
    @JsonProperty(required = false) public @Nullable String oidDomainFqn;
    @JsonProperty(required = false) public @Nullable List<String> dependsOn;
    @JsonProperty(required = false) public @Nullable List<UnitSpec> units;
    @JsonProperty(required = false) public @Nullable List<DomainSpec> domains;
    @JsonProperty(required = false) public @Nullable List<StructureSpec> structures;
    @JsonProperty(required = false) public @Nullable List<ClassSpec> classes;
    @JsonProperty(required = false) public @Nullable List<AssociationSpec> associations;
  }

  public static class ViewableSpec extends AnnotatedSpec {
    @JsonProperty(required = false) public @Nullable Boolean isAbstract;
    @JsonProperty(required = false) public @Nullable String extendsFqn;
    @JsonProperty(required = false) public @Nullable List<AttributeLineRequest> attributes;
    @JsonProperty(required = false) public @Nullable List<IliConstraintSpec> constraints;
  }

  public static class ClassSpec extends ViewableSpec {
    @JsonProperty(required = false) public @Nullable String oidDomainFqn;
  }

  public static class StructureSpec extends ViewableSpec {}

  public static class AssociationSpec extends ViewableSpec {
    @JsonProperty(required = true) public List<AssociationRoleSpec> roles;
  }

  public static class AssociationRoleSpec {
    @JsonProperty(required = true) public String name;
    @JsonProperty(required = true) public String classFqn;
    @JsonProperty(required = true) public CardinalitySpec cardinality;
    @JsonProperty(required = false) public @Nullable Boolean external;
  }

  public static class CardinalitySpec {
    @JsonProperty(required = true) public Integer min;
    /** Non-negative integer or "*". */
    @JsonProperty(required = true) public String max;
  }

}
