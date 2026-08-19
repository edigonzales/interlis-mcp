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
        3. Fuer eine von `applyIliModelChange` unterstuetzte Aenderung eines bestehenden Modells verwende das semantische
           Change-Tool statt den Quelltext selbst umzuschreiben. Aktuell ist `ADD_ATTRIBUTE` fuer CLASS und STRUCTURE unterstuetzt.
        4. Ein erfolgreiches `APPLIED`-Resultat von `applyIliModelChange` enthaelt den semantischen Diff und `afterReview` als
           Abschlussgate. Fuer denselben unveraenderten Nachher-Stand nicht zusaetzlich `reviewIliChange` oder `reviewIliModel` ausfuehren.
        5. Fuer noch nicht unterstuetzte Aenderungen bearbeite den Modelltext gezielt und vergleiche Vorher und Nachher mit `reviewIliChange`.
        6. Fuer einen einzelnen vollstaendigen Modellstand ohne Vorher-Stand verwende `reviewIliModel`.
        7. Behandle Compilerfehler und automatisierte ERROR-Findings vor WARNING/INFO-Findings.
        8. Liste `manualChecks` und `openQuestions` als fachliche Rueckfragen, ohne Kardinalitaeten, Rollen oder Constraints zu erfinden.
        9. Nutze `analyzeIliModel`, `checkModelingRules` und `validateIliModel` nur fuer gezielte Einzeldiagnosen, nicht als Standard-Dreierfolge.
        10. Wenn ein bereits gepruefter Nachher-Stand erneut geaendert wird, pruefe den neuen Stand wieder mit dem passenden High-Level-Tool.
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
        - Unterstuetzte semantische Aenderung an einem bestehenden Modell ausfuehren: `applyIliModelChange`.
          Aktuell wird `ADD_ATTRIBUTE` fuer CLASS und STRUCTURE unterstuetzt. Das Tool arbeitet source-preserving, kompiliert
          Vorher und Nachher hoechstens einmal und prueft den semantischen Diff auf unerwartete Kollateralaenderungen.
          Bei `APPLIED` bilden `afterCompilerValid`, `afterDiagnostics`, semantischer Diff und `afterReview` das Abschlussgate;
          fuer denselben unveraenderten Nachher-Stand kein zusaetzliches `reviewIliChange` oder `reviewIliModel` ausfuehren.
        - Vorher-/Nachher-Aenderung pruefen, wenn die Aenderung nicht von `applyIliModelChange` unterstuetzt wird: `reviewIliChange`.
          Es liefert den semantischen Diff und prueft gleichzeitig das After-Modell. `afterReview` bildet zusammen mit
          `afterCompilerValid` und `afterDiagnostics` den Abschlussreview; fuer denselben unveraenderten Nachher-Stand ist kein
          zusaetzliches `reviewIliModel` erforderlich.
        - Passendes Modellierungsmuster suchen: `findSimilarModels` -> `readModelExample`.
          Treffer dienen nur der Discovery; lies einen relevanten Treffer vollstaendig, bevor du das Muster uebernimmst.

        ## Semantic Model Changes

        - `ADD_ATTRIBUTE`: `request.operation=ADD_ATTRIBUTE`, `addAttribute.containerFqn` bezeichnet eine lokale CLASS oder
          STRUCTURE und `addAttribute.attribute` verwendet dieselbe strikt typisierte `AttributeLineRequest`-Struktur wie
          `createAttributeLine`. Bestehende oder geerbte Attributnamen werden abgelehnt.
        - Das Tool gibt `updatedModelText` nur bei `status=APPLIED` frei. Wenn der erzeugte Quelltext zwar kompiliert, der
          semantische Diff aber mehr als die verlangte Operation enthaelt, wird `UNEXPECTED_SEMANTIC_CHANGE` geliefert und nur
          `candidateModelText` zur Diagnose ausgegeben.
        - Ziele aus importierten Modellen werden nicht veraendert. Noch nicht unterstuetzte Operationen nicht durch freie
          Change-Payloads emulieren; Quelltext gezielt bearbeiten und anschliessend `reviewIliChange` verwenden.

        ## Constraints und String-Pfade

        - Einen bestehenden Constraint erklaeren oder technisch pruefen: `reviewIliConstraint`. Das Tool liefert den compilerbasierten AST, Kontext, referenzierte Elemente, Funktionen, Pfade, Typen und strukturelle Edge Cases.
        - Einen neuen allgemeinen `MANDATORY CONSTRAINT` aus einer fachlich bereits geklaerten Regel erzeugen: `authorIliMandatoryConstraint`. Formuliere die Regel als semantische Node-Liste mit `ATTRIBUTE`, `PATH`, Literalen, `FUNCTION`, `DEFINED`, `NOT`, `AND`, `OR`, `IMPLIES` und `COMPARE`. Verwende fuer Standardfunktionen die stabile `semanticId` aus `listConstraintFunctions`, nicht eine geratene versionsabhaengige Schreibweise.
        - `authorIliMandatoryConstraint` rendert die Node-Liste fuer die Sprachversion des Modells, kompiliert den Vorschlag mit ili2c, uebersetzt den kompilierten AST zurueck in die typisierte semantische IR und beweist ihn anschliessend ueber dieselbe Coverage-/Solver-/Object-Graph-Pipeline wie bestehende Mandatory Constraints mit echtem XTF und ilivalidator. `proofVerified=true` bedeutet, dass alle erzeugten Proof-Faelle vom Validator bestaetigt wurden; ungeloeste Coverage-Ziele bleiben sichtbar.
        - Einen neuen skalaren `EXISTENCE CONSTRAINT` erzeugen: `authorIliExistenceConstraint`. Gib `restrictedPath` sowie jedes `REQUIRED IN`-Ziel explizit als `viewableFqn` plus `attributePath` an. Das Tool raet keine Zielattribute, loest Source/Targets im Before-AST auf, fuegt source-preserving ein, kompiliert Before/After je einmal, prueft den Constraint-Level-IR-Roundtrip und verifiziert die Proof-Faelle mit ilivalidator.
        - `authorIliExistenceConstraint` authort weiterhin den sicheren skalaren NUMERIC/BOOLEAN/ENUM/TEXT/MTEXT-Umfang. Fuer neue Agent-Workflows ist dieses Tool dem alten Low-Level-Helper `createExistenceConstraint` vorzuziehen, dessen Klassenlisten-Schema die echte `ViewableRef : AttributePath`-Semantik nicht vollstaendig ausdrueckt.
        - Wenn die fachliche Regel bereits als Entscheidungstabelle mit erlaubten Zeilen vorliegt, verwende weiterhin `generateIliConstraintFromDecisionTable`. Dieses spezialisierte Frontend ist kompakter: Bedingungen einer Zeile werden mit AND und mehrere erlaubte Zeilen mit OR verbunden; die Proof-Pipeline dahinter ist dieselbe semantische Infrastruktur.
        - Fuer einen bereits bestehenden unterstuetzten Mandatory-, UNIQUE- oder EXISTENCE-Constraint verwende `generateIliConstraintCases`, wenn automatisch Witnesses und Counterexamples erzeugt werden sollen. Das Tool uebersetzt den echten ili2c-AST in die Constraint-Level-/Expression-IR und verifiziert die erzeugten Objektgraphen mit `testIliConstraint` und ilivalidator.
        - Der automatische Mandatory-Umfang umfasst insbesondere direkte skalare Attribute, numerische/Boolean-/Enum-Vergleiche, `DEFINED`, `NOT`, `AND`, `OR`, `IMPLIES`, bekannte Math-/Text-Funktionen, SUM/Aggregate sowie mehrstufige skalare Navigation ueber Associations, Structures/Compositions und `REFERENCE TO`. Die Objektgraph-Synthese bleibt bewusst begrenzt, insbesondere auf hoechstens einen mehrwertigen Navigationsschritt pro Pfad; ungeloeste Faelle werden explizit gemeldet statt geraten.
        - Der automatische UNIQUE-Umfang umfasst globale UNIQUE-Schluessel, `(BASKET)`, `WHERE`, optionale/undefinierte Schluesselkomponenten sowie `LOCAL` fuer direkte STRUCTURE-/Composition-Prefixe mit direkten skalaren Member-Schluesseln. Globale UNIQUE-Pfade verwenden denselben modellbewussten Binder wie Mandatory. Nicht automatisch synthetisierbare LOCAL-Navigation oder nicht isolierbare WHERE-Ziele erscheinen als `coverageUnsolved` statt durch angenaeherte Semantik ersetzt zu werden.
        - Der automatische EXISTENCE-Umfang umfasst skalare NUMERIC/BOOLEAN/ENUM/TEXT/MTEXT-Werte und neu direkte STRUCTURE-/COMPOSITION-Werte. Fuer STRUCTURE erzeugt der Planner fehlendes Target, member-wise Equality, eine gezielte Member-Differenz und bei optionalen Werten einen UNDEFINED-Witness. Die automatische STRUCTURE-Gleichheit wird nur fuer denselben Component-Type, kleine kompatible Kardinalitaeten und identische Source-/Target-Attributnamen behauptet; andere Formen erscheinen als `coverageUnsolved`.
        - `REFERENCE TO` wird bei EXISTENCE bewusst nicht automatisch als Gleichheitsbeweis freigegeben (`EXISTENCE_REFERENCE_VALUE_PROOF_UNSAFE`). COORD hat echte Validator-Semantik, aber noch keine wertbewusste automatische Fixture-Injektion (`EXISTENCE_COORD_FIXTURE_NOT_VALUE_AWARE`). POLYLINE/SURFACE/AREA bleiben `EXISTENCE_COMPLEX_GEOMETRY_FIXTURE_UNAVAILABLE`. Diese Grenzen werden explizit gemeldet und nicht als skalare Ersatzsemantik emuliert.
        - `generationVerified=true` bedeutet fuer Mandatory, UNIQUE und EXISTENCE, dass jeder erzeugte Proof-Fall vom echten ilivalidator mit dem erwarteten Ergebnis bestaetigt wurde. `coverageComplete=false` kann trotzdem anzeigen, dass nicht alle geplanten semantischen Ziele konstruiert oder sicher bewiesen werden konnten.
        - Wenn konkrete fachliche Beispiele fuer gueltige und ungueltige Faelle vorliegen, pruefe sie weiterhin mit `testIliConstraint`. Jeder Testfall gibt `expectedConstraintValid`, Objekte und optional Referenzen bzw. Association-Links explizit vor; `basketId` kann fuer Multi-Basket-Faelle gesetzt werden. Das Tool erzeugt daraus XTF und prueft den ausgewaehlten Constraint mit dem Validator.
        - `testIliConstraint` isoliert den ausgewaehlten Constraint, laesst aber Typ-, Multiplizitaets- und Transferpruefungen aktiv. Nicht zum Ziel-Constraint gehoerende Fehler werden deshalb als Fixture-Fehler ausgewiesen statt als Constraint-Ergebnis interpretiert.
        - Bevor du eine Constraint-Funktion aus Trainingswissen annimmst, pruefe sie mit `listConstraintFunctions`. Beachte insbesondere `origin`, `semanticId` und die Parameter-`semanticType`.
        - Hat ein Parameter `semanticType=ATTRIBUTE_PATH`, verwende im Authoring einen `PATH`-Knoten und erfinde den Pfad nicht. Pruefe unklare Pfade mit `resolveConstraintPath` im konkreten Klassen-/Association-Kontext. `reviewIliConstraint` erledigt dies fuer vorhandene Constraints automatisch.
        - `resolveConstraintPath` verwendet dieselbe ili2c-Objekt-/Attributpfad-Syntax, die iox-ili fuer die bekannten Math-Aggregatfunktionen auswertet.
        - `authorIliMandatoryConstraint` und `generateIliConstraintFromDecisionTable` authoren weiterhin `MANDATORY CONSTRAINT`; `authorIliExistenceConstraint` authort skalare EXISTENCE Constraints. `generateIliConstraintCases` beweist bestehende Mandatory-, UNIQUE- und die unterstuetzten EXISTENCE-Formen inklusive direkter STRUCTURE-Gleichheit. PLAUSIBILITY und SET benoetigen weiterhin ihre eigenen Constraint-Level-Proof-Semantiken und duerfen nicht als Mandatory emuliert werden.

        ## Low-Level nur bei gezieltem Bedarf

        - `validateIliModel`: nur Compiler-/Syntaxdiagnostik, besonders fuer einen gezielten Repair-Loop.
        - `analyzeIliModel`: nur strukturelle oder semantische Detailfragen zum Modell.
        - `checkModelingRules`: nur gezielte Regelpruefung, insbesondere fuer einzelne `ruleIds`.
        - `listModelingRules`: Regelkatalog verstehen; prueft selbst kein Modell.
        - `indexConfiguredModels`: Korpus inventarisieren/aktualisieren; fuer Mustersuche `findSimilarModels` verwenden.

        Nach einem passenden High-Level-Review nicht standardmaessig noch `validateIliModel`, `analyzeIliModel` und `checkModelingRules` aufrufen. Nutze eines davon nur, wenn das High-Level-Resultat eine konkrete Detailfrage offenlaesst.

        Snippet-, Rename- und Formatting-Tools sind lokale Konstruktionshilfen. Fuer einen einzelnen Modellstand ohne Baseline entscheidet `reviewIliModel`; fuer unterstuetzte semantische Aenderungen `applyIliModelChange`; bei einer sonstigen Vorher-/Nachher-Aenderung entscheidet `reviewIliChange`. Technisch generierte Namen oder andere fachliche Platzhalter sind keine fachlichen Entscheide.
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
