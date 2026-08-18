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
      title = "Solothurn INTERLIS Modeling Rules",
      description = "Kuratierte, versionierte Regeln aus dem Solothurner Modellierungshandbuch inklusive der portablen CORE-Basisregeln.",
      mimeType = "text/markdown"
  )
  public ReadResourceResult handbookRules() {
    return markdown(
        "interlis://knowledge/handbook-rules",
        ruleLoader.rulesAsMarkdown(ModelingRuleProfile.SO));
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
        6. Verwende `afterReview` zusammen mit `afterCompilerValid` und `afterDiagnostics` als Abschlussreview des Nachher-Stands.
           Fuehre fuer denselben unveraenderten Nachher-Stand nicht zusaetzlich `reviewIliModel` aus.
        7. Behandle Compilerfehler und automatisierte ERROR-Findings vor WARNING/INFO-Findings.
        8. Liste `manualChecks` und `openQuestions` als fachliche Rueckfragen, ohne Kardinalitaeten, Rollen oder Constraints zu erfinden.
        9. Nutze `analyzeIliModel`, `checkModelingRules` und `validateIliModel` nur fuer gezielte Einzeldiagnosen, nicht als Standard-Dreierfolge.
        10. Wenn der Modelltext nach `reviewIliChange` erneut geaendert wird, pruefe den neuen Nachher-Stand erneut mit `reviewIliChange`.
        11. Liefere am Schluss Modelltext, semantische Aenderungen, Review-Resultat und offene fachliche Entscheide.
        """);
  }

  @McpResource(
      uri = "interlis://knowledge/tool-guide",
      name = "tool-guide",
      title = "INTERLIS MCP Tool Choice Guide",
      description = "Entscheidungshilfe fuer die Auswahl zwischen High-Level-Review, Low-Level-Diagnostik und Modellbeispielen.",
      mimeType = "text/markdown"
  )
  public ReadResourceResult toolGuide() {
    return markdown("interlis://knowledge/tool-guide", """
        # INTERLIS MCP Tool Choice Guide

        Bevorzuge das hoechste Tool, das die konkrete Frage vollstaendig beantwortet. Rufe Low-Level-Tools nicht routinemaessig zusaetzlich auf.

        ## Regelprofile

        - `CORE` enthaelt nur portable technische/agentische Basisregeln des MCP-Servers.
        - `SO` enthaelt `CORE` plus die Regeln aus dem Solothurner Modellierungshandbuch.
        - Fuer Modelle, die nach den Vorgaben des Kantons Solothurn beurteilt werden, verwende `ruleProfile=SO`.

        ## Standardfaelle

        - Vollstaendigen aktuellen Modellstand ohne Vorher-Stand pruefen: `reviewIliModel`.
          Es kombiniert ili2c, Strukturanalyse, automatisierte Regeln, manuelle Checks und offene fachliche Fragen.
        - Vorher-/Nachher-Aenderung pruefen: `reviewIliChange`.
          Es liefert den semantischen Diff und prueft gleichzeitig das After-Modell. `afterReview` bildet zusammen mit
          `afterCompilerValid` und `afterDiagnostics` den Abschlussreview; fuer denselben unveraenderten Nachher-Stand ist kein
          zusaetzliches `reviewIliModel` erforderlich.
        - Passendes Modellierungsmuster suchen: `findSimilarModels` -> `readModelExample`.
          Treffer dienen nur der Discovery; lies einen relevanten Treffer vollstaendig, bevor du das Muster uebernimmst.

        ## Constraints und String-Pfade

        - Einen bestehenden Constraint erklaeren oder technisch pruefen: `reviewIliConstraint`. Das Tool liefert den compilerbasierten AST, Kontext, referenzierte Elemente, Funktionen, Pfade, Typen und strukturelle Edge Cases.
        - Bevor du eine Constraint-Funktion aus Trainingswissen annimmst, pruefe sie mit `listConstraintFunctions`. Beachte insbesondere `origin` und die Parameter-`semanticType`.
        - Hat ein Parameter `semanticType=ATTRIBUTE_PATH`, erfinde den String-Pfad nicht. Pruefe ihn mit `resolveConstraintPath` im konkreten Klassen-/Association-Kontext. `reviewIliConstraint` erledigt dies fuer vorhandene Constraints automatisch.
        - `resolveConstraintPath` verwendet dieselbe ili2c-Objekt-/Attributpfad-Syntax, die iox-ili fuer die bekannten Math-Aggregatfunktionen auswertet.
        - `reviewIliConstraint` prueft in diesem Schritt Struktur und aufloesbare Semantik. Es beweist nicht die beabsichtigte fachliche Regel und erzeugt noch keine Constraint-Testdaten, Witnesses oder Counterexamples.

        ## Low-Level nur bei gezieltem Bedarf

        - `validateIliModel`: nur Compiler-/Syntaxdiagnostik, besonders fuer einen gezielten Repair-Loop.
        - `analyzeIliModel`: nur strukturelle oder semantische Detailfragen zum Modell.
        - `checkModelingRules`: nur gezielte Regelpruefung, insbesondere fuer einzelne `ruleIds`.
        - `listModelingRules`: Regelkatalog verstehen; prueft selbst kein Modell.
        - `indexConfiguredModels`: Korpus inventarisieren/aktualisieren; fuer Mustersuche `findSimilarModels` verwenden.

        Nach einem passenden High-Level-Review nicht standardmaessig noch `validateIliModel`, `analyzeIliModel` und `checkModelingRules` aufrufen. Nutze eines davon nur, wenn das High-Level-Resultat eine konkrete Detailfrage offenlaesst.

        Snippet-, Rename- und Formatting-Tools sind lokale Konstruktionshilfen. Fuer einen einzelnen Modellstand ohne Baseline entscheidet `reviewIliModel`; bei einer Vorher-/Nachher-Aenderung entscheidet `reviewIliChange`. Technisch generierte Namen oder andere fachliche Platzhalter sind keine fachlichen Entscheide.
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
