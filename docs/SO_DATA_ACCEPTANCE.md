# SO-DATA – lokale Entwicklungsabnahme vom 10. Oktober 2026

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
