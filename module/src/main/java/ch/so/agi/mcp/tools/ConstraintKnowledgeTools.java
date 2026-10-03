package ch.so.agi.mcp.tools;

import static ch.so.agi.mcp.constraint.ConstraintExpression.ArgumentSemantics.ATTRIBUTE_PATH;

import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.so.agi.mcp.constraint.ConstraintExpression.IliVersion;
import ch.so.agi.mcp.constraint.ConstraintPathAnalysis;
import ch.so.agi.mcp.constraint.ConstraintPathSearch;
import ch.so.agi.mcp.constraint.StandardFunctionRegistry;
import ch.so.agi.mcp.constraint.StandardFunctionRegistry.Family;
import ch.so.agi.mcp.constraint.StandardFunctionRegistry.StandardFunction;
import ch.so.agi.mcp.model.ModelHashes;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ConstraintKnowledgeTools {

  private static final String ATTRIBUTE_PATH_SEMANTICS = ConstraintPathAnalysis.ATTRIBUTE_PATH_SEMANTICS;

  private final IliCompilerService compilerService;

  public ConstraintKnowledgeTools(IliCompilerService compilerService) {
    this.compilerService = compilerService;
  }

  @McpTool(
      name = "listConstraintFunctions",
      description = "Listet bekannte Constraint-Funktionen mit Herkunft, stabiler semantischer ID, Parametern und semantischen Parametertypen. Math/Text stammen aus INTERLIS-Funktionsmodellen; Validator-Extensions und Modellfunktionen werden als eigene Herkunftskategorien unterschieden.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false)
  )
  public Map<String, Object> listConstraintFunctions(
      @McpToolParam(description = "INTERLIS Sprachversion (2.3 oder 2.4)", required = false) @Nullable String iliVersion) {
    return describeFunctions(iliVersion);
  }

  public static Map<String, Object> describeFunctions(@Nullable String iliVersion) {
    IliVersion version = normalizeIliVersion(iliVersion);
    List<Map<String, Object>> functions = new ArrayList<>();
    appendStandardFunctions(functions, StandardFunctionRegistry.functions(Family.MATH), version);
    appendStandardFunctions(functions, StandardFunctionRegistry.functions(Family.TEXT), version);

    return Map.of(
        "runtimeIdentity", ch.so.agi.mcp.service.RuntimeIdentity.current(),
        "iliVersion", version.text(),
        "functions", functions,
        "constraintLanguage", List.of(
            languageConstruct("DEFINED", "Prueft, ob ein Wert definiert ist."),
            languageConstruct("AND", "Logische Konjunktion."),
            languageConstruct("OR", "Logische Disjunktion."),
            languageConstruct("NOT", "Logische Negation."),
            languageConstruct("IMPLIES", "Logische Implikation.")),
        "originKinds", Map.of(
            "LANGUAGE", "Sprachmittel aus INTERLIS selbst.",
            "STANDARD_FUNCTION_MODEL", "Funktion aus einem standardisierten INTERLIS-Funktionsmodell wie Math_V2 oder Text_V2.",
            "VALIDATOR_EXTENSION", "Zusaetzliche Laufzeitfunktion, die der Validator z. B. als InterlisFunction-Plugin bereitstellt; nicht automatisch portabel.",
            "MODEL_FUNCTION", "Funktion, die ein konkretes Fach-/Projektmodell deklariert; Laufzeitimplementierung separat pruefen."));
  }

  @McpTool(
      name = "resolveConstraintPath",
      description = "Loest einen bekannten String-Objekt-/Attributpfad im Kontext einer Klasse, Struktur, Association oder View exakt mit dem ili2c-Ili23Parser auf. Geeignet insbesondere fuer attributePath-Parameter wie Math_V2.sum(\"Rolle->Attribut\"). Gibt Pfadschritte, Kardinalitaeten, Optionalitaet, Zieltyp, technische Verwendungshinweise und bei Fehlern moegliche Elemente zurueck. Fuer die Suche nach einem noch unbekannten Pfad findConstraintPaths nutzen.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true)
  )
  public Map<String, Object> resolveConstraintPath(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Kontext, z. B. Modell.Topic.Klasse", required = true) String context,
      @McpToolParam(description = "Objekt-/Attributpfad, z. B. Nebenauspraegung->Gewichtung; aeussere Anfuehrungszeichen sind optional", required = true) String path) {
    TransferDescription td = compilerService.compileOrThrow(modelText, null, "constraint_path");
    return resolveCompiledPath(td, context, path);
  }

  public static Map<String, Object> resolveCompiledPath(TransferDescription td, String context, String path) {
    return ConstraintPathAnalysis.resolveCompiledPath(td, context, path);
  }

  @McpTool(
      name = "findConstraintPaths",
      description = "Findet compilergepruefte Pfade vom Constraint-Kontext zu einem bekannten Ziel-FQN (Attribut oder Objekttyp). Liefert nach Laenge/Name sortierte Alternativen, Kardinalitaeten, Optionalitaet und Ausdrucksbausteine fuer Einzelwerte, SUM und OBJECT_COUNT. Erst Pfad suchen, dann Ausdruck erstellen und mit bestehendem Authoring/Validator pruefen. Keine fachliche Pfadauswahl und kein Proof; Suchgrenzen und nicht unterstuetzte Formen bleiben sichtbar. Einen bereits bekannten Pfad mit resolveConstraintPath pruefen.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
  public Map<String, Object> findConstraintPaths(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Ausgangskontext (Klasse, Struktur, Association oder View)", required = true) String context,
      @McpToolParam(description = "Exakter FQN des Zielattributes oder deklarierten Objekttyps; keine Suchbegriffe", required = true) String targetFqn,
      @McpToolParam(description = "Maximale Anzahl Pfadschritte einschliesslich Endattribut; Standard 3, Bereich 1..8", required = false) @Nullable Integer maxDepth,
      @McpToolParam(description = "Maximale Trefferzahl; Standard 10, Bereich 1..50. Zusaetzlich hoechstens 2000 untersuchte Praefixe.", required = false) @Nullable Integer limit) {
    int depth = ConstraintPathSearch.depth(maxDepth), count = ConstraintPathSearch.limit(limit);
    if (context == null || context.isBlank() || targetFqn == null || targetFqn.isBlank()) {
      throw new IllegalArgumentException("context and targetFqn are required.");
    }
    var compilation = compilerService.compile(modelText, null, "constraint_path_search_");
    Map<String, Object> result;
    if (!compilation.valid() || compilation.transferDescription() == null) {
      result = new LinkedHashMap<>(ConstraintPathSearch.unavailable("MODEL_COMPILATION_FAILED", "The model must compile before paths can be discovered."));
    } else {
      result = new LinkedHashMap<>(ConstraintPathSearch.search(compilation.transferDescription(), context.trim(), targetFqn.trim(), depth, count));
    }
    result.put("messages", compilation.messages());
    return ModelHashes.attach(result, ModelHashes.model(modelText));
  }

  private static void appendStandardFunctions(
      List<Map<String, Object>> target,
      List<StandardFunction> functions,
      IliVersion version) {
    for (StandardFunction function : functions) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("name", function.qualifiedName(version));
      item.put("signature", function.signature(version));
      item.put("family", function.family() == Family.MATH ? "Math" : "Text");
      item.put("origin", "STANDARD_FUNCTION_MODEL");
      item.put("sourceModel", function.modelName(version));
      item.put("semanticId", function.semanticId());
      item.put("returns", function.declaredReturnType());
      item.put("parameters", function.parameters().stream().map(parameter -> Map.<String, Object>of(
          "name", parameter.name(),
          "type", parameter.declaredType(),
          "semanticType", parameter.semantics().name())).toList());
      boolean attributePath = function.parameters().stream()
          .anyMatch(parameter -> parameter.semantics() == ATTRIBUTE_PATH);
      if (attributePath) {
        item.put("pathSemantics", ATTRIBUTE_PATH_SEMANTICS);
        item.put("description", "Der attributePath-String wird von iox-ili im aktuellen Objektkontext mit Ili23Parser.parseObjectOrAttributePath geparst.");
        item.put("edgeCases", List.of("leere Zielmenge", "optionale Navigation", "undefinierte Endwerte", "mehrere Zielobjekte"));
      }
      target.add(item);
    }
  }

  private static Map<String, Object> languageConstruct(String name, String description) {
    return Map.of("name", name, "origin", "LANGUAGE", "description", description);
  }

  private static IliVersion normalizeIliVersion(@Nullable String iliVersion) {
    String version = iliVersion == null || iliVersion.isBlank() ? "2.4" : iliVersion.trim();
    return switch (version) {
      case "2.3" -> IliVersion.ILI_23;
      case "2.4" -> IliVersion.ILI_24;
      default -> throw new IllegalArgumentException("iliVersion must be '2.3' oder '2.4'.");
    };
  }

}
