package ch.so.agi.mcp.knowledge;

import ch.so.agi.mcp.analysis.ModelPurpose;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ModelCorpusTools {

  private final ModelCorpusService corpusService;

  public ModelCorpusTools(ModelCorpusService corpusService) {
    this.corpusService = corpusService;
  }

  @McpTool(
      name = "indexConfiguredModels",
      description = "Inventar-/Admin-Tool: scannt die konfigurierten lokalen INTERLIS-Beispielpfade und gibt den aktuellen In-Memory-Index zurueck. Nicht fuer die normale Mustersuche verwenden; dafuer findSimilarModels."
  )
  public Map<String, Object> indexConfiguredModels() {
    return corpusService.indexConfiguredModels();
  }

  @McpTool(
      name = "findSimilarModels",
      description = "Discovery-Tool fuer passende INTERLIS-Beispiele im konfigurierten lokalen Modellkorpus. Verwenden, um Kandidaten fuer einen fachlichen oder strukturellen Modellierungsfall zu finden. Vor der Uebernahme eines Musters einen relevanten Treffer mit readModelExample vollstaendig lesen; nicht nur aus Treffer-Metadaten modellieren."
  )
  public Map<String, Object> findSimilarModels(
      @McpToolParam(description = "Suchbegriffe, fachlicher Kontext oder gewuenschte Modellstruktur", required = false) @Nullable String query,
      @McpToolParam(description = "Optionaler INTERLIS-Modelltext als Suchvorlage", required = false) @Nullable String modelText,
      @McpToolParam(description = "Modellzweck: CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false) @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Maximale Anzahl Treffer", required = false) @Nullable Integer limit
  ) {
    return corpusService.findSimilarModels(query, modelText, modelPurpose, limit);
  }

  @McpTool(
      name = "readModelExample",
      description = "Liest den vollstaendigen Quelltext eines ausgewaehlten INTERLIS-Beispielmodells. Verwenden nach findSimilarModels oder bei einem bekannten path aus indexConfiguredModels, bevor ein Muster uebernommen wird. Nicht zum Lesen beliebiger Dateien verwenden; Pfade ausserhalb des konfigurierten Korpus werden abgelehnt."
  )
  public Map<String, Object> readModelExample(
      @McpToolParam(description = "Pfad eines Treffers aus findSimilarModels oder indexConfiguredModels", required = true) String path
  ) {
    return corpusService.readModelExample(path);
  }
}
