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

  @McpPrompt(name = "interlis-modeling-agent", title = "INTERLIS-Modellierung",
      description = "Werkzeugwahl und Prüfschritte für einen Modellierungsauftrag.")
  public GetPromptResult interlisModelingAgent() {
    return prompt("INTERLIS-Modellierung", """
        Verwende `authorIliModel` für ein neues Modell, `reviewIliModel` für einen bestehenden
        Stand und `verifyIliModel` für dessen automatische Constraint-Nachweise. Änderungen
        am bestehenden Modell gehören in `applyIliModelChanges` oder für eine einzelne neue
        Regel in `authorIliConstraint`. Bei zwei separat bearbeiteten Ständen nutze `reviewIliChange`.

        Kläre den Modellzweck und offene Fachfragen, soweit sie die Entscheidung beeinflussen.
        Prüfe `afterCompilation.valid`, `afterReview` und erforderliche Proofs am Ergebnis.
        Berichte manuelle Checks und verbleibende Nachweislücken. Für neue fachliche Regeln
        prüfe vorab festgelegte Erwartungen mit `testIliConstraint` am freigegebenen Modelltext.
        Halte Kandidaten und Änderungen mit offenen fachlichen Entscheidungen zurück.
        """);
  }

  @McpPrompt(name = "review-interlis-model", title = "INTERLIS-Modell prüfen",
      description = "Compilerbefunde, Modellierungsregeln und manuelle Prüfungen auswerten.")
  public GetPromptResult reviewInterlisModel(
      @McpArg(name = "modelPurpose", description = "CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false)
      @Nullable String modelPurpose) {
    return prompt("INTERLIS-Modell prüfen", """
        Führe `reviewIliModel` für den vollständigen Modelltext aus. Modellzweck: %s.
        Kläre eine offene Zweckfrage, wenn sie die Beurteilung ändert. Wähle das vereinbarte Regelprofil.
        Berichte Compilerdiagnostik, `ruleFindings`, `manualChecks` und `openQuestions`.
        Behandle `constraintInteractions` als beratende Befunde zum angegebenen Kontext.
        Für automatische Nachweise aller deklarierten Constraints verwende `verifyIliModel`;
        prüfe eigenständige fachliche Erwartungen mit `testIliConstraint`.
        """.formatted(purpose(modelPurpose)));
  }

  @McpPrompt(name = "extend-interlis-model", title = "INTERLIS-Modell erweitern",
      description = "Zusammengehörende Änderungen atomar anwenden und prüfen.")
  public GetPromptResult extendInterlisModel(
      @McpArg(name = "modelPurpose", description = "CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false)
      @Nullable String modelPurpose) {
    return prompt("INTERLIS-Modell erweitern", """
        Verwende `applyIliModelChanges` für einen atomaren, quelltexttreuen Batch. Modellzweck: %s.
        Füge zusammengehörende neue Attribute und Constraints im selben Batch hinzu.
        Setze `allowPotentiallyBreaking=true` erst nach ausdrücklicher Bestätigung.
        Prüfe `semanticDiff`, `afterCompilation.valid`, `afterReview.validForAutomatedRules`,
        `afterReview.ruleFindings` und die Proofs der geänderten Constraints. Kläre blockierende
        Fragen aus `openQuestions`. Bei manuellen Änderungen prüfe beide Stände mit `reviewIliChange`.

        Halte ungeprüfte Kandidaten zurück. Prüfe fachliche Erwartungen an neue Regeln mit
        `testIliConstraint` am vorgeschlagenen Nachher-Stand. Vergleiche vor einem autorisierten
        Überschreiben den aktuellen Dateiinhalt mit `modelHashes.before`.
        """.formatted(purpose(modelPurpose)));
  }

  @McpPrompt(name = "author-interlis-constraint", title = "INTERLIS-Constraint erstellen",
      description = "Typisierten Constraint erstellen und fachliche Erwartungen prüfen.")
  public GetPromptResult authorInterlisConstraint(
      @McpArg(name = "constraintKind", description = "MANDATORY, UNIQUE, EXISTENCE, PLAUSIBILITY, SET oder UNKNOWN", required = false)
      @Nullable String constraintKind) {
    return prompt("INTERLIS-Constraint erstellen", """
        Wähle `authorIliConstraint` mit `contextFqn` und dem fachlich passenden `spec.kind`.
        Gewünschte Art: %s. Prüfe unbekannte Attribute und Beziehungen mit `analyzeIliModel`,
        `findConstraintPaths` oder `resolveConstraintPath`. Kläre Mehrdeutigkeiten über Fehlwerte,
        Grenzinklusion, leere Mengen und Geltungsbereich, wenn sie das Ergebnis ändern.

        MANDATORY nutzt `condition`, z.B. für ein vorhandenes Attribut value:
        {"kind":"MANDATORY","name":"ValueRequired","condition":{"kind":"DEFINED","children":[{"kind":"ATTRIBUTE","name":"value"}]}}
        UNIQUE braucht `scope` und `keyPaths`, bei LOCAL auch `localPrefix`.
        EXISTENCE braucht `restrictedPath` und `requiredIn` mit `viewableFqn` und `attributePath`.
        PLAUSIBILITY braucht `direction`, `percentage` und `condition`. SET braucht `scope`
        und eine `condition` vom Typ BOOLEAN_EXPRESSION oder OBJECT_COUNT.

        Halte positive, negative und Grenzfälle vor dem Authoring fest. Prüfe
        `afterCompilation.valid`, `constraintProofs[].status=PASSED` und `afterReview`.
        Bestehende Regeln automatisch mit `generateIliConstraintCases` prüfen, unabhängige
        Fachfälle mit `testIliConstraint` ausführen. Dessen `modelHashes.model` muss dem
        `modelHashes.after` des Authorings entsprechen. Berichte offene Fragen, Coverage-Gaps
        und externe Funktionssemantik. Solange erforderliche Nachweise oder Entscheidungen
        fehlen, bleibt `candidateModelText` ein Entwurf.
        """.formatted(purpose(constraintKind)));
  }

  private GetPromptResult prompt(String description, String text) {
    return new GetPromptResult(description, List.of(new PromptMessage(Role.USER, new TextContent(text))));
  }

  private String purpose(@Nullable String value) {
    return value == null || value.isBlank() ? "UNKNOWN" : value;
  }
}
