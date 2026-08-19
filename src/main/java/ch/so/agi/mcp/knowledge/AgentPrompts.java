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
        - Fuer noch nicht unterstuetzte Aenderungen: bearbeite den Modelltext gezielt und verwende danach `reviewIliChange`
          mit Vorher-/Nachher-Modell. Das enthaltene `afterReview` ist zusammen mit `afterCompilerValid` und
          `afterDiagnostics` der Abschlussreview fuer den Nachher-Stand.
        - Fuer lokale Vorbilder: zuerst `findSimilarModels`, danach das ausgewaehlte Modell mit `readModelExample` lesen.

        `analyzeIliModel`, `checkModelingRules` und `validateIliModel` sind Low-Level-Tools fuer gezielte Einzeldiagnosen.
        Fuehre sie nicht standardmaessig zusaetzlich zu einem passenden High-Level-Review aus.

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
        4. Wenn die Aenderung noch nicht unterstuetzt wird, mache nur die geforderte Erweiterung im Modelltext und vergleiche
           Vorher und Nachher mit `reviewIliChange`. Beachte `potentiallyBreakingChanges` und `impact`.
        5. Liefere den neuen Modelltext, die semantische Aenderung, Compiler-/Regelbefunde und offene fachliche Entscheide.

        Wenn du einen von einem High-Level-Tool bereits geprueften Nachher-Stand nochmals aenderst, pruefe den neuen Stand erneut.
        Nutze `analyzeIliModel`, `checkModelingRules` oder `validateIliModel` nur, wenn fuer eine konkrete Diagnose ein einzelnes
        Low-Level-Ergebnis benoetigt wird. Erfinde keine fachlichen Constraints, Rollen oder Kardinalitaeten ohne klare Vorgabe.
        """.formatted(blankFallback(modelPurpose, "UNKNOWN")));
  }

  private GetPromptResult prompt(String description, String text) {
    return new GetPromptResult(description, List.of(new PromptMessage(Role.USER, new TextContent(text))));
  }

  private String blankFallback(@Nullable String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
