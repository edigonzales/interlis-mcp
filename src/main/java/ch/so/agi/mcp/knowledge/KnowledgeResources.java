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
      title = "INTERLIS-Modellierungsregeln Solothurn",
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
      title = "Agentischer INTERLIS-Arbeitsablauf",
      description = "Kompakter Arbeitsablauf für agentisches INTERLIS-Modellieren.",
      mimeType = "text/markdown"
  )
  public ReadResourceResult agentWorkflow() {
    return markdown("interlis://knowledge/agent-workflow", """
        # Agentischer INTERLIS-Arbeitsablauf

        MCP-Sicherheitsvertrag:
        - Erforderliche und voneinander abhaengige MCP-Aufrufe einzeln und nacheinander ausfuehren.
        - Jedes Resultat vor dem naechsten Aufruf pruefen.
        - Bei Fehler, Timeout oder unbrauchbarem Resultat sofort stoppen und Tool, Argumente sowie exakte Fehlermeldung berichten.
        - Hoechstens einmal bei plausibel transientem Fehler wiederholen.
        - Vor einem erfolgreichen Retry keine `.ili`-Datei schreiben oder aendern.
        - Keine INTERLIS-Syntax erfinden; die typisierten High-Level-Tools verwenden.

        Modell-Gates:
        1. Klaere Modellzweck, fachliche Begriffe, Quellsysteme, Publikationsbedarf und offene Fragen.
        2. Suche bei Bedarf passende Beispiele mit `findSimilarModels` und lies ein ausgewaehltes Modell mit `readModelExample`.
        3. Fuer ein neues Modell `authorIliModel`, fuer einen bestehenden Stand den atomaren Batch `applyIliModelChanges` verwenden.
        4. Ein erfolgreiches `APPLIED`-Resultat von `applyIliModelChanges` enthaelt den semantischen Diff und `afterReview` als
          Abschlussgate. Fuer denselben unveraenderten Nachher-Stand nicht zusaetzlich `reviewIliChange` oder `reviewIliModel` ausfuehren.
        5. Fuer einen neuen Constraint verwende das hoechste passende Authoring-Tool. Ein erfolgreiches `proofVerified=true`
           ist das technische Constraint-Gate; fuer denselben unveraenderten Constraint folgt kein redundanter Validator-Durchlauf.
        6. Constraint-Authoring liefert Diff und `afterReview` aus denselben Compilations; kein redundantes Abschlussreview.
        7. Fuer noch nicht unterstuetzte sonstige Aenderungen bearbeite den Modelltext gezielt und vergleiche Vorher und Nachher mit `reviewIliChange`.
        8. Fuer einen einzelnen vollstaendigen Modellstand ohne Vorher-Stand verwende `reviewIliModel`.
        9. Behandle Compilerfehler und automatisierte ERROR-Findings vor WARNING/INFO-Findings.
        10. Liste `manualChecks` und `openQuestions` als fachliche Rueckfragen, ohne Kardinalitaeten, Rollen oder Constraints zu erfinden.
        11. Nutze `analyzeIliModel`, `checkModelingRules` und `validateIliModel` nur fuer gezielte Einzeldiagnosen, nicht als Standard-Dreierfolge.
        12. Wenn ein bereits gepruefter Nachher-Stand erneut geaendert wird, pruefe den neuen Stand wieder mit dem passenden High-Level-Tool.
        13. Ein neues Modell nur aus `authorIliModel.updatedModelText` bei `complete=true` uebernehmen.
        14. Geometrien ausschliesslich mit `GeometryTypeSpec` und expliziter Fachsemantik beschreiben.
        """);
  }

  @McpResource(
      uri = "interlis://knowledge/tool-guide",
      name = "tool-guide",
      title = "Werkzeugauswahl für interlis-mcp",
      description = "Entscheidungshilfe für High-Level-Reviews, semantische Änderungen, Constraints, Diagnostik und Modellbeispiele.",
      mimeType = "text/markdown"
  )
  public ReadResourceResult toolGuide() {
    return markdown("interlis://knowledge/tool-guide", """
        # Werkzeugauswahl für interlis-mcp

        Bevorzuge das hoechste Tool, das die konkrete Aufgabe vollstaendig abdeckt. Rufe Low-Level-Tools nicht routinemaessig zusaetzlich auf.

        ## Modell-Reviews und Aenderungen

        - Vollstaendigen aktuellen Modellstand ohne Vorher-Stand pruefen: `reviewIliModel`.
        - Neues Modell authoren: `authorIliModel`.
        - Unterstuetzte semantische Aenderung ausfuehren: `applyIliModelChanges`. Das Tool arbeitet atomar und source-preserving und liefert bei `APPLIED` bereits semantischen Diff,
          Compilerzustand und `afterReview` fuer den unveraenderten Nachher-Stand; fuer diesen Stand kein zusaetzliches `reviewIliChange` oder `reviewIliModel` ausfuehren.
        - Der Batch unterstuetzt unter anderem `ADD_CLASS`, `ADD_STRUCTURE`, `ADD_ATTRIBUTE`, `UPDATE_ATTRIBUTE` und `REMOVE_ATTRIBUTE`. Bei potenziell brechender Semantik wird der ganze Batch mit
          `BREAKING_CHANGE_REQUIRES_CONFIRMATION` zurueckgehalten, bis `allowPotentiallyBreaking=true` explizit gesetzt wird.
        - Wenn der Kandidat unerwartete semantische Nebenaenderungen enthaelt, wird `UNEXPECTED_SEMANTIC_CHANGE` geliefert.
        - Nicht unterstuetzte Aenderung: Quelltext gezielt bearbeiten und mit `reviewIliChange` abschliessen.
        - Lokales Vorbild: `findSimilarModels` -> ausgewaehlten Treffer mit `readModelExample` vollstaendig lesen.

        ## Constraints

        - Bestehenden Constraint erklaeren: `reviewIliConstraint`.
        - Bestehenden Constraint automatisch beweisen: `generateIliConstraintCases` fuer MANDATORY, UNIQUE, EXISTENCE,
          PLAUSIBILITY und den unterstuetzten SET-Umfang.
        - MANDATORY authoren: `authorIliMandatoryConstraint`.
        - EXISTENCE authoren: `authorIliExistenceConstraint` mit explizitem Source-Pfad und vollstaendigen REQUIRED-IN-Zielen.
        - PLAUSIBILITY authoren: `authorIliPlausibilityConstraint`; der Proof verwendet echte Populationen und kann
          `UNDEFINED_COUNTS_AS_SUCCESS` verifizieren.
        - SET authoren: `authorIliSetConstraint` fuer `OBJECT_COUNT` ueber `ALL` oder einen navigierten Objektpfad sowie `BOOLEAN_EXPRESSION`, optionales WHERE und GLOBAL/BASKET.
        - UNIQUE authoren: `authorIliUniqueConstraint` fuer GLOBAL/BASKET/LOCAL, mehrere Schluesselpfade, WHERE und LOCAL-Praefix.
        - `proofVerified=true` bzw. `generationVerified=true` bedeutet, dass alle erzeugten Proof-Faelle vom echten
          ilivalidator mit dem erwarteten Resultat bestaetigt wurden. `coverageComplete=false` kann trotzdem verbleibende
          sichere Coverage-Grenzen anzeigen.
        - Safety-Grenzen nicht approximieren. `REFERENCE_EQUALITY_VALIDATOR_FAILURE`, `GEOMETRY_EQUALITY_VALIDATOR_FAILURE`
          oder ein Polymorphie-Budget-Gap bedeuten, dass kein automatischer Ersatzbeweis behauptet wird; materialisierbare
          konkrete SET-Endtypen werden dagegen als getrennte `routeTargetFqn`-Coverage-Faelle bewiesen.
        - `testIliConstraint` ist fuer explizit vorgegebene Testfaelle. Nach einem bereits verifizierten automatischen Proof
          nicht routinemaessig nochmals denselben Constraint damit pruefen.
        - Constraint-Authoring liefert Proof, Diff und `afterReview` gemeinsam; kein redundantes `reviewIliChange`.

        ## Pfade und Funktionen

        - Standardfunktionen fuer typed Authoring zuerst mit `listConstraintFunctions` bestimmen und deren stabile `semanticId` verwenden.
        - Parameter mit `semanticType=ATTRIBUTE_PATH` als `PATH` modellieren; unklare Pfade mit `resolveConstraintPath` pruefen.
        - `reviewIliConstraint` loest Pfade fuer einen bereits vorhandenen Constraint automatisch im kompilierten Kontext auf.

        ## Gezielte Low-Level-Diagnostik

        - `validateIliModel`: Compiler-/Syntaxdiagnostik, insbesondere fuer einen konkreten Repair-Loop.
        - `analyzeIliModel`: Struktur- oder Semantikdetail eines Modells.
        - `checkModelingRules`: gezielte Regelpruefung, bei Bedarf mit einzelnen `ruleIds`.
        - `listModelingRules`: Regelkatalog; prueft selbst kein Modell.
        - `indexConfiguredModels`: Korpus inventarisieren; fuer die eigentliche Mustersuche `findSimilarModels` verwenden.

        Nach einem passenden High-Level-Review nicht standardmaessig noch `validateIliModel`, `analyzeIliModel` und `checkModelingRules` aufrufen. Technisch generierte Namen und andere Platzhalter sind keine fachlichen Entscheide.
        """);
  }

  @McpResource(
      uri = "interlis://knowledge/constraint-workflow",
      name = "constraint-workflow",
      title = "Arbeitsablauf für INTERLIS-Constraints",
      description = "Entscheidungsmatrix für Constraint-Authoring, automatischen Validator-Proof und Modell-Level-Abschlussreview.",
      mimeType = "text/markdown"
  )
  public ReadResourceResult constraintWorkflow() {
    return markdown("interlis://knowledge/constraint-workflow", """
        # Arbeitsablauf für INTERLIS-Constraints

        Trenne drei Ebenen:

        1. Constraint verstehen: `reviewIliConstraint` fuer AST, Pfade, Typen und technische Erklaerung.
        2. Constraint beweisen: `generateIliConstraintCases` fuer automatisch erzeugte Faelle und `generationVerified=true` als Gate; `testIliConstraint` nur fuer explizit vorgegebene Testfaelle.
        3. Modellaenderung abschliessen: Authoring-Resultat mit Proof, Diff und `afterReview` gemeinsam pruefen.

        ## Neue Constraints

        | Art | Bevorzugtes Authoring | Proof |
        | --- | --- | --- |
        | MANDATORY | `authorIliMandatoryConstraint` | im Authoring enthalten (`proofVerified`) |
        | UNIQUE | `authorIliUniqueConstraint` | im Authoring enthalten (`proofVerified`) |
        | EXISTENCE | `authorIliExistenceConstraint` | im Authoring enthalten (`proofVerified`) |
        | PLAUSIBILITY | `authorIliPlausibilityConstraint` | im Authoring enthalten (`proofVerified`) |
        | SET | `authorIliSetConstraint` fuer `OBJECT_COUNT` oder `BOOLEAN_EXPRESSION` | im Authoring enthalten (`proofVerified`) |

        Freie Mandatory-/Existence-/Set-Snippet-Helper sind nicht Teil der MCP-Oberflaeche. Wenn ein typed Authoring einen Fall
        nicht ausdruecken kann, bearbeitet der Agent den Modelltext gezielt und schliesst mit dem passenden Review und Proof ab.

        ## Safety-Grenzen

        - Proof-Reason-Codes und `coverageUnsolved` werden berichtet und nicht approximiert.
        - EXISTENCE REFERENCE/COORD/komplexe Geometrie besitzt keine skalare Ersatzsemantik; nur validatorbestaetigte typisierte Fixtures zaehlen.
        - PLAUSIBILITY wird mit echten Populationen bewiesen.
        - SET unterstuetzt `ALL` und navigierte Objektmengen, boolesche Ausdruecke, optionales WHERE und GLOBAL/BASKET, soweit alle Routen materialisierbar sind.
        - Jedes Authoring-Tool liefert auch das Modell-Level-`afterReview` aus seinen vorhandenen Compilations.
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
