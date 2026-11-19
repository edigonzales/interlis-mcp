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

  @McpResource(uri = "interlis://knowledge/so-data-rules", name = "so-data-rules",
      title = "SO-DATA: kommentarfreie CSV-Validierungsmodelle", mimeType = "text/markdown")
  public ReadResourceResult soDataRules() {
    return markdown("interlis://knowledge/so-data-rules", ruleLoader.rulesAsMarkdown(ModelingRuleProfile.SO_DATA));
  }

  @McpResource(
      uri = "interlis://knowledge/handbook-rules",
      name = "handbook-rules",
      title = "INTERLIS-Modellierungsregeln Solothurn",
      description = "Kuratierte, versionierte Regeln aus dem Solothurner Modellierungshandbuch inklusive der portablen CORE-Basisregeln.",
      mimeType = "text/markdown"
  )
  public ReadResourceResult handbookRules() {
    return markdown(
        "interlis://knowledge/handbook-rules",
        ruleLoader.rulesAsMarkdown(ModelingRuleProfile.SO_GIS));
  }

  @McpResource(
      uri = "interlis://knowledge/agent-workflow",
      name = "agent-workflow",
      title = "INTERLIS-Arbeitsablauf",
      description = "Werkzeugwahl, Prüfergebnisse und Freigabe von Modelländerungen.",
      mimeType = "text/markdown"
  )
  public ReadResourceResult agentWorkflow() {
    return markdown("interlis://knowledge/agent-workflow", """
        # INTERLIS-Arbeitsablauf

        - Modellstruktur oder Constraint-Kontext mit `analyzeIliModel` untersuchen. Reine
          Compilerdiagnostik liefert `validateIliModel`. `reviewIliModel` ergänzt Regeln,
          manuelle Checks und offene Fragen. `verifyIliModel` führt auch automatische
          Constraint-Nachweise für einen bestehenden Modellstand aus.
        - Neues Modell mit `authorIliModel` erstellen. Zusammengehörende Änderungen mit
          `applyIliModelChanges` atomar anwenden. `allowPotentiallyBreaking=true` setzt eine
          ausdrückliche Bestätigung voraus. Nutze `reviewIliChange` für separat bearbeitete Stände.
        - Einen neuen Constraint mit `authorIliConstraint` und passendem `spec.kind` ergänzen.
          Bekannte Pfade mit `resolveConstraintPath` prüfen, unbekannte zu einem Ziel-FQN mit
          `findConstraintPaths` suchen. Verwende `listConstraintFunctions` für Standard-Semantik-IDs.
          Einzutragende fachliche Erwartungen vor der Modellierung festhalten, einschliesslich
          Grenzwerten, Fehlwerten, leeren Beziehungen und Geltungsbereich.
        - `reviewIliConstraint` erklärt eine bestehende Regel. `generateIliConstraintCases`
          prüft automatisch abgeleitete Fälle mit dem Validator. Beurteile `generationVerified`
          zusammen mit `coverageComplete`. `testIliConstraint` prüft separat festgelegte
          Erwartungen. Gib deren Herkunft als `expectationSource` an. Für fachliche Abnahme
          sind Erwartungen aus einer bestätigten Fachquelle erforderlich.
        - Führe abhängige MCP-Aufrufe nacheinander aus. Bei einem temporären Ausfall kannst
          du den betroffenen Aufruf einmal wiederholen. Fehlt weiterhin ein brauchbares Resultat,
          melde den Fehler und halte alle darauf angewiesenen Dateiänderungen zurück.
        - Lies Compilerbefunde unter `beforeCompilation` und `afterCompilation`.
          `constraintProofs: []` meldet, dass keine Regel zu prüfen war. Fehlt das Feld,
          wurde die Proof-Phase noch nicht erreicht. `PASSED` gilt für die geprüfte Coverage.
          Berichte fehlende Coverage, fehlgeschlagene Fälle und externe Funktionssemantik.
          Nutze `specDiagnostics` oder den JSON-Pfad eines Schemafehlers für eine gezielte Korrektur.
        - `APPLIED` gibt den technischen Änderungsstand frei. Prüfe dessen Regelkonformität
          anhand `afterReview.validForAutomatedRules` und berichte `afterReview.ruleFindings`.
          `constraintInteractions` enthält beratende Befunde. Ein nachgewiesener Widerspruch
          bezieht sich auf Objekte im angegebenen Kontext, auch wenn ein leerer Transfer möglich ist.
        - Kläre blockierende Fragen aus `openQuestions`. Halte Kandidaten zurück, wenn
          erforderliche Proofs oder fachliche Tests fehlen oder gescheitert sind. Vor einem
          autorisierten Schreiben prüfe, dass `testIliConstraint.modelHashes.model` zum
          `modelHashes.after` der Änderung passt. Vergleiche beim Überschreiben den exakten
          aktuellen UTF-8-Text per SHA-256 mit `modelHashes.before`. Prüfe nach einer weiteren
          Änderung den neuen Stand.
        - Erfolgreiche, ausgeübte und warnungsfreie Test-XTFs werden standardmässig ausgelassen.
          Für die vollständige Ausgabe setze `includeSuccessfulTestXtf=true`.
        - `generateExampleXtf` meldet untersuchte Transferdaten unter `validation.status`.
          `generated=true` meldet die Erzeugung. Ausgelassene Klassen sind ungeprüft.
        """);
  }

  @McpResource(
      uri = "interlis://knowledge/model-corpus-index",
      name = "model-corpus-index",
      title = "Index des konfigurierten INTERLIS-Modellkorpus",
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
