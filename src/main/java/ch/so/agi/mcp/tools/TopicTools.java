package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.util.AnnotationRenderer;
import ch.so.agi.mcp.util.NameValidator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class TopicTools {

  public Map<String,Object> createTopic(
      @McpToolParam(description = "Topic-Name", required = true) String name,
      @McpToolParam(description = "OID-Definition, z. B. 'OID AS INTERLIS.UUIDOID'", required = false) @Nullable String oidType,
      @McpToolParam(description = "Abstrakter Topic?", required = false) @Nullable Boolean isAbstract,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor dem TOPIC", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor dem TOPIC", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
      var nv = NameValidator.ascii(); 
      nv.validateIdent(name, "Topic name");

    boolean abs = isAbstract != null && isAbstract;
    String header = abs ? String.format("TOPIC %s (ABSTRACT) =", name) : String.format("TOPIC %s =", name);
    String oid = (oidType != null && !oidType.isBlank()) ? "  " + oidType.trim() + ";\n" : "";
    String snippet = AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes)
        + header + "\n" + oid + "END " + name + ";";

    return Map.of("iliSnippet", snippet);
  }
}
