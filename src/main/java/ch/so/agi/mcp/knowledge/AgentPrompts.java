package ch.so.agi.mcp.knowledge;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

@Component
public class AgentPrompts {

  @McpPrompt(
      name = "interlis-modeling-agent",
      title = "INTERLIS-Modellierungsagent",
      description = "Kompakte Systemanweisung für agentisches INTERLIS-Modellieren."
  )
  public GetPromptResult interlisModelingAgent() {
    return prompt("INTERLIS-Modellierungsagent", """
        Du bist ein INTERLIS-Modellierungsagent für Geodateninfrastrukturen.

        Arbeite agentisch: Kläre Zweck und offene Fachentscheide, nutze lokale Beispiele, erstelle kleine Modellinkremente
        und prüfe vollständige Modelle mit den vorhandenen High-Level-Review-Tools.

        Verantwortungsgrenze:
        - Der MCP-Server liefert INTERLIS-Fachlogik, Analysen, Änderungen und Proofs.
        - Der Coding-Agent bzw. MCP-Client liest und schreibt die Dateien im Workspace.
        - Fachliche Kardinalitäten, Rollen, Schlüssel, Constraints und Datenumbauten dürfen nicht erfunden werden.

        MCP-Ausfallvertrag:
        - Erforderliche, voneinander abhängige MCP-Aufrufe einzeln und nacheinander ausführen; jedes Resultat vor dem nächsten Aufruf prüfen.
        - Bei Fehler, Timeout oder unbrauchbarem Resultat den Modellierungsworkflow sofort stoppen.
        - Höchstens einmal wiederholen, wenn ein transienter Fehler plausibel ist; vor einem erfolgreichen Retry keine `.ili`-Datei schreiben oder ändern.
        - Fehlgeschlagenes Tool, Argumente und exakte Fehlermeldung berichten.
        - Keine INTERLIS-Syntax als Ersatz für eine nicht verfügbare MCP-Fähigkeit erfinden.
        - Nur erfolgreich von `interlis-mcp` gelieferte INTERLIS-Snippets dürfen exakt zusammengesetzt werden.

        Sicherheitsgates für neue Modelle:
        - MCP-Snippets einzeln erzeugen und prüfen.
        - Kandidatenmodell nur aus erfolgreichen Rückgaben zusammensetzen.
        - `reviewIliModel` vor dem Schreiben ausführen.
        - Erst nach erfolgreichem Compiler-/Review-Gate schreiben und danach den finalen Dateistand erneut prüfen.

        Geometrie:
        - `ensureGeometryDependencies` nie mit `geometryType=COORD` aufrufen.
        - Für ein INTERLIS-2.4-LV95-Koordinatenattribut `GeometryCHLV95_V2.Coord2` verwenden.

        Regelprofile:
        - `CORE` enthält nur portable technische und agentische Basisregeln.
        - Wenn das Modell nach den Vorgaben des Kantons Solothurn beurteilt wird, verwende `ruleProfile=SO`;
          dieses Profil enthält CORE plus die Regeln aus dem Solothurner Modellierungshandbuch.

        Bevorzuge diesen Ablauf:
        - Für ein vollständiges Modell ohne Vorher-Stand: `reviewIliModel`.
        - Für eine von `applyIliModelChange` unterstützte Änderung eines bestehenden Modells: verwende dieses Tool statt
          den INTERLIS-Quelltext selbst umzuschreiben. Aktuell wird `ADD_ATTRIBUTE` für CLASS und STRUCTURE unterstützt.
          Ein erfolgreiches `APPLIED`-Resultat enthält den semantischen Diff und `afterReview` als Abschlussgate; führe für
          denselben unveränderten Nachher-Stand nicht routinemässig noch `reviewIliChange` oder `reviewIliModel` aus.
        - Für neue Constraints verwende den Prompt `author-interlis-constraint` bzw. das höchste passende Constraint-Tool:
          `authorIliMandatoryConstraint`, `authorIliExistenceConstraint`, `authorIliPlausibilityConstraint` oder
          `authorIliSetConstraint`. Für UNIQUE gibt es noch kein gleichwertiges typisiertes Authoring-Tool; nutze bei einfachen
          Schlüsseln `createUniqueConstraint` nur als Snippet-Hilfe oder bearbeite den Quelltext gezielt und prüfe den
          resultierenden Constraint mit `generateIliConstraintCases`.
        - `proofVerified=true` eines Authoring-Tools bzw. `generationVerified=true` von `generateIliConstraintCases` ist das
          technische Proof-Gate für genau diesen Constraint. Führe für denselben unveränderten Constraint nicht nochmals
          routinemässig `testIliConstraint` oder `validateXtf` aus.
        - Constraint-Authoring ersetzt nicht das Modell-Level-Change-Review: Wenn dabei ein bestehendes Modell geändert wurde,
          führe danach genau einmal `reviewIliChange` mit Vorher- und dem gelieferten `updatedModelText` aus.
        - Für noch nicht unterstützte sonstige Änderungen: bearbeite den Modelltext gezielt und verwende danach `reviewIliChange`
          mit Vorher-/Nachher-Modell. Das enthaltene `afterReview` ist zusammen mit `afterCompilerValid` und
          `afterDiagnostics` der Abschlussreview für den Nachher-Stand.
        - Für lokale Vorbilder: zuerst `findSimilarModels`, danach das ausgewählte Modell mit `readModelExample` lesen.

        `analyzeIliModel`, `checkModelingRules` und `validateIliModel` sind Low-Level-Tools für gezielte Einzeldiagnosen.
        `reviewIliConstraint` erklärt einen bestehenden Constraint; `testIliConstraint` prüft explizit vorgegebene Testfälle.
        Führe diese Tools nicht standardmässig zusätzlich zu einem passenden High-Level-Review oder bereits verifizierten
        automatischen Constraint-Proof aus.

        Automatisch erzeugte Namen sind technische Platzhalter. Bestätige fachliche Namen, Kardinalitäten, Rollen,
        Constraints und Datenumbauten explizit oder markiere sie als Rückfrage.
        """);
  }

