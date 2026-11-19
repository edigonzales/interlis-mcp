package ch.so.agi.mcp.knowledge;

/** Shared guidance for interpreting compiler, proof and interaction results. */
final class ResultEvidenceGuidance {
  private ResultEvidenceGuidance() {}
  static final String TEXT = """

      Prüfnachweise richtig einordnen:
      - Im Authoring stehen die tatsächlichen Compilerphasen unter `beforeCompilation` und `afterCompilation`.
        `constraintProofs: []` bedeutet, dass keine Constraints zu prüfen waren. Fehlt das Feld, wurde
        die Proof-Phase noch nicht erreicht. `PASSED` gilt je Constraint für die erreichte endliche Coverage.
      - Automatisch aus dem Constraint abgeleitete Fälle prüfen dessen technische Wirkung.
        Explizite Nutzererwartungen mit `testIliConstraint` prüfen und ihre Herkunft über `expectationSource` angeben.
      - `constraintInteractions` in Reviews/afterReview untersucht unterstützte skalare Mandatory-Regeln.
        CONTRADICTION_PROVEN betrifft Objekte des genannten Kontexts; ein leerer Transfer kann gültig bleiben.
        SCALAR_ASSIGNMENT_FOUND ist kein validierter Objektgraph. UNKNOWN und unsupported sichtbar berichten.
      - Bei `applyIliModelChanges` bestätigt `APPLIED` die technische Freigabe der angeforderten
        atomaren Änderung. Beurteile die Regelkonformität des resultierenden Gesamtmodells anhand
        von `afterReview.validForAutomatedRules`. Berichte vorhandene `afterReview.ruleFindings`
        auch bei `APPLIED`.
      - `updatedModelText` ist der freigegebene Text. `candidateModelText` bleibt ein Kandidat.
      - `generateExampleXtf` validiert den erzeugten Transfer einmal. `generated=true` bestätigt nur die Erzeugung;
        `validation.status` ist VALID, INVALID, ERROR oder NOT_RUN. Ausgelassene Klassen sind nicht mitgeprüft.
        Für dasselbe unveränderte XTF kein zusätzlicher routinemässiger `validateXtf`-Aufruf.
      """;
}
