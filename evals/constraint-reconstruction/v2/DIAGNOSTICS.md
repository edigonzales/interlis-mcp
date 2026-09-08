# Technische Diagnose und Grenzen der bisherigen Prüfung

## Drei ursprüngliche Testfehler

Die erste gezielte Ausführung meldete bei P01/P02 `CANDIDATE_MODEL_INVALID` und bei N11 nur eine statt zwei Kompilierungen. Die damaligen Assertions enthielten keine vollständigen Compilerdiagnosen. Nach Ergänzung dieser Diagnosen bestanden sowohl der isolierte Wiederholungslauf als auch dieselbe Kombination aller 16 Tests. Auch die anschließende vollständige Testsuite war grün.

Die ursprüngliche Ursache ist damit **nicht nachgewiesen**, und es wurde kein Authoring-Fix zur vermeintlichen Behebung dieser drei Fehler vorgenommen. Insbesondere darf aus dem grünen Wiederholungslauf nicht rückwirkend ein Server- oder Harness-Fehler behauptet werden. Die neue v2-Prüfung entfernt die bisher unkontrollierte Auflösung lebender Modell-Repositories als Einflussgröße: alle zwölf Public-/Goldmodelle und Referenz-Requests werden gegen denselben gehashten lokalen Importbestand geprüft. Ein erneutes Auftreten muss anhand der jetzt erhaltenen vollständigen Diagnosen zugeordnet werden.

## Gefundener Fehler im neuen Prüfer

Die ursprüngliche Implementierung des unabhängigen Vergleichs behandelte automatisch nummerierte `ConstraintN`-Namen als stabile Identität. Das Wiederherstellen von N11 verschiebt jedoch die Nummern nachfolgender unbenannter Constraints. Das führte zu einer falschen Meldung zusätzlicher Modelländerungen. Der Prüfer unterscheidet jetzt explizite INTERLIS-2.4-Namen, INTERLIS-2.3-Metadaten-Namen und rein automatische Nummern. Zwei eigenständige Regressionen verhindern diese Fehlbewertung. Die ursprünglichen Suite-Modelle mussten dafür nicht geändert werden.

## Technischer Nachweis vor fachlicher Freigabe

- Alle zwölf öffentlichen Modelle und Corpus-Goldmodelle kompilieren offline.
- Die unabhängigen Gold-ASTs sind vollständig, insbesondere für P10 und N12. Existenz-Zielklassen gehen nicht mehr verloren.
- Alle zwölf festen Payloads passen zu den tatsächlich registrierten MCP-Schemas und bestehen die Prüfung über die registrierten Handler: zehn positive Ergebnisse mit vollständigem Proof und zwei korrekt ausgewiesene externe Semantikgrenzen.
- Die Bewertungslogik weist falsche Operatoren, geänderte Existenz-Ziele, fehlende SUM-Präsenz, Reihenfolgeänderungen, Fremdänderungen, falsche Boundary-Erfolge, fehlende Rohresultate, falsche Laufzeitidentität und wiederverwendete Agenten zurück.
- JAR und Modellimporte werden für den Connector unveränderlich nach Inhalt abgelegt. Der Vorbereitungsmodus startet keinen MCP-Prozess.

Diese technischen Handler-/Compilerprüfungen sind **keine nativen Benchmark-Abnahmerunden**. Die fachliche Freigabe liegt seit 8. September 2026 vor. Die drei vollständigen nativen Runden, ihre End-to-End-Streuung und die Aktivierung der Automation stehen weiterhin aus. Die aktuelle Sitzung meldete beim nativen Canary und einem identischen Wiederholungsaufruf jeweils `Transport closed`; daraus entsteht kein MCP-Leistungsscore. Der neue Connector-Launcher ist konfiguriert, benötigt aber eine frische native Verbindung. Die Isolation wird über getrennte Agentenkontexte und tatsächliche Rollenverläufe geprüft; sie ist keine Betriebssystem-Sandbox.
