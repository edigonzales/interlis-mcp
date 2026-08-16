package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.util.AnnotationRenderer;
import ch.so.agi.mcp.util.NameValidator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
  private static final String DEFAULT_ILI_VERSION = "2.4";
  private static final DateTimeFormatter ISO_DAY = DateTimeFormatter.ISO_DATE;
  private static final String SOLOTHURN_BANNER_TEMPLATE =
      "/** !!------------------------------------------------------------------------------\n" +
      " * !! Version    | wer | Änderung\n" +
      " * !!------------------------------------------------------------------------------\n" +
      " * !! %s | abr  | Initalversion\n" +
      " * !!==============================================================================\n" +
      " */\n";

  private final Clock clock;
  public ModelTools(Clock clock) { this.clock = clock; }

  @McpTool(
      name = "createModelSnippet",
      description = "Erzeugt ein INTERLIS-2 Modellgerüst. Params: name (required), lang (default 'de'), version (default 'today'), uri (default 'https://example.org/<name>'), iliVersion (default '2.4'), includeSolothurnHeader (default false), imports (default []), iliDoc, metaAttributes."
  )
  public Map<String, Object> createModelSnippet(
      @McpToolParam(description = "Modellname (Bezeichner ohne Leerzeichen)", required = true) String name,
      @McpToolParam(description = "Sprachcode, z. B. 'de' oder 'en'", required = false) @Nullable String lang,
      @McpToolParam(description = "URI des Modells", required = false) @Nullable String uri,
      @McpToolParam(description = "Version im Format YYYY-MM-DD", required = false) @Nullable String version,
      @McpToolParam(description = "INTERLIS Sprachversion (z. B. '2.3' oder '2.4')", required = false) @Nullable String iliVersion,
      @McpToolParam(description = "Zusätzliche Imports (z. B. 'GeometryCHLV95_V1')", required = false) @Nullable List<String> imports,
      @McpToolParam(description = "Fügt einen Solothurn-Header oberhalb des Snippets ein", required = false) @Nullable Boolean includeSolothurnHeader,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor dem MODEL", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor dem MODEL", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Model name is required.");
    }

    String modelName = name.trim();
    var nv = NameValidator.ascii();
    nv.validateIdent(modelName, "Model name");

    List<String> trimmedImports = imports == null
        ? List.of()
        : imports.stream().map(String::trim).collect(Collectors.toList());
    for (String m : trimmedImports) {
      validateImportModelName(m);
    }

    String _lang = (lang == null || lang.isBlank()) ? "de" : lang.trim();
    String _version = (version == null || version.isBlank()) ? LocalDate.now(clock).toString() : version.trim();
    String _uri = (uri == null || uri.isBlank())
        ? ("https://example.org/" + modelName.toLowerCase(Locale.ROOT))
        : uri.trim();
    String _iliVersion = (iliVersion == null || iliVersion.isBlank()) ? DEFAULT_ILI_VERSION : iliVersion.trim();
    if (!"2.3".equals(_iliVersion) && !DEFAULT_ILI_VERSION.equals(_iliVersion)) {
      throw new IllegalArgumentException("iliVersion must be either '2.3' or '2.4'. Got: '" + _iliVersion + "'.");
    }
    String importLines = trimmedImports.stream()
        .map(model -> "  IMPORTS " + model + ";\n")
        .collect(Collectors.joining());

    String header = Boolean.TRUE.equals(includeSolothurnHeader) ? buildSolothurnBanner() : "";
    List<MetaAttributeSpec> mergedMetaAttributes = Boolean.TRUE.equals(includeSolothurnHeader)
        ? AnnotationRenderer.mergeMetaAttributes(defaultSolothurnMetaAttributes(), metaAttributes)
        : AnnotationRenderer.mergeMetaAttributes(null, metaAttributes);
    String modelAnnotations = AnnotationRenderer.renderAnnotations(iliDoc, mergedMetaAttributes);
    String snippet = header +
        String.format(
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

  @McpTool(
      name = "createImportLine",
      description = "Erzeugt eine einzelne IMPORTS-Zeile. Params: modelName (required), qualified (default true)."
  )
  public String createImportLine(
      @McpToolParam(description = "Modellname (Bezeichner ohne Leerzeichen)", required = true) String modelName,
      @McpToolParam(description = "Qualified import (default true)", required = false) @Nullable Boolean qualified
  ) {
    if (modelName == null || modelName.isBlank()) {
      throw new IllegalArgumentException("Model name is required.");
    }

    String trimmedName = modelName.trim();
    validateImportModelName(trimmedName);
    String qualifier = Boolean.FALSE.equals(qualified) ? " UNQUALIFIED" : "";

    return "IMPORTS" + qualifier + " " + trimmedName + ";";
  }

  private void validateImportModelName(String modelName) {
    if (!"INTERLIS".equals(modelName)) {
      NameValidator.ascii().validateIdent(modelName, "Import model name");
    }
  }

  private String buildSolothurnBanner() {
    String today = LocalDate.now(clock.withZone(ZURICH)).format(ISO_DAY);
    return String.format(SOLOTHURN_BANNER_TEMPLATE, today);
  }

  private List<MetaAttributeSpec> defaultSolothurnMetaAttributes() {
    List<MetaAttributeSpec> defaults = new ArrayList<>();
    defaults.add(metaAttribute("technicalContact", null, "mailto:agi@bd.so.ch"));
    defaults.add(metaAttribute("title", "a title", null));
    defaults.add(metaAttribute("shortDescription", "a short description", null));
    defaults.add(metaAttribute("tags", "de:Gebäude,fr:Bâtiment,fubar", null));
    return defaults;
  }

  private MetaAttributeSpec metaAttribute(String name, @Nullable String value, @Nullable String rawValue) {
    MetaAttributeSpec metaAttribute = new MetaAttributeSpec();
    metaAttribute.setName(name);
    metaAttribute.setValue(value);
    metaAttribute.setRawValue(rawValue);
    return metaAttribute;
  }

}
