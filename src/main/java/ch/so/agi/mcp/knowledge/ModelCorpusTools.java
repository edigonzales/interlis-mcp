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
      name = "findSimilarModels",
      description = "Findet passende Beispiele im konfigurierten lokalen Modellkorpus anhand von Suchbegriffen oder Modelltext.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false)
  )
  public Map<String, Object> findSimilarModels(
      @McpToolParam(description = "Suchbegriffe oder gewünschte Modellstruktur", required = false) @Nullable String query,
      @McpToolParam(description = "Optionaler INTERLIS-Modelltext als Suchvorlage", required = false) @Nullable String modelText,
      @McpToolParam(description = "Modellzweck", required = false) @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Maximale Anzahl Treffer", required = false) @Nullable Integer limit
  ) {
    return corpusService.findSimilarModels(query, modelText, modelPurpose, limit);
  }

  @McpTool(
      name = "readModelExample",
      description = "Liest ein Beispiel aus dem konfigurierten lokalen Modellkorpus vollständig.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false)
  )
  public Map<String, Object> readModelExample(
      @McpToolParam(description = "Pfad aus findSimilarModels oder aus dem Korpusindex", required = true) String path
  ) {
    return corpusService.readModelExample(path);
  }
}
