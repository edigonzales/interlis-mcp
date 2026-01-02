package ch.so.agi.mcp.tools;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import ch.so.agi.mcp.util.NameValidator;

import java.util.Map;

@Component
public class TopicTools {

  @McpTool(name = "createTopicSnippet",
        description = "Erzeugt einen TOPIC-Block. Params: name (required), oidType (e.g. 'OID AS UUIDOID'), isAbstract (default false).")
  public Map<String,Object> createTopic(
      @McpToolParam(description = "Topic-Name", required = true) String name,
      @McpToolParam(description = "OID-Definition, z. B. 'OID AS UUIDOID'", required = false) @Nullable String oidType,
      @McpToolParam(description = "Abstrakter Topic?", required = false) @Nullable Boolean isAbstract
  ) {
      var nv = NameValidator.ascii(); 
      nv.validateIdent(name, "Topic name");

    boolean abs = isAbstract != null && isAbstract;
    String header = abs ? String.format("TOPIC %s (ABSTRACT) =", name) : String.format("TOPIC %s =", name);
    String oid = (oidType != null && !oidType.isBlank()) ? "  " + oidType.trim() + ";\n" : "";
    String snippet = header + "\n" + oid + "  !! Klassen/Assoziationen hier\nEND " + name + ";";

    return Map.of("iliSnippet", snippet, "cursorHint", Map.of("line", 1, "col", 2));
  }
}
