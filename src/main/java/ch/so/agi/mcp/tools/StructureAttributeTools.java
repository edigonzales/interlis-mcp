package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.util.AnnotationRenderer;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class StructureAttributeTools {

  public enum Collection {
    NONE, LIST_OF, BAG_OF
  }

  /**
   * Helper to emit an attribute line that references a STRUCTURE type.
   * Examples:
   *  - address : Demo.Core.Address;
   *  - addresses : LIST OF Demo.Core.Address;
   *  - kontakt : MANDATORY Demo.Core.Contact;
   */
  @McpTool(
      name = "createStructureAttributeLine",
      description = "Create an attribute of STRUCTURE type. " +
                    "Params: name (required), structureFqn (required), " +
                    "mandatory (default false), collection (NONE|LIST_OF|BAG_OF, default NONE), iliDoc, metaAttributes."
  )
  public Map<String, Object> createStructureAttributeLine(
      @McpToolParam(description = "Attribute name", required = true) String name,
      @McpToolParam(description = "Fully-qualified STRUCTURE name, e.g., 'Demo.Core.Address'", required = true) String structureFqn,
      @McpToolParam(description = "MANDATORY flag (default false)", required = false) @Nullable Boolean mandatory,
      @McpToolParam(description = "Collection kind (NONE|LIST_OF|BAG_OF)", required = false) @Nullable Collection collection,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor dem Attribut", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor dem Attribut", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Attribute 'name' is required.");
    }
    if (structureFqn == null || structureFqn.isBlank()) {
      throw new IllegalArgumentException("'structureFqn' is required (e.g., 'Demo.Core.Address').");
    }

    String prefix = Boolean.TRUE.equals(mandatory) ? "MANDATORY " : "";
    Collection col = (collection == null) ? Collection.NONE : collection;
    String colPrefix = switch (col) {
      case NONE -> "";
      case LIST_OF -> "LIST OF ";
      case BAG_OF -> "BAG OF ";
    };

    String line = name.trim() + " : " + prefix + colPrefix + structureFqn.trim() + ";";
    return Map.of(
        "iliSnippet", AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes) + line
    );
  }
}
