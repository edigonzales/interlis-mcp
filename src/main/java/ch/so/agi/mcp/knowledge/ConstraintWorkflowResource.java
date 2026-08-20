package ch.so.agi.mcp.knowledge;

import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

/** Stable decision hierarchy for agentic constraint authoring and proof. */
@Component
public class ConstraintWorkflowResource {

  @McpResource(
      uri = "interlis://knowledge/constraint-workflow",
      name = "constraint-workflow",
      title = "INTERLIS Constraint Workflow",
      description = "Entscheidungsmatrix fuer Constraint-Authoring, automatischen Validator-Proof und Modell-Level-Abschlussreview.",
      mimeType = "text/markdown"
  )
  public ReadResourceResult constraintWorkflow() {
    String text = """
        # INTERLIS Constraint Workflow

        Bevorzuge immer das hoechste Tool, das die verlangte Constraint-Semantik ausdruecken kann. Trenne dabei drei Ebenen:

        1. **Constraint verstehen**: `reviewIliConstraint` fuer AST, Pfade, Typen und technische Erklaerung.
        2. **Constraint beweisen**: `generateIliConstraintCases` fuer automatisch erzeugte Witnesses, Counterexamples,
           Population-Boundaries und Scope-Faelle; `testIliConstraint` nur fuer explizit vorgegebene Testfaelle.
        3. **Modellaenderung abschliessen**: nach einer Constraint-Aenderung genau einmal `reviewIliChange` fuer Vorher/Nachher.

        ## Neue Constraints

        | Art | Bevorzugtes Authoring | Proof |
        | --- | --- | --- |
        | MANDATORY | `authorIliMandatoryConstraint` | im Authoring enthalten (`proofVerified`) |
        | UNIQUE | kein typed Authoring; bei einfachen Schluesseln `createUniqueConstraint` nur als Snippet-Hilfe | `generateIliConstraintCases` (`generationVerified`) |
        | EXISTENCE | `authorIliExistenceConstraint` | im Authoring enthalten (`proofVerified`) |
        | PLAUSIBILITY | `authorIliPlausibilityConstraint` | im Authoring enthalten (`proofVerified`) |
        | SET | `authorIliSetConstraint` fuer den unterstuetzten `objectCount(ALL)`-Umfang | im Authoring enthalten (`proofVerified`) |

        `createMandatoryConstraint`, `createExistenceConstraint` und `createSetConstraint` sind fuer neue Agent-Workflows
        Legacy-/Snippet-Helper. Nutze sie nicht, wenn das typed Authoring-Tool den Fall ausdruecken kann.

        ## Bestehende Constraints

        - Erklaerung/Diagnose: `reviewIliConstraint`.
        - Automatischer semantischer Proof: `generateIliConstraintCases` fuer MANDATORY, UNIQUE, EXISTENCE,
          PLAUSIBILITY und den unterstuetzten SET-Umfang.
        - `generationVerified=true` bedeutet, dass alle erzeugten Proof-Faelle mit dem echten ilivalidator bestaetigt wurden.
        - `coverageUnsolved` oder ein Safety-Reason-Code ist eine bewusste Grenze. Nicht durch angenaeherte Semantik ersetzen.

        ## Abschlussregeln

        - `proofVerified=true` bzw. `generationVerified=true` ist das technische Proof-Gate fuer genau diesen Constraint.
          Fuer denselben unveraenderten Constraint nicht routinemaessig nochmals `testIliConstraint`, `validateXtf` oder
          eine zweite automatische Proof-Runde ausfuehren.
        - Ein Constraint-Proof ersetzt **nicht** das Modell-Level-Review. Wenn ein bestehendes Modell geaendert wurde,
          fuehre genau einmal `reviewIliChange(before, after)` aus. Dessen `afterReview`, `afterCompilerValid` und
          `afterDiagnostics` bilden das Abschlussgate fuer den gesamten Nachher-Stand.
        - Wenn der Nachher-Stand danach erneut geaendert wird, muss der neue Stand erneut geprueft werden.

        ## Wichtige Safety-Grenzen

        - EXISTENCE REFERENCE/COORD/komplexe Geometrie wird nicht als skalare Ersatzsemantik approximiert.
        - PLAUSIBILITY wird mit echten Populationen bewiesen, nicht als Mandatory Constraint emuliert.
        - SET beweist aktuell plain `ALL` mit `INTERLIS.objectCount`, optionalem direktem WHERE und `(BASKET)`-Scope;
          `ALL(base/RESTRICTION)` und geometry-aware SET-Funktionen bleiben explizite Grenzen.
        - UNIQUE `WHERE`, `(BASKET)` und `LOCAL` sind im Proof unterstuetzt, aber noch nicht durch ein typed High-Level-Authoring abgedeckt.
        """;
    return new ReadResourceResult(List.of(new TextResourceContents(
        "interlis://knowledge/constraint-workflow",
        "text/markdown",
        text)));
  }
}
