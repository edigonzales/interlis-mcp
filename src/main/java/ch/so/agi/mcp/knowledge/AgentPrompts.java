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

        Bevorzuge diesen Ablauf:
        - Fuer ein vollstaendiges Modell: `reviewIliModel`.
        - Fuer die Aenderung eines bestehenden Modells: `reviewIliChange` mit Vorher-/Nachher-Modell und danach `reviewIliModel` fuer den finalen Stand.
        - Fuer lokale Vorbilder: zuerst `findSimilarModels`, danach das ausgewaehlte Modell mit `readModelExample` lesen.

        `analyzeIliModel`, `checkModelingRules` und `validateIliModel` sind Low-Level-Tools fuer gezielte Einzeldiagnosen.
        Fuehre sie nicht standardmaessig zusaetzlich zu `reviewIliModel` aus.

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

        Pflichtablauf:
        1. Fuehre `reviewIliModel` mit dem passenden `modelPurpose` aus.
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

        Vorgehen:
        1. Reviewe den bestehenden Modelltext mit `reviewIliModel`, damit Compilerstatus, Struktur, Regeln und offene Fragen bekannt sind.
        2. Suche bei Bedarf lokale Vorbilder mit `findSimilarModels` und lies ein ausgewaehltes Modell mit `readModelExample`.
        3. Mache nur die geforderte Erweiterung und halte bestehende Namen, Imports und Stil konsistent.
        4. Vergleiche Vorher und Nachher mit `reviewIliChange` und beachte insbesondere `potentiallyBreakingChanges` und `impact`.
        5. Reviewe den finalen Modelltext mit `reviewIliModel`.
        6. Liefere den neuen Modelltext, die semantische Aenderung, Compiler-/Regelbefunde und offene fachliche Entscheide.

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
