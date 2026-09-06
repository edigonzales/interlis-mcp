# INTERLIS Constraint Reconstruction Eval

Dieses Verzeichnis enthält die versionierten, menschenlesbaren Eingaben und Oracle-Daten für den Codex-Task **INTERLIS MCP Constraint Benchmark**.

## Aufbau

- `v1/public/<Fall>/requirement.de.md`: deutsche Fachanforderung ohne Constraint-Syntax oder Goldnamen
- `v1/public/<Fall>/model.ili`: vollständiges, kompilierbares Modell ohne den Ziel-Constraint
- `v1/oracle/<Fall>/original-constraint.ili`: entfernter Gold-Constraint
- `v1/oracle/<Fall>/expected.json`: Kontext, Constraint-Art, Werkzeugerwartung und Gold-AST
- `v1/manifest.json`: Fallreihenfolge, Corpus-Commit und SHA-256-Hashes

## Freigabe

`v1` wurde nach der fachlichen Prüfung aller zwölf Dateien `requirement.de.md` am 6. September 2026 freigegeben. Die zugehörigen Hashes sind im Manifest festgehalten und der Manifeststatus lautet `APPROVED`.

Eine freigegebene Version wird nicht still geändert. Inhaltliche Änderungen erzeugen eine neue Version, beispielsweise `v2`, und aktualisieren anschließend `suite.json`.

## Ausführung

Der Codex-Task liest nur eine freigegebene und unveränderte Suite. Er kopiert den öffentlichen Teil in ein temporäres Verzeichnis, führt Rekonstruktion und Bewertung isoliert aus und archiviert die Resultate außerhalb des Repositorys. Ein Benchmark-Lauf verändert dieses Verzeichnis nicht.
