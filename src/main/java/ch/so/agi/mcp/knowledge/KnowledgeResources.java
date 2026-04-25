package ch.so.agi.mcp.knowledge;

import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeResources {

  private final KnowledgeRuleLoader ruleLoader;
  private final ModelCorpusService corpusService;

  public KnowledgeResources(KnowledgeRuleLoader ruleLoader, ModelCorpusService corpusService) {
    this.ruleLoader = ruleLoader;
    this.corpusService = corpusService;
  }

  @McpResource(
      uri = "interlis://knowledge/handbook-rules",
      name = "handbook-rules",
      title = "Curated INTERLIS Modeling Rules",
      description = "Kuratierte, versionierte MVP-Regeln aus dem Modellierungshandbuch-Kontext.",
      mimeType = "text/markdown"
  )
  public ReadResourceResult handbookRules() {
    return markdown("interlis://knowledge/handbook-rules", ruleLoader.rulesAsMarkdown());
  }

  @McpResource(
      uri = "interlis://knowledge/agent-workflow",
      name = "agent-workflow",
      title = "INTERLIS Agent Workflow",
      description = "Kompakter Arbeitsablauf fuer agentisches INTERLIS-Modellieren.",
      mimeType = "text/markdown"
  )
  public ReadResourceResult agentWorkflow() {
    return markdown("interlis://knowledge/agent-workflow", """
        # INTERLIS Agent Workflow

        1. Klaere Modellzweck, fachliche Begriffe, Quellsysteme, Publikationsbedarf und offene Fragen.
        2. Suche passende Beispiele mit `findSimilarModels`, falls lokale Modellpfade konfiguriert sind.
        3. Erstelle oder erweitere das Modell in kleinen, validierbaren Schritten.
        4. Fuehre nach jeder strukturellen Aenderung `analyzeIliModel` und `validateIliModel` aus.
        5. Fuehre `checkModelingRules` mit dem passenden `modelPurpose` aus.
        6. Behandle automatisierte ERROR-Findings vor WARNING/INFO-Findings.
        7. Liste MANUAL-Checks als fachliche Rueckfragen, ohne Kardinalitaeten, Rollen oder Constraints zu erfinden.
        8. Liefere am Schluss Modelltext, Validierungsresultat, Regel-Findings und offene fachliche Entscheide.
        """);
  }

  @McpResource(
      uri = "interlis://knowledge/model-corpus-index",
      name = "model-corpus-index",
      title = "Configured INTERLIS Model Corpus Index",
      description = "Aktueller Index der konfigurierten lokalen .ili-Beispielpfade.",
      mimeType = "text/markdown"
  )
  public ReadResourceResult modelCorpusIndex() {
    return markdown("interlis://knowledge/model-corpus-index", corpusService.indexMarkdown());
  }

  private ReadResourceResult markdown(String uri, String markdown) {
    return new ReadResourceResult(List.of(new TextResourceContents(uri, "text/markdown", markdown)));
  }
}
