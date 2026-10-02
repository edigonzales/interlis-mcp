package ch.so.agi.mcp.change;

import ch.so.agi.mcp.model.AttributeLineRequest;
import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.model.TypeSpec;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateAttributeChange {
  public enum IliDocAction { KEEP, SET, REMOVE }
  public enum MetaAttributesAction { KEEP, REPLACE, REMOVE }

  @JsonProperty(required = true) public String attributeFqn;
  @JsonProperty(required = true) public AttributePatch patch;

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class AttributePatch {
    @JsonProperty(required = false) public @Nullable Boolean mandatory;
    @JsonProperty(required = false) public AttributeLineRequest.Collection collection;
    @JsonProperty(required = false) public @Nullable TypeSpec typeSpec;
    @JsonProperty(required = false) public @Nullable IliDocAction iliDocAction;
    @JsonProperty(required = false) public @Nullable String iliDoc;
    @JsonProperty(required = false) public @Nullable MetaAttributesAction metaAttributesAction;
    @JsonProperty(required = false) public @Nullable List<MetaAttributeSpec> metaAttributes;

    public void validate() {
      boolean hasChange = mandatory != null || collection != null || typeSpec != null
          || (iliDocAction != null && iliDocAction != IliDocAction.KEEP)
          || (metaAttributesAction != null && metaAttributesAction != MetaAttributesAction.KEEP);
      if (!hasChange) {
        throw new IllegalArgumentException("UPDATE_ATTRIBUTE patch must contain at least one change.");
      }
      IliDocAction docAction = iliDocAction == null ? IliDocAction.KEEP : iliDocAction;
      if (docAction == IliDocAction.SET && (iliDoc == null || iliDoc.isBlank())) {
        throw new IllegalArgumentException("iliDoc is required when iliDocAction=SET.");
      }
      if (docAction != IliDocAction.SET && iliDoc != null) {
        throw new IllegalArgumentException("iliDoc is allowed only when iliDocAction=SET.");
      }
      MetaAttributesAction metaAction = metaAttributesAction == null
          ? MetaAttributesAction.KEEP : metaAttributesAction;
      if (metaAction == MetaAttributesAction.REPLACE
          && (metaAttributes == null || metaAttributes.isEmpty())) {
        throw new IllegalArgumentException(
            "metaAttributes is required when metaAttributesAction=REPLACE.");
      }
      if (metaAction != MetaAttributesAction.REPLACE && metaAttributes != null) {
        throw new IllegalArgumentException(
            "metaAttributes is allowed only when metaAttributesAction=REPLACE.");
      }
    }
  }
}
