# interlis-mcp

`interlis-mcp` ist ein [Model Context Protocol (MCP)](https://modelcontextprotocol.io)-Server für INTERLIS 2. Er stellt Coding-Agenten und anderen MCP-Clients Fachwissen und Werkzeuge zum Erstellen, Analysieren, Ändern, Prüfen und Testen von INTERLIS-Modellen bereit.

Der Server läuft ausschliesslich über **STDIO**. Er ist bewusst **kein Datei- oder Workspace-Agent**: Der MCP-Client liest und schreibt `.ili`-Dateien. `interlis-mcp` erhält Modelltext als Eingabe und liefert strukturierte Ergebnisse oder einen aktualisierten Modelltext zurück.

## Was kann der Server?

- Vollständige INTERLIS-Modelle aus typisierten Spezifikationen erzeugen und atomare Modelländerungen source-preserving anwenden.
- Vollständige Modelle mit ili2c kompilieren, analysieren und gegen kuratierte Modellierungsregeln prüfen.
- Vorher-/Nachher-Stände semantisch vergleichen und potenziell inkompatible Änderungen sichtbar machen.
- Ergänzungen, Attributänderungen und Attributlöschungen als atomare, source-preserving Batches ausführen.
- Lokale `.ili`-Modelle als Beispiele durchsuchen und vollständig lesen.
- XTF-Beispieldaten erzeugen und im selben Aufruf mit ilivalidator prüfen; Erzeugung und Gültigkeit getrennt ausweisen.
- Prüfnachweise über `evidence` einordnen und unterstützte skalare Mandatory-Regeln gemeinsam auf Widersprüche untersuchen.
- INTERLIS-Constraints erklären, automatisch Testfälle erzeugen und mit dem echten ilivalidator verifizieren.
- Alle fünf Constraint-Arten über diskriminierte Specs typisiert und source-preserving erstellen; unvollständige Proofs werden als Kandidat zurückgehalten.
- Agenten über MCP-Resources und MCP-Prompts einen stabilen Arbeitsablauf und eine klare Tool-Hierarchie bereitstellen.

## Schnellstart

Voraussetzung ist Java 21.

```bash
./gradlew bootJar
java -jar build/libs/interlis-mcp.jar
```

Für einen lokalen Native-Image-Build wird zusätzlich GraalVM für JDK 25 benötigt.
Entweder als aktives `JAVA_HOME`:

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.3-graal"
./gradlew nativeCompile
./build/native/nativeCompile/interlis-mcp
```

oder über `GRAALVM_HOME`, wenn Java 21 das aktive `JAVA_HOME` bleiben soll:

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.10-tem"
export GRAALVM_HOME="$HOME/.sdkman/candidates/java/25.0.3-graal"
./gradlew nativeCompile
```

Das JVM-Artefakt bleibt in beiden Fällen Java-21-Bytecode.

Für die Entwicklung kann der Server direkt über Gradle gestartet werden:

```bash
./gradlew bootRun
```

Da MCP über STDIN und STDOUT kommuniziert, darf beim Containerbetrieb kein TTY erzwungen werden. Ein veröffentlichtes Image kann beispielsweise so gestartet werden:

```bash
docker run --rm -i sogis/interlis-mcp:latest
```

Es gibt zwei Container-Varianten, jeweils für `linux/amd64` und `linux/arm64`:

| Image | Inhalt |
| --- | --- |
| `sogis/interlis-mcp:latest` | GraalVM Native Image, empfohlen |
| `sogis/interlis-mcp-jvm:latest` | Spring-Boot-JAR auf Java 21 |

Das Native Image startet ohne JVM-Kaltstart. Lokal bauen:

```bash
./gradlew buildNativeImage   # GraalVM Native Image
./gradlew buildJvmImage      # JVM-Variante
```

Der Native-Image-Build benötigt GraalVM als `JAVA_HOME`, zum Beispiel
`~/.sdkman/candidates/java/25.0.3-graal`.

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
| Neuen MANDATORY Constraint erstellen | `authorIliMandatoryConstraint` |
| Neuen EXISTENCE Constraint erstellen | `authorIliExistenceConstraint` |
| Neuen PLAUSIBILITY Constraint erstellen | `authorIliPlausibilityConstraint` |
| Neuen SET-Constraint erstellen | `authorIliSetConstraint` |
| UNIQUE-Constraint erstellen | `authorIliUniqueConstraint` |
| Geometrie modellieren | `GeometryTypeSpec` in `authorIliModel` oder `applyIliModelChanges` |
| XTF erzeugen bzw. prüfen | `generateExampleXtf` / `validateXtf` |

## Agentische Nutzung

Die wichtigste Regel lautet: **Das höchste Tool verwenden, das die Aufgabe vollständig abdeckt.** Low-Level-Tools werden nicht routinemässig zusätzlich ausgeführt.

Beispiele:

- Ein vollständiges Modell wird mit `reviewIliModel` geprüft. Ein zusätzlicher Standarddurchlauf von `validateIliModel`, `analyzeIliModel` und `checkModelingRules` wäre redundant.
- Eine unterstützte Änderung wird als Batch mit `applyIliModelChanges` ausgeführt. Ein erfolgreiches `APPLIED`-Resultat enthält bereits semantischen Diff, Constraint-Proofs und `afterReview` für den unveränderten Nachher-Stand.
- Die `authorIli...Constraint`-Tools enthalten bereits Proof, Diff und `afterReview`. Nur bei einer separat vorgenommenen Quelltextänderung ist zusätzlich `reviewIliChange` nötig.
- Fachliche Semantik wird nicht erfunden. Fehlende Kardinalitäten, Rollen, Schlüssel oder Constraints werden als offene Fragen behandelt.

Die MCP-Resource `interlis://knowledge/agent-workflow` und der Prompt `interlis-modeling-agent` stellen diese Regeln direkt einem Agenten zur Verfügung.

## Modellierungsregeln

Es gibt zwei Regelprofile:

- `CORE`: portable technische und agentische Basisregeln.
- `SO`: `CORE` plus die kuratierten Regeln aus dem Solothurner Modellierungshandbuch.

Für Modelle nach den Vorgaben des Kantons Solothurn sollte `ruleProfile=SO` verwendet werden.

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
Prüfungen an den genauen Modelltext; `includeSuccessfulTestXtf=false` verkürzt erfolgreiche
Testausgaben ohne Verlust von Diagnosen. Details und Grenzen stehen
im [Benutzerhandbuch](docs/USER_GUIDE.md#constraints-aus-prosa-mit-fachlichen-erwartungen).