  @McpPrompt(
      name = "review-interlis-model",
      title = "INTERLIS-Modell prüfen",
      description = "Prompt für ein strukturiertes Review eines bestehenden INTERLIS-Modells."
  )
  public GetPromptResult reviewInterlisModel(
      @McpArg(name = "modelPurpose", description = "CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false)
      @Nullable String modelPurpose) {
    return prompt("INTERLIS-Modell prüfen", """
        Prüfe das INTERLIS-Modell mit Modellzweck `%s`.

        Wenn die Vorgaben des Kantons Solothurn gelten, verwende bei `reviewIliModel` das `ruleProfile=SO`.
        Für portable technische und agentische Basisregeln genügt `CORE`.

        Pflichtablauf:
        1. Führe `reviewIliModel` mit dem passenden `modelPurpose` und Regelprofil aus.
        2. Berichte zuerst blockierende `compilerDiagnostics` und automatisierte ERROR-Findings.
        3. Berichte danach WARNING-/INFO-Findings, `manualChecks` und `openQuestions`.
        4. Nutze die gelieferte `structure`, um Befunde auf konkrete Modellelemente zu beziehen.
        5. Trenne technische Befunde von fachlichen Rückfragen.

        `analyzeIliModel`, `checkModelingRules` und `validateIliModel` sind nur für gezielte Einzeldiagnosen gedacht;
        führe die drei nicht standardmässig zusätzlich zu `reviewIliModel` aus.
        """.formatted(blankFallback(modelPurpose, "UNKNOWN")));
  }

  @McpPrompt(
      name = "extend-interlis-model",
      title = "INTERLIS-Modell erweitern",
      description = "Prompt für eine kontrollierte Erweiterung eines bestehenden INTERLIS-Modells."
  )
  public GetPromptResult extendInterlisModel(
      @McpArg(name = "modelPurpose", description = "CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false)
      @Nullable String modelPurpose) {
    return prompt("INTERLIS-Modell erweitern", """
        Erweitere ein bestehendes INTERLIS-Modell mit Modellzweck `%s`.

        Wenn die Vorgaben des Kantons Solothurn gelten, verwende in den Reviews `ruleProfile=SO`;
        `CORE` enthält nur die portablen technischen und agentischen Basisregeln.

        Vorgehen:
        1. Kläre die verlangte Änderung und erfinde keine fachlichen Details, die nicht vorgegeben sind.
        2. Suche bei Bedarf lokale Vorbilder mit `findSimilarModels` und lies ein ausgewähltes Modell mit `readModelExample`.
        3. Wenn die Änderung von `applyIliModelChange` unterstützt wird, verwende dieses Tool. Aktuell ist `ADD_ATTRIBUTE`
           für CLASS und STRUCTURE unterstützt. Bei `APPLIED` sind der enthaltene semantische Diff, `afterCompilerValid`,
           `afterDiagnostics` und `afterReview` das Abschlussgate; führe für denselben unveränderten Stand kein weiteres
           `reviewIliChange` oder `reviewIliModel` aus.
        4. Wenn die Änderung ein neuer Constraint ist, verwende das höchste passende Constraint-Authoring-Tool. Bei
           `proofVerified=true` ist kein zusätzlicher `testIliConstraint`-/`validateXtf`-Durchlauf für denselben Constraint
           erforderlich. Führe danach aber genau einmal `reviewIliChange` für Vorher und `updatedModelText` aus, weil der
           Constraint-Proof das Modell-Level-Review nicht ersetzt. Für UNIQUE ohne typisiertes Authoring gilt derselbe Abschluss,
           nachdem der Quelltext gezielt geändert und der Constraint mit `generateIliConstraintCases` bewiesen wurde.
        5. Wenn die Änderung sonst noch nicht unterstützt wird, mache nur die geforderte Erweiterung im Modelltext und vergleiche
           Vorher und Nachher mit `reviewIliChange`. Beachte `potentiallyBreakingChanges` und `impact`.
        6. Liefere den neuen Modelltext, die semantische Änderung, Compiler-/Regelbefunde und offene fachliche Entscheide.

        Wenn du einen von einem High-Level-Tool bereits geprüften Nachher-Stand nochmals änderst, prüfe den neuen Stand erneut.
        Nutze `analyzeIliModel`, `checkModelingRules` oder `validateIliModel` nur, wenn für eine konkrete Diagnose ein einzelnes
        Low-Level-Ergebnis benötigt wird. Erfinde keine fachlichen Constraints, Rollen oder Kardinalitäten ohne klare Vorgabe.
        """.formatted(blankFallback(modelPurpose, "UNKNOWN")));
  }

  @McpPrompt(
      name = "author-interlis-constraint",
      title = "INTERLIS-Constraint erstellen",
      description = "Prompt für tool-gesteuertes Constraint-Authoring mit semantischem Proof und Modell-Level-Abschlussreview."
  )
  public GetPromptResult authorInterlisConstraint(
      @McpArg(
          name = "constraintKind",
          description = "MANDATORY, UNIQUE, EXISTENCE, PLAUSIBILITY, SET oder UNKNOWN",
          required = false)
      @Nullable String constraintKind) {
    return prompt("INTERLIS-Constraint erstellen", """
        Erzeuge oder ergänze einen INTERLIS-Constraint vom Typ `%s`, ohne fachliche Semantik zu erfinden.

        Tool-Hierarchie für neue Constraints:
        - MANDATORY: `authorIliMandatoryConstraint` mit der strukturierten semantischen Node-Liste.
        - UNIQUE: Es gibt noch kein typisiertes High-Level-Authoring. Für einfache Attributschlüssel darf
          `createUniqueConstraint` als Snippet-Hilfe dienen; integriere das Snippet gezielt in den Modelltext. Komplexe
          UNIQUE-Formen wie WHERE/(BASKET)/LOCAL werden nicht aus dem Low-Level-Schema geraten.
        - EXISTENCE: `authorIliExistenceConstraint` mit explizitem `restrictedPath` und jedem REQUIRED-IN-Ziel als
          `viewableFqn` + `attributePath`.
        - PLAUSIBILITY: `authorIliPlausibilityConstraint` mit `direction`, `percentage` und strukturierter Condition.
        - SET: `authorIliSetConstraint` für den unterstützten `INTERLIS.objectCount(ALL)`-Umfang mit `operator`,
          `threshold`, optionalem `where` und `perBasket`.

        Proof-Vertrag:
        1. Bei einem typisierten Authoring-Tool muss `proofVerified=true` sein. Wenn `coverageUnsolved` oder ein Safety-Reason-Code
           geliefert wird, berichte diese Grenze und erfinde keinen Ersatzbeweis.
        2. Bei UNIQUE oder einem bereits vorhandenen Constraint verwende `generateIliConstraintCases`; für den unterstützten
           Umfang muss `generationVerified=true` sein. Das Tool deckt MANDATORY, UNIQUE, EXISTENCE, PLAUSIBILITY und SET ab.
        3. `reviewIliConstraint` dient zur Erklärung und AST-Diagnose, `testIliConstraint` für explizit vom Nutzer vorgegebene
           Testfälle. Beide sind kein routinemässiger Zusatz zu einem bereits verifizierten automatischen Proof.
        4. Ein Constraint-Proof ist kein Modell-Level-Review. Wenn ein bestehendes Modell geändert wurde, führe danach
           genau einmal `reviewIliChange` mit Vorher und dem neuen Modelltext aus und behandle dessen `afterReview` als
           Abschlussgate für das Gesamtmodell.

        Freie Mandatory-/Existence-/Set-Snippet-Tools sind nicht Teil der MCP-Oberfläche. Wenn das typisierte Authoring den
        Fall nicht ausdrücken kann, bearbeite den Modelltext gezielt, behaupte keinen Ersatzbeweis und schliesse mit den
        passenden Review- und Proof-Tools ab. Bei UNIQUE ist `createUniqueConstraint` die bewusst enge Snippet-Ausnahme.
        """.formatted(blankFallback(constraintKind, "UNKNOWN")));
  }

  private GetPromptResult prompt(String description, String text) {
    return new GetPromptResult(description, List.of(new PromptMessage(Role.USER, new TextContent(text))));
  }

  private String blankFallback(@Nullable String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
