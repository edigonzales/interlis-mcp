package ch.so.agi.mcp.knowledge;

/** Agent-side workflow; does not alter server release flags. */
public final class ConstraintWorkflowGuidance {
  private ConstraintWorkflowGuidance() {}
  public static final String TOOL = " Prosa: Erwartungen vorab festhalten, vor Schreiben mit testIliConstraint prüfen. Test-modelHashes.model muss Authoring-modelHashes.after entsprechen; aktuelle Datei gegen before prüfen. Normal includeSuccessfulTestXtf=false. INVALID_SPEC gezielt einmal korrigieren; offene Fragen klären, Proof-Grenzen berichten. Serverstatus allein ist keine Schreibfreigabe.";
  public static final String TEXT = """

      Prosa zu Constraints: verbindlicher Agentenablauf
      1. Original- und Teilanforderungen erhalten; Zielkontext mit analyzeIliModel(contextFqn)
         prüfen. Betroffene Objekte, Bedingung, Konsequenz, Grenzen und Geltungsbereich festhalten.
         Eindeutige Vorgaben direkt umsetzen, nur fachlich wirksame Mehrdeutigkeiten rückfragen:
         „Wenn A, dann B“ verlangt keine Umkehrung; „mindestens 18“ schliesst 18 ein.
         Bei fehlenden Werten: unzulässig oder nicht anwendbar? Leere Beziehungen verlangen
         nicht automatisch ein Ziel; leere Summen nicht ungeprüft als Null behandeln.
         „eindeutig“ benötigt GLOBAL, BASKET oder LOCAL als fachlich begründeten Bereich.
      2. VOR Authoring zulässige, unzulässige, Grenz- und notwendige Fehlwertfälle festhalten.
         expectationSource: USER_PROVIDED, USER_CONFIRMED, AGENT_DERIVED oder UNSPECIFIED.
         Herkunft ist nicht serverseitig verifiziert. Erwartungen nicht aus dem fertigen
         Constraint ableiten oder nach Fehlern passend ändern. Fehlende Gegenfälle nicht erfinden.
      3. Typisierte Authoring-Tools nutzen; zusammengehörende Änderungen als atomaren Batch.
         Normal includeSuccessfulTestXtf=false wählen: nur erfolgreiche, warnungsfreie Transfers
         entfallen. omittedSuccessfulTestXtfCount nennt die Anzahl; Befunde bleiben vollständig.
         Vollständige Ausgabe nur bei konkretem Diagnosebedarf anfordern, nicht routinemässig.
      4. Ergebnisse anhand bestehender Felder behandeln:
         INVALID_SPEC: mit specDiagnostics genau das betroffene Feld einmal korrigieren,
         ohne Fachsemantik zu ändern; strengere Versuchslimits gelten.
         Vorgelagerte MCP-Schemafehler sind kein Serverstatus INVALID_SPEC und dürfen weder
         im Protokoll noch in der Bewertung so umetikettiert werden; sie erweitern das Versuchslimit nicht.
         NEEDS_INPUT/offene Fachfragen: fehlende Entscheidung einholen.
         Compilerfehler: konkrete Diagnose bearbeiten, keinen unveränderten Aufruf wiederholen.
         PROOF_INCOMPLETE/PROOF_FAILED: Grenze berichten, Kandidaten nicht freigeben.
      5. Nach erfolgreichem Authoring die vorab festgehaltenen Fälle mit testIliConstraint prüfen.
         Das ist zusätzlich zum automatischen Proof erforderlich. modelHashes.model des Tests
         muss modelHashes.after des Authorings entsprechen. Offene Fachentscheide, fehlgeschlagene
         oder nicht ausführbare notwendige Tests, ungültige Fixtures oder nicht ausgeübte Regeln
         verhindern das Schreiben. Ein erfolgreicher Serverstatus allein erlaubt kein Schreiben.
      6. Vor Überschreiben die aktuelle Datei erneut lesen und ihren SHA-256 über den exakten
         UTF-8-Text mit modelHashes.before vergleichen. Bei Abweichung vom aktuellen Stand neu
         ansetzen, nicht automatisch zusammenführen. Zeilenenden und Unicode nicht normalisieren.
         Hashes identifizieren Text, keine Importe oder Validatorumgebung; keine atomare Dateisperre.
      7. explanation und entscheidende Fälle zeigen; PARTIAL/UNAVAILABLE sowie
         CONTRADICTION_PROVEN und andere relevante Interaktionsgrenzen sichtbar berichten.
         constraintInteractions bleiben advisory; technische Statuswerte und Freigaben unverändert.
         Bei requiresUserDecision=true die konkrete Frage und den Umgang damit in der
         Abschlussantwort nennen. Ist eine allgemeine Frage anhand des vorhandenen Auftrags
         für die konkrete Änderung unerheblich, diese Beurteilung dort begründen; das Flag
         bleibt unverändert. Kein Modellzweck wird erfunden, keine pauschale Ausnahme erteilt.
         Tatsächlich offene Fachentscheide verhindern weiterhin das Schreiben; Proofs ersetzen
         diese Beurteilung nicht.

      Einzeltests isolieren den Constraint; AGENT_DERIVED ist keine unabhängige Fachabnahme,
      businessAcceptance bleibt NOT_RUN. Der Server erzwingt die Schreibregel nicht bei fremden
      Clients. Diesen Ablauf bei Prosa-Authoring anwenden, nicht bei reinen Reviews.
      """;
}
