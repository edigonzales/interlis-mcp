# Native Abnahme v2 vom 8. September 2026

**ACCEPTED.** Drei vollständige native Läufe derselben Suite-Revision 2.0.3 wurden unabhängig abgeschlossen und durch `tools/acceptance.py` verglichen. [Maschineller Abnahmebeleg](/Users/stefan/.codex/automations/interlis-mcp-constraint-benchmark/acceptance-v2-20260908.json). Keine Auswahl des besten End-to-End-Ergebnisses.

| Runde | End-to-End positiv | End-to-End Grenzen | MCP-Referenz positiv | MCP-Referenz Grenzen |
| --- | --- | --- | --- | --- |
| [1](/Users/stefan/.codex/automations/interlis-mcp-constraint-benchmark/runs-v2/20260908T102256Z-ea40b71-af83cc/REPORT.md) | 7/10 | 0/2 | 10/10 | 2/2 |
| [2](/Users/stefan/.codex/automations/interlis-mcp-constraint-benchmark/runs-v2/20260908T114332Z-ea40b71-4702a6/REPORT.md) | 4/10 | 1/2 | 10/10 | 2/2 |
| [3](/Users/stefan/.codex/automations/interlis-mcp-constraint-benchmark/runs-v2/20260908T122250Z-ea40b71-22d334/REPORT.md) | 5/10 | 1/2 | 10/10 | 2/2 |

Strukturelle Rekonstruktion und vollständiger Proof wurden getrennt geprüft: End-to-End jeweils 7/10, 4/10 und 5/10; MCP-Referenz jeweils 10/10 in allen drei Runden. Die Referenz-Requests sowie die geprüften Ergebnismerkmale sind über alle Runden identisch. `finalize` hat die gespeicherten Artefakte erneut bewertet; die wiederholte Bewertung lieferte dieselben Scores. Laufzeit-, Inhalts-, Aufruf- und Rollenbelege sind Teil der geprüften Artefaktinventare.

## Aussage und Grenzen

Der Server verarbeitet die zwölf eingefrorenen Referenz-Payloads stabil. Die freie Rekonstruktion mit gpt-5.6-luna/xhigh streut deutlich. Wiederkehrende Ursachen sind ungültige Enum-Literale mit `#`, numerisch beginnende Constraint-Namen, falsche Funktionszuordnung, unvollständige Funktionsargumente und fehlende oder falsch strukturierte Werte im Tool-Payload. Diese Befunde betreffen die gemeinsame Nutzung durch Agent und Toolvertrag; sie senken ausschließlich die End-to-End-Messung.

Bei N12 führt ein außerhalb von `children` übergebenes `objects=ALL` zu einem fehlenden Argument von `GetInGroups` und einem Compilerfehler. In Runde 2 scheitert N12 bereits am nativen Schema, weil `spec.condition` fehlt. Solche Fehler erhalten keinen Boundary-Punkt. Ein automatisch ausgewiesener Fehlerabschnitt `MCP_AUTHORING` belegt allein keine Ursache im Server; die unveränderten Referenz-Payloads bestehen. Einzelbefunde und offene Ursachen stehen in den jeweiligen `FINDINGS.md` neben den verlinkten Rundenberichten.

Jeder der 36 Reconstructor-Kontexte erhielt ausschließlich seinen Fall; die archivierten tatsächlichen Werkzeugzugriffe wurden einzeln geprüft. Die Isolation ist durch Aufträge und Rollenverläufe belegt, keine Betriebssystem-Sandbox. Fehlgeschlagene Zugriffe auf falsch geschriebene Pfade lieferten keinen fremden Inhalt. In Runde 3 blockierte der neutrale Recorder einen nicht erlaubten Review-Aufruf lokal vor jeder MCP-Kommunikation. Der P08-Agent dieser Runde las die Hashdatei, berechnete die Hashes aber nicht ausdrücklich selbst; der unabhängige Prüfer belegte die unveränderten Eingaben. Diese Einschränkungen sind in den Audits dokumentiert.

Die v2 ist eine neue Basis für diese zwölf bekannten Regressionfälle. Weder allgemeine Fähigkeiten auf unbekannten Modellen noch direkte Verbesserungsprozente gegenüber v1 werden daraus abgeleitet. P04/P05 behalten die fachlich bestätigten ursprünglichen Constraints; ihre Anforderungen beschreiben geordnete Auswertung beziehungsweise zulässige Kardinalitäten.

## Abgebrochene Versuche

Unter derselben Revision 2.0.3 wurde zusätzlich [20260908T110718Z-ea40b71-1de94f](/Users/stefan/.codex/automations/interlis-mcp-constraint-benchmark/runs-v2/20260908T110718Z-ea40b71-1de94f/run.json) abgebrochen: Shell-Kommandoersetzung entfernte den letzten Zeilenumbruch des N11-Eingabemodells. Der unabhängige Inhaltsvergleich sperrte den Gesamtscore. Originalrequest, Resultat und Rollenverlauf sind erhalten; kein MCP-Fähigkeitswert wird daraus berechnet. Drei vollständige Läufe bei vier Versuchen belegen keine hundertprozentige Durchführungszuverlässigkeit.

Drei weitere vorbereitende Abbrüche früherer Revisionen und der anfängliche Transportfehler sind in [ROLLOUT.md](ROLLOUT.md) dokumentiert. [Vollständiges Versuchsregister](/Users/stefan/.codex/automations/interlis-mcp-constraint-benchmark/acceptance-v2-progress.json). Abgebrochene Läufe bleiben ohne gültigen Gesamtscore erhalten; es gab keine Reparaturen innerhalb gewerteter Läufe.

## Reproduktionsbasis und technische Prüfung

- MCP-Repository: `ea40b71`, zu Beginn und Abschluss aller drei Runden unverändert und sauber; Corpus: `b94abb0953c8413d4eaaa647fa66fa7d585aa4df`.
- Suite-Manifest SHA-256: `3ee3a81d73f1a201154a3eafb5520163388d5e82a962fd1ee2dfb9a358e6f37b`.
- Buildkennung: `e3e1e75436b21a26abacd98b483e7a75153e23867e6cdae6d46639d3fd014af8`.
- Laufendes JAR SHA-256: `979e06e1986ba91ec44fb95c0b319d52c9f8961473440a2362461fdf4d95cd37`.
- Abhängigkeitssnapshot SHA-256: `494e7d6a591eec1fcaec125be13c9a64678eec0c77f030a19822c1de44257d74`.
- ili2c 5.6.8, ilivalidator 1.14.3, iox-ili 1.24.4. Alle nativen Aufrufe über den Codex-Connector, seriell und vollständig protokolliert; keine manuellen MCP-Prozesse.
- Technische Prüfung: 498 Java-Tests, 18 STDIO-End-to-End-Tests und 32 Python-Prüfer-/Recorder-Tests grün; vorgeschriebene Vorprüfung und Input-QA in jeder akzeptierten Runde bestanden.

Die ursprünglich gemeldeten drei Testfehler ließen sich nach Ergänzung vollständiger Compilerdiagnosen nicht reproduzieren. Ihre Ursache bleibt ausdrücklich unbewiesen; es wurde kein vermeintlicher Authoring-Fix behauptet. Details im eingefrorenen [Diagnosebericht](v2/DIAGNOSTICS.md). Die dortigen Angaben zum damaligen Zwischenstand bleiben als historische Diagnose erhalten; maßgeblicher abschließender Abnahmestand ist dieses Dokument.
