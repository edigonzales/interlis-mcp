package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.util.AnnotationRenderer;
import ch.so.agi.mcp.util.NameValidator;
import java.time.Clock;
import java.time.LocalDate;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ModelTools {

  private static final String DEFAULT_ILI_VERSION = "2.4";

  private final Clock clock;
  public ModelTools(Clock clock) { this.clock = clock; }

  @McpTool(
      name = "createModelSnippet",
      description = "Erzeugt ein INTERLIS-2 Modellgerüst aus expliziten Metadaten. Params: name (required), lang (default 'de'), version (default 'today'), uri (default 'https://example.org/<name>'), iliVersion (default '2.4'), imports (default []), iliDoc, metaAttributes."
  )
  public Map<String, Object> createModelSnippet(
      @McpToolParam(description = "Modellname (Bezeichner ohne Leerzeichen)", required = true) String name,
      @McpToolParam(description = "Sprachcode, z. B. 'de' oder 'en'", required = false) @Nullable String lang,
      @McpToolParam(description = "URI des Modells", required = false) @Nullable String uri,
      @McpToolParam(description = "Version im Format YYYY-MM-DD", required = false) @Nullable String version,
      @McpToolParam(description = "INTERLIS Sprachversion (z. B. '2.3' oder '2.4')", required = false) @Nullable String iliVersion,
      @McpToolParam(description = "Zusätzliche Imports (z. B. 'GeometryCHLV95_V1')", required = false) @Nullable List<String> imports,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor dem MODEL", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor dem MODEL", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Model name is required.");
    }

    String modelName = name.trim();
    var nv = NameValidator.ascii();
    nv.validateIdent(modelName, "Model name");

    List<String> trimmedImports;
    if (imports == null) {
      trimmedImports = List.of();
    } else {
      if (imports.stream().anyMatch(model -> model == null || model.isBlank())) {
        throw new IllegalArgumentException("Import model names must not be blank.");
      }
      trimmedImports = imports.stream().map(String::trim).toList();
      if (new LinkedHashSet<>(trimmedImports).size() != trimmedImports.size()) {
        throw new IllegalArgumentException("Duplicate model imports are not allowed.");
      }
    }
    for (String m : trimmedImports) {
      validateImportModelName(m);
    }

    String _lang = (lang == null || lang.isBlank()) ? "de" : lang.trim();
    if (!_lang.matches("[a-z]{2}")) {
      throw new IllegalArgumentException("lang must be a two-letter lowercase language code.");
    }
    String _version = (version == null || version.isBlank()) ? LocalDate.now(clock).toString() : version.trim();
    try {
      LocalDate.parse(_version);
    } catch (java.time.format.DateTimeParseException ex) {
      throw new IllegalArgumentException("version must use ISO date format YYYY-MM-DD.");
    }
    String _uri = (uri == null || uri.isBlank())
        ? ("https://example.org/" + modelName.toLowerCase(Locale.ROOT))
        : uri.trim();
    URI parsedUri;
    try {
      parsedUri = URI.create(_uri);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("uri must be a valid absolute URI.");
    }
    if (!parsedUri.isAbsolute() || _uri.contains("\"") || _uri.contains("\n") || _uri.contains("\r")) {
      throw new IllegalArgumentException("uri must be a valid absolute URI.");
    }
    String _iliVersion = (iliVersion == null || iliVersion.isBlank()) ? DEFAULT_ILI_VERSION : iliVersion.trim();
    if (!"2.3".equals(_iliVersion) && !DEFAULT_ILI_VERSION.equals(_iliVersion)) {
      throw new IllegalArgumentException("iliVersion must be either '2.3' or '2.4'. Got: '" + _iliVersion + "'.");
    }
    String importLines = trimmedImports.stream()
        .map(model -> "  IMPORTS " + model + ";\n")
        .collect(Collectors.joining());

    String modelAnnotations = AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes);
    String snippet = String.format(
            "INTERLIS %s;\n\n" +
            "%s" +
            "MODEL %s (%s) AT \"%s\" VERSION \"%s\" =\n" +
            "%s\n" +
            "END %s.\n",
            _iliVersion, modelAnnotations, modelName, _lang, _uri, _version, importLines, modelName);

    return Map.of(
        "iliSnippet", snippet
    );
  }

  private void validateImportModelName(String modelName) {
    if (!"INTERLIS".equals(modelName)) {
      NameValidator.ascii().validateIdent(modelName, "Import model name");
    }
  }

}
