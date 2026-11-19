# interlis-mcp

`interlis-mcp` ist ein [Model Context Protocol (MCP)](https://modelcontextprotocol.io)-Server für INTERLIS 2. Er stellt Coding-Agenten und anderen MCP-Clients Fachwissen und Werkzeuge zum Erstellen, Analysieren, Ändern, Prüfen und Testen von INTERLIS-Modellen bereit.

Der Server unterstützt **STDIO** und **Streamable HTTP**. Lokale JAR-Aufrufe verwenden standardmässig STDIO; Dockerimages starten HTTP auf `/mcp`. Er ist bewusst **kein Datei- oder Workspace-Agent**: Der MCP-Client liest und schreibt `.ili`-Dateien. `interlis-mcp` erhält Modelltext als Eingabe und liefert strukturierte Ergebnisse oder einen aktualisierten Modelltext zurück.

## Was kann der Server?

- Vollständige INTERLIS-Modelle aus typisierten Spezifikationen erzeugen und atomare Modelländerungen source-preserving anwenden.
- Vollständige Modelle mit ili2c kompilieren, analysieren und gegen kuratierte Modellierungsregeln prüfen.
- Vorher-/Nachher-Stände semantisch vergleichen und potenziell inkompatible Änderungen sichtbar machen.
- Ergänzungen, Attributänderungen und Attributlöschungen als atomare, source-preserving Batches ausführen.
- Lokale `.ili`-Modelle als Beispiele durchsuchen und vollständig lesen.
- XTF-Beispieldaten erzeugen und im selben Aufruf mit ilivalidator prüfen; Erzeugung und Gültigkeit getrennt ausweisen.
- Compilerdiagnosen und Validatorresultate einzeln einordnen und unterstützte skalare Mandatory-Regeln gemeinsam auf Widersprüche untersuchen.
- INTERLIS-Constraints erklären, automatisch Testfälle erzeugen und mit dem echten ilivalidator verifizieren.
- Pfade zu bekannten Modellelementen compilerbasiert finden, mit Kardinalitäten, Optionalität und Ausdrucksbausteinen für Constraints.
- Alle fünf Constraint-Arten über diskriminierte Specs typisiert und source-preserving erstellen; unvollständige Proofs werden als Kandidat zurückgehalten.
- Agenten über MCP-Resources und MCP-Prompts einen stabilen Arbeitsablauf und eine klare Tool-Hierarchie bereitstellen.

## Schnellstart

Voraussetzung ist Java 21.

```bash
./gradlew bootJar
java -jar build/libs/interlis-mcp.jar
```

Für die Entwicklung: `./gradlew bootRun`. HTTP lokal:

```bash
java -jar build/libs/interlis-mcp.jar --spring.profiles.active=http
```

Dockerimages stehen unter `sogis/interlis-mcp` und
`ghcr.io/edigonzales/interlis-mcp` für `linux/amd64` und `linux/arm64` bereit:

```bash
./gradlew buildImage                       # baut zuerst das ausführbare JAR
# Streamable HTTP: http://127.0.0.1:8080/mcp
docker run --rm -p 127.0.0.1:8080:8080 sogis/interlis-mcp:latest
# STDIO: ohne TTY, mit offenem STDIN
docker run --rm -i -e SPRING_PROFILES_ACTIVE=stdio sogis/interlis-mcp:latest
```

## Typische Aufgaben

| Aufgabe | Bevorzugter Einstieg |
| --- | --- |
| Vollständiges Modell prüfen | `reviewIliModel` |
| Bestehendes Modell semantisch vergleichen | `reviewIliChange` |
| Neues vollständiges Modell erstellen | `authorIliModel` |
| Bestehendes Modell atomar ändern | `applyIliModelChanges` |
| Passendes Modellbeispiel finden | `findSimilarModels` → `readModelExample` |
| Bestehenden Constraint verstehen | `reviewIliConstraint` |
| Bestehenden Constraint automatisch beweisen | `generateIliConstraintCases` |
| Neuen Constraint erstellen | `authorIliConstraint` mit `spec.kind` |
| Geometrie modellieren | `GeometryTypeSpec` in `authorIliModel` oder `applyIliModelChanges` |
| XTF erzeugen bzw. prüfen | `generateExampleXtf` / `validateXtf` |

## Agentische Nutzung

Die wichtigste Regel lautet: **Das höchste Tool verwenden, das die Aufgabe vollständig abdeckt.** Low-Level-Tools werden nicht routinemässig zusätzlich ausgeführt.

Beispiele:

- Ein vollständiges Modell wird mit `reviewIliModel` geprüft. Für reine Compilerdiagnostik steht `validateIliModel` zur Verfügung.
- Eine unterstützte Änderung wird als Batch mit `applyIliModelChanges` ausgeführt. Ein erfolgreiches `APPLIED`-Resultat enthält bereits semantischen Diff, Constraint-Proofs und `afterReview` für den unveränderten Nachher-Stand.
- `authorIliConstraint` liefert bereits Proof, Diff und `afterReview`. Nur bei einer separat vorgenommenen Quelltextänderung ist zusätzlich `reviewIliChange` nötig.
- Fachliche Semantik wird nicht erfunden. Fehlende Kardinalitäten, Rollen, Schlüssel oder Constraints werden als offene Fragen behandelt.

Die MCP-Resource `interlis://knowledge/agent-workflow` und der Prompt `interlis-modeling-agent` stellen diese Regeln direkt einem Agenten zur Verfügung.

## Modellierungsregeln

Es gibt drei Regelprofile:

- `CORE`: portable technische und agentische Basisregeln.
- `SO-GIS`: `CORE` plus die kuratierten Regeln aus dem Solothurner Modellierungshandbuch.
- `SO-DATA`: kommentarfreie CSV-Validierungsmodelle mit passendem Namenssuffix und Versionsdatum; die drei Dokumentationsregeln entfallen.

Für Modelle nach den Vorgaben des Kantons Solothurn sollte `ruleProfile=SO-GIS` verwendet werden.
Zulässige Profilwerte sind ausschliesslich `CORE`, `SO-GIS` und `SO-DATA`.

## Dokumentation

Die Dokumentation ist nach Aufgaben gegliedert:

- [Dokumentationsübersicht](docs/README.md)
- [Benutzerhandbuch](docs/USER_GUIDE.md)
- [Tool-Referenz](docs/TOOL_REFERENCE.md)
- [Agentische Arbeitsabläufe](docs/AGENT_WORKFLOWS.md)
- [Constraints: Semantik, Authoring und Proofs](docs/CONSTRAINTS.md)
- [Architektur](docs/ARCHITECTURE.md)
- [Entwicklerhandbuch](docs/DEVELOPER_GUIDE.md)

## Tests

```bash
./gradlew test
./gradlew e2eTest
```

Die Tests decken nicht nur einzelne Java-Komponenten ab. Contract-, Golden-Scenario- und STDIO-E2E-Tests schützen auch die öffentlichen MCP-Schemas und die vorgesehenen agentischen Arbeitsabläufe.

## Lizenz

[MIT](LICENSE)

### Constraints aus Prosa

Der Agent bereitet mit `analyzeIliModel(..., contextFqn)` den Modellkontext vor und hält fachliche
Erwartungen vor dem Authoring fest. Diese werden zusätzlich zum automatischen Proof mit
`testIliConstraint` geprüft, bevor der Agent das Modell schreibt. `expectationSource` kennzeichnet
die angegebene Herkunft, `explanation` beschreibt die kompilierte Regel. `modelHashes` bindet
Prüfungen an den genauen Modelltext. Für erfolgreiche, ausgeübte und warnungsfreie Tests
liefert `includeSuccessfulTestXtf=true` auch den XTF-Text. Details und Grenzen stehen
im [Benutzerhandbuch](docs/USER_GUIDE.md#constraints-aus-prosa-mit-fachlichen-erwartungen).

## SO-DATA: CSV-Validierungsmodelle

`SO-DATA` ist das Profil für kommentarfreie CSV-Validierungsmodelle mit passendem
Modellnamenssuffix und VERSION-Datum. CORE und SO-GIS bleiben unverändert verfügbar.
[Regeln und Umstellung vorhandener Modelle](docs/USER_GUIDE.md#so-data-csv-validierungsmodelle)
stehen im Benutzerhandbuch.

### Numeric bounds and decimal places

`NUM_RANGE.min` and `max` accept JSON numbers or decimal strings. Use strings when
decimal places are significant: `"0.00"` / `"100.00"` renders `0.00 .. 100.00`,
including without a unit. Both bounds must have the same number of decimal
places. No rounding or removal of trailing zeros is performed. The same contract
applies to typed attribute changes.
