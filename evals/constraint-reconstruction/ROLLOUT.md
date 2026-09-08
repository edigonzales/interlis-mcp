# Abnahme und Aktivierung von v2

Stand 2026-09-08: Fachliche Freigabe liegt vor. P04/P05 behalten ihre ursprünglichen Constraints und Eingabemodelle. Die versionierten Anforderungen beschreiben die tatsächliche geordnete Auswertung beziehungsweise die zulässigen Kardinalitätsfälle. Suite, Referenz-Payloads, Gold-ASTs, native Werkzeugdeklarationen und Prüfhelfer werden gemeinsam eingefroren. Technische Handler-Tests sind keine nativen MCP-Scores.

## Verbindungsdiagnose und erste Abnahme

Der native `listConstraintFunctions`-Canary lieferte zweimal `Transport closed`, auch mit identischem Retry. Nach dem Neustart wurde die Verbindung wiederhergestellt und die Laufzeitidentität nativ bestätigt. Kein manueller MCP-Server oder Ersatzclient wurde gestartet. Die vollständigen Diagnosebelege liegen unter `/Users/stefan/.codex/automations/interlis-mcp-constraint-benchmark/implementation-20260908/`.

Die lokale Konfiguration in `/Users/stefan/.codex/config.toml` startet den Eval-Connector jetzt über den versionierten `tools/launch_eval.py`. Andere Connector-Einstellungen bleiben erhalten. Die alte command/args-Sektion ist unter `config-backups` beim bestehenden Scheduled Task archiviert. Der Launcher hat im Vorbereitungsmodus ein unveränderliches JAR und einen gehashten Importbestand erstellt, aber keinen MCP-Prozess gestartet. Der erforderliche Neuaufbau der Verbindung ist inzwischen erfolgt. Die Codex-Oberfläche ist für das verfügbare Computer-Use-Werkzeug gesperrt.

## Fortsetzung nach Neuaufbau der Verbindung

1. Den nativen Canary mit vorher archiviertem Request aufrufen. Nur `runtimeIdentity.verified=true`, `mode=IMMUTABLE_BENCHMARK`, passendes Build und passender Import-Hash erlauben die Abnahme. Kein Nachbau eines MCP-Clients und keine manuellen Java-Serverstarts.
2. Mit dem unveränderten Prompt aus `v2/automation-prompt.md` drei vollständige Abnahmerunden ausführen. In dieser Phase ausdrücklich `--suite evals/constraint-reconstruction/v2` verwenden; `suite.json` zeigt weiterhin auf v1. Pro Runde genau zwölf neue Reconstructor-Kontexte mit gpt-5.6-luna/xhigh/fork_turns=none sowie zwölf feste native Referenzaufrufe. MCP-Aufrufe seriell, vollständige originale Rollenverläufe archivieren. Keine Reparaturen in einer gewerteten Runde. Bei Infrastruktur-/Belegfehlern `benchmark.py abort` verwenden.
3. `tools/acceptance.py` mit den drei COMPLETE-Laufverzeichnissen und einem persistenten `--output`-Pfad ausführen. Alle drei Ergebnisse erhalten. Gleiche Laufzeitidentität und stabile Referenzergebnisse sind Pflicht; End-to-End-Streuung vollständig berichten.
4. Erst nach ACCEPTED `suite.json` auf `activeVersion=v2`, `manifest=v2/manifest.json` umstellen und diese Aktivierung versionieren. Über `automation_update` die bestehende Automation `interlis-mcp-constraint-benchmark` aktualisieren: Prompt bytegleich zur freigegebenen v2, Modell gpt-5.6-luna, Reasoning xhigh, vorhandener Zeitplan, Projekt, Status und Benachrichtigungseinstellung unverändert. Die aktuelle Automation vor dem Update erneut lesen; keine doppelte Automation anlegen.
5. Die gespeicherte Automation erneut lesen und Einstellungen sowie Prompt-Hash überprüfen. Akzeptanzbericht verlinken. Reguläre Läufe führen jeweils eine Runde beider Messungen aus. Historische v1-Berichte bleiben unverändert und sind nicht direkt mit v2 vergleichbar.

Keine der drei nativen Abnahmerunden wurde bislang als vollständig oder gewertet ausgegeben. Der Scheduled Task ist noch nicht auf v2 umgestellt.

Der erste Lauf `20260908T091847Z-fe4b0aa-d590f4` wurde wegen fehlender vorab gespeicherter Canary-Requests des ersten Reconstructors ohne Gesamtwert abgebrochen. Revision 2.0.1 ergänzt einen neutralen nativen Recorder; ursprüngliche Revision und Rollenverlauf bleiben erhalten. Die drei vollständigen Abnahmerunden müssen dieselbe neue Revision verwenden.

Der Lauf `20260908T093111Z-0ddddfb-b9a385` wurde nach P06 ohne Gesamtscore archiviert: Der Bewertungshelfer konnte die reine Vertauschung zweier totaler Bedingungen im SUM-Fallback nicht nachweisen. Revision 2.0.2 ergänzt dafür einen Compiler-gestützten lokalen Äquivalenznachweis und weist ungeklärte Äquivalenz als `null` statt `false` aus. Historische Scores und Kandidaten bleiben unverändert; die Diagnose mit dem neuen Helfer liegt separat unter `implementation-20260908/total-boolean-proof`. Fachanforderungen, Modelle, Gold-ASTs, Referenzrequests und Reconstructor-Prompt werden durch diese technische Korrektur nicht geändert. Die Abnahme startet erneut mit der gemeinsam versionierten Revision.
