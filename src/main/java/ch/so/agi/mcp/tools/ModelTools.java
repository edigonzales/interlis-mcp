package ch.so.agi.mcp.tools;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import ch.so.agi.mcp.util.NameValidator;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ModelTools {

  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
  private static final String DEFAULT_ILI_VERSION = "2.4";
  private static final DateTimeFormatter ISO_DAY = DateTimeFormatter.ISO_DATE;
  private static final String SOLOTHURN_BANNER_TEMPLATE =
      "/** !!------------------------------------------------------------------------------\n" +
      " * !! Version    | wer | Änderung\n" +
      " * !!------------------------------------------------------------------------------\n" +
      " * !! %s | abr  | Initalversion\n" +
      " * !!==============================================================================\n" +
      " */\n" +
      "!!@ technicalContact=mailto:agi@bd.so.ch\n" +
      "!!@ title=\"a title\"\n" +
      "!!@ shortDescription=\"a short description\"\n" +
      "!!@ tags=\"de:Gebäude,fr:Bâtiment,fubar\"\n";

  private final Clock clock;
  public ModelTools(Clock clock) { this.clock = clock; }

  @McpTool(
      name = "createModelSnippet",
      description = "Erzeugt ein INTERLIS-2 Modellgerüst. Params: name (required), lang (default 'de'), version (default 'today'), uri (default 'https://example.org/<name>'), iliVersion (default '2.4'), includeSolothurnHeader (default false), imports (default [])."
  )
  public Map<String, Object> createModelSnippet(
      @McpToolParam(description = "Modellname (Bezeichner ohne Leerzeichen)", required = true) String name,
      @McpToolParam(description = "Sprachcode, z. B. 'de' oder 'en'", required = false) @Nullable String lang,
      @McpToolParam(description = "URI des Modells", required = false) @Nullable String uri,
      @McpToolParam(description = "Version im Format YYYY-MM-DD", required = false) @Nullable String version,
      @McpToolParam(description = "INTERLIS Sprachversion (z. B. '2.3' oder '2.4')", required = false) @Nullable String iliVersion,
      @McpToolParam(description = "Zusätzliche Imports (z. B. 'GeometryCHLV95_V1')", required = false) @Nullable List<String> imports,
      @McpToolParam(description = "Fügt einen Solothurn-Header oberhalb des Snippets ein", required = false) @Nullable Boolean includeSolothurnHeader
  ) {
      
    List<String> trimmedImports = imports == null
        ? List.of()
        : imports.stream().map(String::trim).collect(Collectors.toList());
    var nv = NameValidator.ascii();
    for (String m : trimmedImports) {
      nv.validateIdent(m, "Import model name");
    }

    String _lang = (lang == null || lang.isBlank()) ? "de" : lang.trim();
    String _version = (version == null || version.isBlank()) ? LocalDate.now(clock).toString() : version.trim();
    String _uri = (uri == null || uri.isBlank()) ? ("https://example.org/" + name.toLowerCase()) : uri.trim();
    String _iliVersion = (iliVersion == null || iliVersion.isBlank()) ? DEFAULT_ILI_VERSION : iliVersion.trim();
    if (!"2.3".equals(_iliVersion) && !DEFAULT_ILI_VERSION.equals(_iliVersion)) {
      throw new IllegalArgumentException("iliVersion must be either '2.3' or '2.4'. Got: '" + _iliVersion + "'.");
    }
    String importLines = trimmedImports.stream()
        .map(model -> "  IMPORTS " + model + ";\n")
        .collect(Collectors.joining());

    String header = Boolean.TRUE.equals(includeSolothurnHeader) ? buildSolothurnBanner() : "";
    int headerLines = header.isEmpty() ? 0 : (int) header.lines().count();
    int cursorLine = headerLines + 4 + trimmedImports.size();

    String snippet = header +
        String.format(
            "INTERLIS %s;\n\n" +
            "MODEL %s (%s) AT \"%s\" VERSION \"%s\" =\n" +
            "%s\n" +
            "END %s.\n",
            _iliVersion, name, _lang, _uri, _version, importLines, name);

    return Map.of(
        "iliSnippet", snippet,
        "cursorHint", Map.of("line", cursorLine, "col", 0)
    );
  }

  @McpTool(
      name = "createImportLine",
      description = "Erzeugt eine einzelne IMPORTS-Zeile. Params: modelName (required), qualified (default true)."
  )
  public String createImportLine(
      @McpToolParam(description = "Modellname (Bezeichner ohne Leerzeichen)", required = true) String modelName,
      @McpToolParam(description = "Qualified import (default true)") @Nullable Boolean qualified
  ) {
    if (modelName == null || modelName.isBlank()) {
      throw new IllegalArgumentException("Model name is required.");
    }

    String trimmedName = modelName.trim();
    NameValidator.ascii().validateIdent(trimmedName, "Model name");
    String qualifier = Boolean.FALSE.equals(qualified) ? " UNQUALIFIED" : "";

    return "IMPORTS" + qualifier + " " + trimmedName + ";";
  }

  private String buildSolothurnBanner() {
    String today = LocalDate.now(clock.withZone(ZURICH)).format(ISO_DAY);
    return String.format(SOLOTHURN_BANNER_TEMPLATE, today);
  }

}
