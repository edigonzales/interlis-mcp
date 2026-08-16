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
      description = "Scannt die konfigurierten lokalen INTERLIS-Beispielpfade und gibt den aktuellen In-Memory-Index zurueck."
  )
  public Map<String, Object> indexConfiguredModels() {
    return corpusService.indexConfiguredModels();
  }

  @McpTool(
      name = "findSimilarModels",
      description = "Sucht aehnliche INTERLIS-Modelle in den konfigurierten lokalen Beispielpfaden mit lexikalischem Scoring."
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
      description = "Liest ein vollstaendiges INTERLIS-Beispielmodell aus dem konfigurierten lokalen Modellkorpus. Verwende einen path aus findSimilarModels oder indexConfiguredModels. Pfade ausserhalb des konfigurierten Korpus werden abgelehnt."
  )
  public Map<String, Object> readModelExample(
      @McpToolParam(description = "Pfad eines Treffers aus findSimilarModels oder indexConfiguredModels", required = true) String path
  ) {
    return corpusService.readModelExample(path);
  }
}
