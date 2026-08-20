package ch.so.agi.mcp.knowledge;

import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

/** Stabile Entscheidungshierarchie für Constraint-Authoring und Proofs. */
@Component
public class ConstraintWorkflowResource {

  @McpResource(
      uri = "interlis://knowledge/constraint-workflow",
      name = "constraint-workflow",
      title = "Arbeitsablauf für INTERLIS-Constraints",
      description = "Entscheidungsmatrix für Constraint-Authoring, automatischen Validator-Proof und Modell-Level-Abschlussreview.",
      mimeType = "text/markdown"
  )
  public ReadResourceResult constraintWorkflow() {
    String text = """
        # Arbeitsablauf für INTERLIS-Constraints

        Bevorzuge immer das höchste Tool, das die verlangte Constraint-Semantik ausdrücken kann. Trenne dabei drei Ebenen:

        1. **Constraint verstehen**: `reviewIliConstraint` für AST, Pfade, Typen und technische Erklärung.
        2. **Constraint beweisen**: `generateIliConstraintCases` für automatisch erzeugte Witnesses, Counterexamples,
           Populationsgrenzen und Scope-Fälle; `testIliConstraint` nur für explizit vorgegebene Testfälle.
        3. **Modelländerung abschliessen**: nach einer Constraint-Änderung genau einmal `reviewIliChange` für Vorher/Nachher.

        ## Neue Constraints

        | Art | Bevorzugtes Authoring | Proof |
        | --- | --- | --- |
        | MANDATORY | `authorIliMandatoryConstraint` | im Authoring enthalten (`proofVerified`) |
        | UNIQUE | kein typisiertes Authoring; bei einfachen Schlüsseln `createUniqueConstraint` nur als Snippet-Hilfe | `generateIliConstraintCases` (`generationVerified`) |
        | EXISTENCE | `authorIliExistenceConstraint` | im Authoring enthalten (`proofVerified`) |
        | PLAUSIBILITY | `authorIliPlausibilityConstraint` | im Authoring enthalten (`proofVerified`) |
        | SET | `authorIliSetConstraint` für den unterstützten `objectCount(ALL)`-Umfang | im Authoring enthalten (`proofVerified`) |

        `createMandatoryConstraint`, `createExistenceConstraint` und `createSetConstraint` sind für neue Agenten-Workflows
        Legacy-/Snippet-Helper. Nutze sie nicht, wenn das typisierte Authoring-Tool den Fall ausdrücken kann.

        ## Bestehende Constraints

        - Erklärung und Diagnose: `reviewIliConstraint`.
        - Automatischer semantischer Proof: `generateIliConstraintCases` für MANDATORY, UNIQUE, EXISTENCE,
          PLAUSIBILITY und den unterstützten SET-Umfang.
        - `generationVerified=true` bedeutet, dass alle erzeugten Proof-Fälle mit dem echten ilivalidator bestätigt wurden.
        - `coverageUnsolved` oder ein Safety-Reason-Code ist eine bewusste Grenze. Nicht durch angenäherte Semantik ersetzen.

        ## Abschlussregeln

        - `proofVerified=true` bzw. `generationVerified=true` ist das technische Proof-Gate für genau diesen Constraint.
          Für denselben unveränderten Constraint nicht routinemässig nochmals `testIliConstraint`, `validateXtf` oder
          eine zweite automatische Proof-Runde ausführen.
        - Ein Constraint-Proof ersetzt **nicht** das Modell-Level-Review. Wenn ein bestehendes Modell geändert wurde,
          führe genau einmal `reviewIliChange(before, after)` aus. Dessen `afterReview`, `afterCompilerValid` und
          `afterDiagnostics` bilden das Abschlussgate für den gesamten Nachher-Stand.
        - Wenn der Nachher-Stand danach erneut geändert wird, muss der neue Stand erneut geprüft werden.

        ## Wichtige Safety-Grenzen

        - EXISTENCE REFERENCE/COORD/komplexe Geometrie wird nicht als skalare Ersatzsemantik approximiert.
        - PLAUSIBILITY wird mit echten Populationen bewiesen, nicht als Mandatory Constraint emuliert.
        - SET beweist aktuell plain `ALL` mit `INTERLIS.objectCount`, optionalem direktem WHERE und `(BASKET)`-Scope;
          `ALL(base/RESTRICTION)` und geometry-aware SET-Funktionen bleiben explizite Grenzen.
        - UNIQUE `WHERE`, `(BASKET)` und `LOCAL` sind im Proof unterstützt, aber noch nicht durch ein typisiertes High-Level-Authoring abgedeckt.
        """;
    return new ReadResourceResult(List.of(new TextResourceContents(
        "interlis://knowledge/constraint-workflow",
        "text/markdown",
        text)));
  }
}
