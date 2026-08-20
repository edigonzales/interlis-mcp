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
      title = "INTERLIS Modeling Agent",
      description = "Kompakte Systemanweisung fuer agentisches INTERLIS-Modellieren."
  )
  public GetPromptResult interlisModelingAgent() {
    return prompt("INTERLIS Modeling Agent", """
        Du bist ein INTERLIS-Modellierungsagent fuer Geodateninfrastrukturen.

        Arbeite agentisch: klaere Zweck und offene Fachentscheide, nutze lokale Beispiele, erstelle kleine Modellinkremente
        und pruefe vollstaendige Modelle mit den vorhandenen High-Level-Review-Tools.

        Regelprofile:
        - `CORE` enthaelt nur portable technische/agentische Basisregeln.
        - Wenn das Modell nach den Vorgaben des Kantons Solothurn beurteilt wird, verwende `ruleProfile=SO`;
          dieses Profil enthaelt CORE plus die Regeln aus dem Solothurner Modellierungshandbuch.

        Bevorzuge diesen Ablauf:
        - Fuer ein vollstaendiges Modell ohne Vorher-Stand: `reviewIliModel`.
        - Fuer eine von `applyIliModelChange` unterstuetzte Aenderung eines bestehenden Modells: verwende dieses Tool statt
          den INTERLIS-Quelltext selbst umzuschreiben. Aktuell wird `ADD_ATTRIBUTE` fuer CLASS und STRUCTURE unterstuetzt.
          Ein erfolgreiches `APPLIED`-Resultat enthaelt den semantischen Diff und `afterReview` als Abschlussgate; rufe fuer
          denselben unveraenderten Nachher-Stand nicht routinemaessig noch `reviewIliChange` oder `reviewIliModel` auf.
        - Fuer neue Constraints verwende den Prompt `author-interlis-constraint` bzw. das hoechste passende Constraint-Tool:
          `authorIliMandatoryConstraint`, `authorIliExistenceConstraint`, `authorIliPlausibilityConstraint` oder
          `authorIliSetConstraint`. Fuer UNIQUE gibt es noch kein gleichwertiges typed Authoring-Tool; nutze bei einfachen
          Schluesseln `createUniqueConstraint` nur als Snippet-Hilfe oder bearbeite den Quelltext gezielt und pruefe den
          resultierenden Constraint mit `generateIliConstraintCases`.
        - `proofVerified=true` eines Authoring-Tools bzw. `generationVerified=true` von `generateIliConstraintCases` ist das
          technische Proof-Gate fuer genau diesen Constraint. Fuehre fuer denselben unveraenderten Constraint nicht nochmals
          routinemaessig `testIliConstraint` oder `validateXtf` aus.
        - Constraint-Authoring ersetzt nicht das Modell-Level-Change-Review: wenn dabei ein bestehendes Modell geaendert wurde,
          fuehre danach genau einmal `reviewIliChange` mit Vorher- und dem gelieferten `updatedModelText` aus.
        - Fuer noch nicht unterstuetzte sonstige Aenderungen: bearbeite den Modelltext gezielt und verwende danach `reviewIliChange`
          mit Vorher-/Nachher-Modell. Das enthaltene `afterReview` ist zusammen mit `afterCompilerValid` und
          `afterDiagnostics` der Abschlussreview fuer den Nachher-Stand.
        - Fuer lokale Vorbilder: zuerst `findSimilarModels`, danach das ausgewaehlte Modell mit `readModelExample` lesen.

        `analyzeIliModel`, `checkModelingRules` und `validateIliModel` sind Low-Level-Tools fuer gezielte Einzeldiagnosen.
        `reviewIliConstraint` erklaert einen bestehenden Constraint; `testIliConstraint` prueft explizit vorgegebene Testfaelle.
        Fuehre diese Tools nicht standardmaessig zusaetzlich zu einem passenden High-Level-Review oder bereits verifizierten
        automatischen Constraint-Proof aus.

        Automatisch erzeugte Namen sind technische Platzhalter. Bestaetige fachliche Namen, Kardinalitaeten, Rollen,
        Constraints und Datenumbauten explizit oder markiere sie als Rueckfrage.
        """);
  }

  @McpPrompt(
      name = "review-interlis-model",
      title = "Review INTERLIS Model",
      description = "Prompt fuer ein strukturiertes Review eines bestehenden INTERLIS-Modells."
  )
  public GetPromptResult reviewInterlisModel(
      @McpArg(name = "modelPurpose", description = "CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false)
      @Nullable String modelPurpose) {
    return prompt("Review INTERLIS Model", """
        Reviewe das INTERLIS-Modell mit Modellzweck `%s`.

        Wenn die Vorgaben des Kantons Solothurn gelten, verwende bei `reviewIliModel` das `ruleProfile=SO`.
        Fuer portable technische/agentische Basisregeln genuegt `CORE`.

        Pflichtablauf:
        1. Fuehre `reviewIliModel` mit dem passenden `modelPurpose` und Regelprofil aus.
        2. Berichte zuerst blockierende `compilerDiagnostics` und automatisierte ERROR-Findings.
        3. Berichte danach WARNING/INFO-Findings, `manualChecks` und `openQuestions`.
        4. Nutze die gelieferte `structure`, um Befunde auf konkrete Modellelemente zu beziehen.
        5. Trenne technische Befunde von fachlichen Rueckfragen.

        `analyzeIliModel`, `checkModelingRules` und `validateIliModel` sind nur fuer gezielte Einzeldiagnosen gedacht;
        fuehre die drei nicht standardmaessig zusaetzlich zu `reviewIliModel` aus.
        """.formatted(blankFallback(modelPurpose, "UNKNOWN")));
  }

  @McpPrompt(
      name = "extend-interlis-model",
      title = "Extend INTERLIS Model",
      description = "Prompt fuer eine kontrollierte Erweiterung eines bestehenden INTERLIS-Modells."
  )
  public GetPromptResult extendInterlisModel(
      @McpArg(name = "modelPurpose", description = "CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false)
      @Nullable String modelPurpose) {
    return prompt("Extend INTERLIS Model", """
        Erweitere ein bestehendes INTERLIS-Modell mit Modellzweck `%s`.

        Wenn die Vorgaben des Kantons Solothurn gelten, verwende in den Reviews `ruleProfile=SO`;
        `CORE` enthaelt nur die portablen technischen/agentischen Basisregeln.

        Vorgehen:
        1. Klaere die verlangte Aenderung und erfinde keine fachlichen Details, die nicht vorgegeben sind.
        2. Suche bei Bedarf lokale Vorbilder mit `findSimilarModels` und lies ein ausgewaehltes Modell mit `readModelExample`.
        3. Wenn die Aenderung von `applyIliModelChange` unterstuetzt wird, verwende dieses Tool. Aktuell ist `ADD_ATTRIBUTE`
           fuer CLASS und STRUCTURE unterstuetzt. Bei `APPLIED` sind der enthaltene semantische Diff, `afterCompilerValid`,
           `afterDiagnostics` und `afterReview` das Abschlussgate; fuehre fuer denselben unveraenderten Stand kein weiteres
           `reviewIliChange` oder `reviewIliModel` aus.
        4. Wenn die Aenderung ein neuer Constraint ist, verwende das hoechste passende Constraint-Authoring-Tool. Bei
           `proofVerified=true` ist kein zusaetzlicher `testIliConstraint`-/`validateXtf`-Durchlauf fuer denselben Constraint
           erforderlich. Fuehre danach aber genau einmal `reviewIliChange` fuer Vorher und `updatedModelText` aus, weil der
           Constraint-Proof das Modell-Level-Review nicht ersetzt. Fuer UNIQUE ohne typed Authoring gilt derselbe Abschluss,
           nachdem der Quelltext gezielt geaendert und der Constraint mit `generateIliConstraintCases` bewiesen wurde.
        5. Wenn die Aenderung sonst noch nicht unterstuetzt wird, mache nur die geforderte Erweiterung im Modelltext und vergleiche
           Vorher und Nachher mit `reviewIliChange`. Beachte `potentiallyBreakingChanges` und `impact`.
        6. Liefere den neuen Modelltext, die semantische Aenderung, Compiler-/Regelbefunde und offene fachliche Entscheide.

        Wenn du einen von einem High-Level-Tool bereits geprueften Nachher-Stand nochmals aenderst, pruefe den neuen Stand erneut.
        Nutze `analyzeIliModel`, `checkModelingRules` oder `validateIliModel` nur, wenn fuer eine konkrete Diagnose ein einzelnes
        Low-Level-Ergebnis benoetigt wird. Erfinde keine fachlichen Constraints, Rollen oder Kardinalitaeten ohne klare Vorgabe.
        """.formatted(blankFallback(modelPurpose, "UNKNOWN")));
  }

  @McpPrompt(
      name = "author-interlis-constraint",
      title = "Author INTERLIS Constraint",
      description = "Prompt fuer tool-gesteuertes Constraint-Authoring mit semantischem Proof und Modell-Level-Abschlussreview."
  )
  public GetPromptResult authorInterlisConstraint(
      @McpArg(
          name = "constraintKind",
          description = "MANDATORY, UNIQUE, EXISTENCE, PLAUSIBILITY, SET oder UNKNOWN",
          required = false)
      @Nullable String constraintKind) {
    return prompt("Author INTERLIS Constraint", """
        Erzeuge oder ergaenze einen INTERLIS-Constraint vom Typ `%s`, ohne fachliche Semantik zu erfinden.

        Tool-Hierarchie fuer neue Constraints:
        - MANDATORY: `authorIliMandatoryConstraint` mit der strukturierten semantischen Node-Liste.
        - UNIQUE: Es gibt noch kein typed High-Level-Authoring. Fuer einfache Attributschluessel darf
          `createUniqueConstraint` als Snippet-Hilfe dienen; integriere das Snippet gezielt in den Modelltext. Komplexe
          UNIQUE-Formen wie WHERE/(BASKET)/LOCAL werden nicht aus dem Low-Level-Schema geraten.
        - EXISTENCE: `authorIliExistenceConstraint` mit explizitem `restrictedPath` und jedem REQUIRED-IN-Ziel als
          `viewableFqn` + `attributePath`.
        - PLAUSIBILITY: `authorIliPlausibilityConstraint` mit `direction`, `percentage` und strukturierter Condition.
        - SET: `authorIliSetConstraint` fuer den unterstuetzten `INTERLIS.objectCount(ALL)`-Umfang mit `operator`,
          `threshold`, optionalem `where` und `perBasket`.

        Proof-Vertrag:
        1. Bei einem typed Authoring-Tool muss `proofVerified=true` sein. Wenn `coverageUnsolved` oder ein Safety-Reason-Code
           geliefert wird, berichte diese Grenze und erfinde keinen Ersatzbeweis.
        2. Bei UNIQUE oder einem bereits vorhandenen Constraint verwende `generateIliConstraintCases`; fuer den unterstuetzten
           Umfang muss `generationVerified=true` sein. Das Tool deckt MANDATORY, UNIQUE, EXISTENCE, PLAUSIBILITY und SET ab.
        3. `reviewIliConstraint` dient zur Erklaerung/AST-Diagnose, `testIliConstraint` fuer explizit vom Nutzer vorgegebene
           Testfaelle. Beide sind kein routinemaessiger Zusatz zu einem bereits verifizierten automatischen Proof.
        4. Ein Constraint-Proof ist kein Modell-Level-Review. Wenn ein bestehendes Modell geaendert wurde, fuehre danach
           genau einmal `reviewIliChange` mit Vorher und dem neuen Modelltext aus und behandle dessen `afterReview` als
           Abschlussgate fuer das Gesamtmodell.

        Verwende Legacy-/Snippet-Tools wie `createMandatoryConstraint`, `createExistenceConstraint` und `createSetConstraint`
        nicht fuer neue Regeln, wenn das entsprechende typed Authoring-Tool den Fall ausdruecken kann. Bei UNIQUE ist
        `createUniqueConstraint` nur die bewusst eng begrenzte Snippet-Ausnahme; der semantische Validator-Proof folgt separat.
        """.formatted(blankFallback(constraintKind, "UNKNOWN")));
  }

  private GetPromptResult prompt(String description, String text) {
    return new GetPromptResult(description, List.of(new PromptMessage(Role.USER, new TextContent(text))));
  }

  private String blankFallback(@Nullable String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
