package ch.so.agi.mcp.tools;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import ch.so.agi.mcp.util.NameValidator;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ModelTools {

  private final Clock clock;
  public ModelTools(Clock clock) { this.clock = clock; }

  @McpTool(
      name = "createModelSnippet",
      description = "Erzeugt ein INTERLIS-2 Modellgerüst. Params: name (required), lang (default 'de'), version (default 'today'), uri (default 'https://example.org/<name>'), imports (default ['INTERLIS'])."
  )
  public Map<String, Object> createModelSnippet(
      @McpToolParam(description = "Modellname (Bezeichner ohne Leerzeichen)", required = true) String name,
      @McpToolParam(description = "Sprachcode, z. B. 'de' oder 'en'") @Nullable String lang,
      @McpToolParam(description = "URI des Modells") @Nullable String uri,
      @McpToolParam(description = "Version im Format YYYY-MM-DD") @Nullable String version,
      @McpToolParam(description = "Zusätzliche Imports (z. B. 'GeometryCHLV95_V1')") @Nullable List<String> imports
  ) {
      
      var nv = NameValidator.ascii();
      if (imports != null) {
        for (String m : imports) {
          nv.validateIdent(m, "Import model name");
        }
      }
      
    String _lang = (lang == null || lang.isBlank()) ? "de" : lang.trim();
    String _version = (version == null || version.isBlank()) ? LocalDate.now(clock).toString() : version.trim();
    String _uri = (uri == null || uri.isBlank()) ? ("https://example.org/" + name.toLowerCase()) : uri.trim();
    String _imports = (imports == null || imports.isEmpty())
        ? "INTERLIS"
        : imports.stream().map(String::trim).collect(Collectors.joining(", "));

    String snippet = String.format(
        "MODEL %s (%s) AT \"%s\" VERSION \"%s\" =\n" +
        "  IMPORTS UNQUALIFIED %s;\n\n" +
        "END %s.\n",
        name, _lang, _uri, _version, _imports, name);

    return Map.of(
        "iliSnippet", snippet,
        "cursorHint", Map.of("line", 2, "col", 0)
    );
  }

}
