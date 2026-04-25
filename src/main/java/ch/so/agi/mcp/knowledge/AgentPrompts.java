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

        Arbeite agentisch: klaere Zweck und offene Fachentscheide, nutze lokale Beispiele, erstelle kleine Modellinkremente,
        validiere mit ili2c, analysiere die Modellstruktur und pruefe die kuratierten Modellierungsregeln.

        Verwende mindestens diese Tools, wenn ein vollstaendiges Modell vorliegt:
        - `analyzeIliModel`
        - `checkModelingRules`
        - `validateIliModel`

        Erfinde keine fachlichen Kardinalitaeten, Rollen, Constraints oder Datenumbauten. Markiere solche Punkte als Rueckfrage.
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
        1. Fuehre `analyzeIliModel` aus.
        2. Fuehre `checkModelingRules` mit passendem `modelPurpose` aus.
        3. Fuehre `validateIliModel` aus.
        4. Berichte zuerst blockierende Compilerfehler und automatisierte ERROR-Findings.
        5. Berichte danach WARNING/INFO-Findings und die MANUAL-Checks.
        6. Trenne technische Befunde von fachlichen Rueckfragen.
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
        1. Analysiere zuerst den bestehenden Modelltext mit `analyzeIliModel`.
        2. Suche bei Bedarf lokale Vorbilder mit `findSimilarModels`.
        3. Mache nur die geforderte Erweiterung und halte bestehende Namen, Imports und Stil konsistent.
        4. Fuehre `validateIliModel` und `checkModelingRules` aus.
        5. Liefere den neuen Modelltext, die strukturelle Zusammenfassung, Findings und offene fachliche Entscheide.

        Nicht erlaubt: fachliche Constraints, Rollen oder Kardinalitaeten ohne klare Vorgabe erfinden.
        """.formatted(blankFallback(modelPurpose, "UNKNOWN")));
  }

  private GetPromptResult prompt(String description, String text) {
    return new GetPromptResult(description, List.of(new PromptMessage(Role.USER, new TextContent(text))));
  }

  private String blankFallback(@Nullable String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
