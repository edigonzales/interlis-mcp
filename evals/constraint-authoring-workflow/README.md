# Prosa-Authoring-Workflow, Suite 1

Diese neue Suite verändert weder die eingefrorene Constraint-Rekonstruktion noch deren Automation.
Sie untersucht Interpretation, Rückfragen, regelgetreue Erweiterung, zuvor festgelegte Erwartungen
und die zusätzliche Schreibregel des Agenten. Die technischen Tests sind keine Agentenerfolgsquote.

## Durchführung

1. Für jeden Fall einen frischen Agentenkontext mit ausschliesslich `public/<id>/model.ili`,
   `requirement.de.md` und dem aktuellen Authoring-Workflow öffnen. Keine Referenzdateien freigeben.
   Die Eingabe-Hashes aus `suite.json` vor dem Lauf unabhängig prüfen.
2. Native MCP-Aufrufe und Antworten sowie Dialog und Dateischreibvorgänge vollständig aufzeichnen.
   Die Erwartungsfälle vor dem ersten Authoring-Aufruf als unverändertes Artefakt speichern.
   Eine digest-Referenz bindet jeden späteren Test an genau diese Fälle.
3. Q01–Q03 enden bei der erforderlichen Rückfrage ohne Authoring oder Schreiben. Der unabhängige
   Reviewer bewertet den Inhalt der Frage; blosses Auftreten eines Fragezeichens genügt nicht.
4. R01 ist ein gesonderter, kontrollierter Wiederherstellungsfall: Ein Testadapter beschädigt
   ausschliesslich den technischen Namen des ersten sonst gültigen Requests zu `1Adult` und
   lässt ihn vom echten MCP ablehnen. Originalrequest, veränderten Request und reale Antwort
   protokollieren. Danach ist genau eine Reparatur auf `Adult` erlaubt. Dies ist kein natürlicher
   Erstversuch und wird separat ausgewiesen. Keine künstlich als echt ausgegebenen Toolantworten.
5. Ein unabhängiger Prüfer ordnet erzeugte Constraint-FQNs den Schlüsseln in `reference/<id>.json`
   zu. Er testet alle `expectedRules` mit dem echten `testIliConstraint` am geschriebenen Stand.
   Für kombinierte Anforderungen ist `actualValid` die Konjunktion der beobachteten Regelergebnisse.
   Alle relevanten Regeln müssen ausgeübt und die Fixtures gültig sein. Referenzconstraints dienen
   der überprüfbaren Sollsemantik, nicht als Vorlage für den Agenten.
6. Interpretation, vollständige Teilanforderungen und erforderliche Rückfragen unabhängig reviewen.
   Automatische Einzelproofs ersetzen dieses Review nicht. UNKNOWN/CONTRADICTION_PROVEN dürfen
   in der Abschlussdarstellung nicht als uneingeschränkter Gesamterfolg verschwinden.

## Auditformat und Prüfer

`tools/score.py CASE evidence.json` prüft ein aus dem Transkript auditiertes JSON:

- `inputHashes`: dieselben Eingabe-Hashes wie im Manifest.
- `nativeTranscript`, `referenceArtifact`: Referenzen auf die archivierten Originalbelege.
- `independentReview`: `reviewer`, `interpretationCorrect`, `questionsCovered`, `rulesCovered`.
- `events`: chronologische Ereignisse `EXPECTATIONS` mit `digest`, `AUTHOR`, gegebenenfalls
  `REPAIR` mit `reason=INVALID_SPEC`, `TEST` mit `expectationsDigest`, `modelHash`, `allPassed`,
  `fixtureValid`, `constraintExercised`, und `WRITE` mit `modelHash`, `technicalGatePassed`.
- Die `AUTHOR`- und `TEST`-Ereignisse übernehmen die unveränderten `modelHashes` aus der Serverantwort:
  `before`/`after` beim Authoring, `model` beim Test. `WRITE.sourceModelHash` enthält den Hash der
  unmittelbar vor dem Schreiben erneut gelesenen Ausgangsdatei. Diese müssen zusammenpassen.
- `referenceModelHash`: SHA-256 des unabhängig geprüften geschriebenen Modellstands.
- `referenceResults`: pro Referenzfall `id`, `actualValid`, `fixtureValid`, `constraintExercised`.

Die Digest-Werte sind SHA-256 der archivierten Erwartungen beziehungsweise exakten Modellbytes.
Ein erneutes Authoring macht vorausgegangene Tests für das Schreiben ungültig. Der Prüfer
verwirft geänderte Erwartungen, abweichende Modellstände, ungültige Fixtures und ungeprüfte Writes.

Der Offline-Prüfer authentifiziert keine Toolausführung und versteht keine Prosa. Ein Reviewer
muss die Auditangaben gegen die Originalbelege prüfen; eine JSON-Datei allein ist kein Nachweis
für einen nativen Lauf. Die Prüfer-Unit-Tests verwenden ausdrücklich synthetische Belege.

## Abnahmezustand

Suite, Referenzfälle, Validatorprüfungen und adversariale Prüfer-Tests gehören zu `./gradlew check`.
Ein freier nativer Agentenlauf steht aus: In der Implementierungssitzung war kein nativer
INTERLIS-Connector verfügbar. Deshalb wird keine Agentenerfolgsquote angegeben.
