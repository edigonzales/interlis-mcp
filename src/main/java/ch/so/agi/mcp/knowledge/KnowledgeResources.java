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
        2. Suche bei Bedarf passende Beispiele mit `findSimilarModels` und lies ein ausgewaehltes Modell mit `readModelExample`.
        3. Wenn ein bestehendes vollstaendiges Modell vorliegt, ermittle den Ausgangszustand mit `reviewIliModel`.
        4. Erstelle oder erweitere das Modell in kleinen, nachvollziehbaren Schritten. Fachliche Entscheidungen nicht erfinden.
        5. Vergleiche bei Aenderungen eines bestehenden Modells Vorher und Nachher mit `reviewIliChange`.
        6. Pruefe den aktuellen vollstaendigen Modellstand mit `reviewIliModel`.
        7. Behandle Compilerfehler und automatisierte ERROR-Findings vor WARNING/INFO-Findings.
        8. Liste `manualChecks` und `openQuestions` als fachliche Rueckfragen, ohne Kardinalitaeten, Rollen oder Constraints zu erfinden.
        9. Nutze `analyzeIliModel`, `checkModelingRules` und `validateIliModel` nur fuer gezielte Einzeldiagnosen, nicht als Standard-Dreierfolge.
        10. Liefere am Schluss Modelltext, semantische Aenderungen, Review-Resultat und offene fachliche Entscheide.
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
