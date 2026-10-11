# Abnahmestand: SO-GIS und SO-DATA

## Profilbereinigung vom 11. Oktober 2026

Aktuelle Parser und MCP-Schemas akzeptieren ausschliesslich `CORE`, `SO-GIS`
und `SO-DATA`. Der Eingabealias `SO` wurde entfernt; die Profilregeln bleiben
unverändert. Frühere Abschnitte beschreiben historische Quellstände.

Tatsächlich mit JDK 21 ausgeführt: `check e2eTest bootJar` und
`:interlis-mcp-module:publishMavenJavaPublicationToVerificationRepository`.
Alle 595 Unit-Tests und 24 echten STDIO-End-to-End-Tests bestanden, darunter
Profilserialisierung, tatsächliche Werkzeugschemas und Zurückweisung von `SO`.
Die bestehenden Benchmark- und Workflow-Prüfungen bestanden ebenfalls.
Nachweis des lokalen Laufs: `/tmp/interlis-cleanup-checks.log`.

Veröffentlicht und tatsächlich verifiziert: **0.0.574-1**, Quellrevision
`2f202dd9926f9a0eb0b73a7a50bb73179d2cba3a`.
[Pipeline 38114821829](https://github.com/edigonzales/interlis-mcp/actions/runs/38114821829)
bestand Application-, beide Architektur- und Publish-Jobs. Registry-Digest
`sha256:e9824d526528127705d800b798e8e6173dc2adb812451bf9096b349d7174b233`
enthält AMD64 und ARM64. Das frisch geladene ARM64-Image bestätigt Version und
Quellrevision. Dieser unveränderliche Release ist der Bezugspunkt; spätere reine
Dokumentations-Builds ersetzen seinen Nachweis nicht.

## SO-GIS und vervollständigte Referenzen vom 10. Oktober 2026

Das bisherige Profil `SO` heisst `SO-GIS`, intern `SO_GIS`. Die GIS-Regeln
bleiben gleich; Modellnamen mit `SO_` werden nicht umbenannt. Schemas erklären
`SO` als veralteten Eingabealias; Parser und MCP-Transport akzeptieren ihn,
Antworten verwenden `SO-GIS`. `SO-DATA` bleibt ein eigenes Profil.

Benutzerhandbuch, Tool-Referenz, Architektur, Entwicklerhandbuch, README,
MCP-Prompts und Regelressourcen sind gemeinsam nachgeführt. Die primäre
Operationsliste enthält `REMOVE_COMMENTS`; die Ressourcenübersicht nennt
`interlis://knowledge/so-data-rules`.

Tatsächlich lokal mit JDK 21 ausgeführt: `test check e2eTest bootJar` und
`:interlis-mcp-module:publishMavenJavaPublicationToVerificationRepository`.
595 JUnit-Tests und 24 MCP-End-to-End-Tests bestanden. Insbesondere prüft der
STDIO-Test beide Profilnamen durch die tatsächliche MCP-Schemavalidierung.
Benchmark- und Workflow-Prüfungen sind ebenfalls erfolgreich.

Der vorausgehende Stand `41ec4eb` wurde als `0.0.571-1` veröffentlicht:
[erfolgreiche Pipeline](https://github.com/edigonzales/interlis-mcp/actions/runs/38075711193).
Docker-Hub-Digest: `sha256:e14f2305c71003f0ae5a0167835c67958125da768c948eed1572cb4ad36accde`,
mit linux/arm64 und linux/amd64. Dieser Vorgänger enthält noch kein `SO-GIS`.
Die Umbenennung ist mit Quellrevision `44ea8449b819aa1cb009388fd7650a85285a03d3`
als **0.0.572-1** veröffentlicht:
[erfolgreiche Pipeline](https://github.com/edigonzales/interlis-mcp/actions/runs/38076945421).
Application-, ARM64-, AMD64- und Publish-Job sind erfolgreich, einschliesslich
Maven-Verbrauchertest und Image-Smokes. Der Registry-Digest
`sha256:eb348cd3cc6da8fedf9da2dbf564f1c8ee0d3827fb0c8e9447b3b44f013d338b`
enthält linux/arm64 und linux/amd64; das heruntergeladene ARM64-Image weist
dieselbe Quellrevision und Version aus. Der Integrator übernimmt diesen
unveränderlichen Digest für seine gemeinsame Abnahme und seine aktuellen Pins.
Der folgende Abschnitt dokumentiert den früheren Entwicklungsstand; seine
damaligen offenen Veröffentlichungspunkte sind durch diesen Nachweis erledigt.

## SO-DATA – lokale Entwicklungsabnahme vom 10. Oktober 2026

Implementiert: öffentliches Profil `SO-DATA`, CORE/SO-Regeln ausser MDE-060,
MDE-209 und MDE-210, kommentarfreie Quellen, Datumssuffix/VERSION/AT-Abgleich
und explizite Operation `REMOVE_COMMENTS`. Constraint-Namen werden für SO-DATA
nativ gerendert, damit technische Namen keine Kommentar-Metaattribute benötigen.
Bei der Kommentarentfernung werden vorhandene technische Constraint-Namen in
native Bezeichner überführt und im Änderungsreview gezeigt.

Tatsächlich mit JDK 21 ausgeführt:

```sh
./gradlew --no-daemon --no-watch-fs test check e2eTest bootJar :interlis-mcp-module:publishMavenJavaPublicationToVerificationRepository
```

595 JUnit-Tests und 24 MCP-End-to-End-Tests bestanden. Die bestehenden
Benchmark-/Workflow-Prüfungen liefen ebenfalls erfolgreich. Die Veröffentlichung
in diesem Befehl betrifft nur das lokale Verification-Repository.
Das JVM-Image aus `docker/Dockerfile` wurde lokal als `interlis-mcp-sodata:local`
gebaut und vom Integrator an einem eigenen HTTP-Port mit JDK 21 verwendet.

Synthetische Nachweise decken Profilserialisierung und tatsächliche MCP-Schemas,
beibehaltene SO-Regeln, Versionsdaten, Kommentarzeichen in Strings, Entfernung
von Dokumentation/Metaattributen, erhaltene Constraint-Namen und fünf Arten von
Constraint-Nachweisen ohne generierte Kommentare ab. CORE/SO-Regressionstests
bleiben Teil der Gesamtsuite. Geschäftsdaten wurden nicht geändert oder publiziert.

Zusätzliche offene Compilerbeobachtung aus der Integrator-Testentwicklung:
Eine absichtlich fehlende Domain `INTERLIS.MissingSyntheticType` wurde beim ersten
Aufruf zurückgewiesen, bei späteren Aufrufen desselben laufenden MCP jedoch als
gültig gemeldet. Die Ursache ist nicht abschliessend untersucht. Die dauerhafte
Reparaturfixture verwendet deshalb eine fehlende modelllokale Domain; damit sind
die wiederholten tatsächlichen Compilerfehler und die Reparaturgrenze nachgewiesen.
Die Tests belegen keine Behebung dieser gesonderten Compilerbeobachtung.

Offen: Registry-Veröffentlichung und nachfolgender Integrator-Pin auf einen
verifizierten Registry-Digest. Lokale Image-IDs dürfen diesen Nachweis nicht
ersetzen. Die gemeinsame Integrator-Abnahme und ihre Umgebungsgrenzen sind in
jenem Repository unter `docs/abnahme.md` festgehalten.
