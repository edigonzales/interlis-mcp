package ch.so.agi.mcp.knowledge;

/** Shared interpretation guidance; advisory evidence never changes existing release gates. */
final class ResultEvidenceGuidance {
  private ResultEvidenceGuidance() {}
  static final String TEXT = """

      Prüfnachweise richtig einordnen:
      - `evidence` trennt Compiler, automatische Modellierungsregeln, Constraint-Tests,
        Constraint-Zusammenspiel und fachliche Akzeptanz. NOT_RUN ist kein bestandener Test;
        NOT_APPLICABLE bedeutet, dass in diesem Umfang nichts zu prüfen war.
      - Automatisch aus dem Constraint abgeleitete Fälle prüfen dessen technische Wirkung.
        Explizite Nutzererwartungen mit `testIliConstraint` prüfen; ihre fachliche Herkunft
        wird vom Server nicht verifiziert. `businessAcceptance` bleibt NOT_RUN.
      - `constraintInteractions` in Reviews/afterReview untersucht unterstützte skalare Mandatory-Regeln.
        CONTRADICTION_PROVEN betrifft Objekte des genannten Kontexts; ein leerer Transfer kann gültig bleiben.
        SCALAR_ASSIGNMENT_FOUND ist kein validierter Objektgraph. UNKNOWN und unsupported sichtbar berichten.
      - Neue Nachweise sind ergänzend: bestehende Statuswerte, updatedModelText und Freigaberegeln gelten weiter.
      - `generateExampleXtf` validiert den erzeugten Transfer einmal. `generated=true` bestätigt nur die Erzeugung;
        `validation.status` ist VALID, INVALID, ERROR oder NOT_RUN. Ausgelassene Klassen sind nicht mitgeprüft.
        Für dasselbe unveränderte XTF kein zusätzlicher routinemässiger `validateXtf`-Aufruf.
      """;
}
