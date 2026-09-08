# Abnahme und Aktivierung von v2

Stand 2026-09-08: Die fachlich freigegebene v2 (Revision 2.0.3, Bewertungsrevision 2.0.1) hat drei vollständige native Abnahmerunden bestanden. `acceptance.py` bestätigt ACCEPTED, identische Laufzeitidentität und stabile MCP-Referenzergebnisse. Sämtliche End-to-End-Werte und zusätzliche abgebrochene Versuche sind in [ACCEPTANCE-v2.md](ACCEPTANCE-v2.md) aufgeführt. P04/P05 behalten ihre ursprünglichen Constraints und Eingabemodelle.

## Aktivierung

`suite.json` zeigt auf die unveränderte freigegebene v2. Der bestehende Scheduled Task `interlis-mcp-constraint-benchmark` wurde über `automation_update` aktualisiert. Als Toolargument wurde der bytegleiche Prompt aus `v2/automation-prompt.md` übergeben. Die App entfernt beim Speichern genau den abschließenden Zeilenumbruch; der gesamte übrige Text ist identisch. Bytegleichheit der gespeicherten Datei wird deshalb ausdrücklich nicht behauptet. Die eingefrorene Suite und ihre Hashes bleiben unverändert.

Modell gpt-5.6-luna, Reasoning xhigh, Zeitplan, Projekt, Status und Benachrichtigungseinstellung wurden unverändert zurückgelesen. Beide Prompt-Hashes, die genaue Normalisierung und die Einstellungskontrolle sind außerhalb der abgeschlossenen Läufe im [Aktivierungsbeleg](/Users/stefan/.codex/automations/interlis-mcp-constraint-benchmark/activation-v2-20260908.json) archiviert. Diese Speicherung betrifft ausschließlich das Task-Prompt; für eingereichte Modelltexte gilt weiterhin die unveränderte strikte Inhaltsprüfung.

Reguläre Läufe führen jeweils eine Runde beider Messungen aus. Historische v1-Berichte bleiben unverändert; v2 ist eine neue Vergleichsbasis für diese zwölf bekannten Regressionfälle. Technische Handler-Tests sind keine nativen MCP-Scores.

## Connector und vorbereitende Korrekturen

Der native Canary lieferte zunächst zweimal `Transport closed`, auch mit identischem Retry. Nach Neuaufbau der Codex-Verbindung wurde die unveränderliche Laufzeit nativ bestätigt. Kein manueller MCP-Server oder Ersatzclient wurde gestartet. Diagnosebelege liegen unter `/Users/stefan/.codex/automations/interlis-mcp-constraint-benchmark/implementation-20260908/`.

Die lokale Konfiguration `/Users/stefan/.codex/config.toml` startet den Eval-Connector über den versionierten `tools/launch_eval.py`. Andere Connector-Einstellungen bleiben erhalten. Die frühere command/args-Sektion ist unter `config-backups` beim Scheduled Task archiviert. Der Launcher verwendet ein nach Inhalt adressiertes JAR und einen geprüften lokalen Abhängigkeitssnapshot.

Die drei vorbereitenden Abbrüche bleiben unverändert erhalten:

- `20260908T091847Z-fe4b0aa-d590f4`: Der erste Reconstructor speicherte den Canary-Request nicht vorab; ein TextEncoder-Fehler verlor das Rohresultat. Revision 2.0.1 ergänzte einen neutralen nativen Recorder.
- `20260908T093111Z-0ddddfb-b9a385`: Für P06 fehlte der unabhängige Nachweis einer Vertauschung totaler Bedingungen. Revision 2.0.2 ergänzte einen eng begrenzten Compiler-Nachweis; ungeklärte Äquivalenz bleibt `null`. Die separate nachträgliche Diagnose unter `implementation-20260908/total-boolean-proof` verändert den ursprünglichen Lauf nicht. Anforderungen, Gold-ASTs und Referenz-Payloads blieben unverändert.
- `20260908T100307Z-8fdd26a-e78cc5`: Der P05-Reconstructor verkürzte seinen Eingabepfad und konnte das Modell nicht lesen. Revision 2.0.3 legt die Eingaben im einfachen Unterordner `input` des isolierten Ausgabeordners ab. Dies war keine Korrektur eines MCP-Fehlers.

Mit Revision 2.0.3 wurden vier Versuche durchgeführt: drei vollständige Runden und ein Abbruch wegen eines vom Agenten entfernten abschließenden Zeilenumbruchs in N11. Dieser Abbruch hat keinen Gesamtscore und wurde weder repariert noch still fortgesetzt. Die drei akzeptierten Runden verwenden identische eingefrorene Suite- und Helper-Hashes. Die Durchführungszuverlässigkeit dieser kleinen Stichprobe beträgt damit drei vollständige Läufe bei vier Versuchen; eine fehlerfreie autonome Durchführung ist nicht nachgewiesen.
