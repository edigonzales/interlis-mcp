# INTERLIS Constraint Reconstruction Eval

Dieses Verzeichnis enthält die versionierten, menschenlesbaren Eingaben und Oracle-Daten für den Codex-Task **INTERLIS MCP Constraint Benchmark**.

## Aufbau

- `suite.json`: aktive Suite, aktuell v2 (Revision 2.0.3)
- `v2/public/<Fall>/requirement.de.md`: deutsche Fachanforderung ohne Constraint-Syntax oder Goldnamen
- `v2/public/<Fall>/model.ili`: vollständiges, kompilierbares Modell ohne den Ziel-Constraint
- `v2/oracle/<Fall>/`: entfernter Gold-Constraint und vollständige Compiler-Goldartefakte
- `v2/manifest.json`: Fallreihenfolge, Corpus-Commit, Referenz-Payloads und SHA-256-Hashes
- `v1/`: unveränderte historische Suite

## Freigabe

`v1` wurde nach der fachlichen Prüfung aller zwölf Dateien `requirement.de.md` am 6. September 2026 freigegeben. Die zugehörigen Hashes sind im Manifest festgehalten und der Manifeststatus lautet `APPROVED`.

Eine freigegebene Version wird nicht still geändert. Inhaltliche Änderungen erzeugen eine neue Version, beispielsweise `v2`, und aktualisieren anschließend `suite.json`.

## Ausführung

Der Codex-Task liest nur eine freigegebene und unveränderte Suite. Er kopiert den öffentlichen Teil in ein temporäres Verzeichnis, führt Rekonstruktion und Bewertung isoliert aus und archiviert die Resultate außerhalb des Repositorys. Ein Benchmark-Lauf verändert dieses Verzeichnis nicht.

## v2: freigegeben und nativ abgenommen

[v2/REVIEW.md](v2/REVIEW.md) dokumentiert die fachliche Freigabe vom 8. September 2026. Die ursprünglichen Constraints von P04/P05 bleiben unverändert. Drei vollständige native Runden wurden maschinell akzeptiert; `suite.json` aktiviert v2. [ACCEPTANCE-v2.md](ACCEPTANCE-v2.md) enthält alle Ergebnisse und Abbrüche, [ROLLOUT.md](ROLLOUT.md) dokumentiert die Umstellung. Historische Ergebnisse bleiben unverändert und sind nicht direkt mit v2 vergleichbar.

v2 trennt freie Rekonstruktion durch einen frischen Agenten pro Fall von festen MCP-Referenz-Payloads. Gold-ASTs und Modelländerungen werden durch `src/benchmark/.../CompilerEvidence.java` direkt anhand des festgelegten ili2c-Modells geprüft, unabhängig von Authoring und MCP-Review. Erhaltene alternative Formen werden nur bei belegter Äquivalenz gewertet. Unvollständige Belege sperren den Gesamtscore.

Technische Prüfung: `JAVA_HOME=<Java 21> ./gradlew test check e2eTest`. `check` beinhaltet die Gegenbeispiele des Python-Prüfers. Die Handler-Fixtures in `build/benchmark/reference-diagnostic` sind **keine** nativen Benchmarkläufe. Das Compiler-Hilfsprogramm benötigt `./gradlew benchmarkClasspath` und wird ausschließlich für Offline-Prüfungen verwendet.

Werkzeuge unter `tools/`:

- `prepare_v2.py`: explizite Fixture-Wartung; sammelt die Abhängigkeiten und erstellt Compiler-Goldartefakte. Niemals während eines gewerteten Laufs ausführen.
- `freeze_manifest.py`: aktualisiert ausschließlich DRAFT-Hashes, erteilt keine Freigabe.
- `benchmark.py`: Suite-/Input-Prüfung, Laufzustand, einzelne Eingaben, Aufrufprüfung, unabhängige Bewertung und Abschluss. `--draft` erlaubt technische Fixture-QA, niemals offizielle Scores.
- `launch_eval.py`: ausschließlich für den Codex-Connector; baut und startet ein nach Inhalt adressiertes JAR mit geprüftem lokalem Abhängigkeitssnapshot. Spricht selbst kein MCP.
- `acceptance.py`: prüft drei vollständige native Läufe, stabile MCP-Referenzergebnisse und hält die gesamte End-to-End-Streuung fest.

Das Automation-Prompt liegt eingefroren in `v2/automation-prompt.md`. v2 und die Prüfhelfer sind gemeinsam versioniert. Die lokale Connector-Konfiguration verwendet `python3 /Users/stefan/sources/interlis-mcp/evals/constraint-reconstruction/tools/launch_eval.py`; JAVA_HOME und die elf erlaubten Werkzeuge bleiben bestehen. Die native Laufzeitidentität wurde in allen drei Runden bestätigt. Eine bereits laufende Verbindung darf nicht als neues Build ausgegeben werden. Der bestehende Scheduled Task verwendet eine Runde beider Messungen mit gpt-5.6-luna, Reasoning xhigh und unverändertem Zeitplan.
