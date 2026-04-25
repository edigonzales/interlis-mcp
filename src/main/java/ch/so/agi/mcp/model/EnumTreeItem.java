package ch.so.agi.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnumTreeItem {

  @JsonProperty(required = true)
  private String name;

  @JsonProperty(required = false)
  private String iliDoc;

  @JsonProperty(required = false)
  private List<MetaAttributeSpec> metaAttributes;

  @JsonProperty(required = false)
  private List<EnumTreeItem> children;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getIliDoc() {
    return iliDoc;
  }

  public void setIliDoc(String iliDoc) {
    this.iliDoc = iliDoc;
  }

  public List<MetaAttributeSpec> getMetaAttributes() {
    return metaAttributes;
  }

  public void setMetaAttributes(List<MetaAttributeSpec> metaAttributes) {
    this.metaAttributes = metaAttributes;
  }

  public List<EnumTreeItem> getChildren() {
    return children;
  }

  public void setChildren(List<EnumTreeItem> children) {
    this.children = children;
  }
}
