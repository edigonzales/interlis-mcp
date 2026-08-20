# interlis-mcp

`interlis-mcp` ist ein [Model Context Protocol (MCP)](https://modelcontextprotocol.io)-Server für INTERLIS 2. Er stellt Coding-Agenten und anderen MCP-Clients Fachwissen und Werkzeuge zum Erstellen, Analysieren, Ändern, Prüfen und Testen von INTERLIS-Modellen bereit.

Der Server läuft ausschliesslich über **STDIO**. Er ist bewusst **kein Datei- oder Workspace-Agent**: Der MCP-Client liest und schreibt `.ili`-Dateien. `interlis-mcp` erhält Modelltext als Eingabe und liefert strukturierte Ergebnisse oder einen aktualisierten Modelltext zurück.

## Was kann der Server?

- INTERLIS-Bausteine für Modelle, Topics, Klassen, Strukturen, Beziehungen, Domains, Units, Attribute und Geometrien erzeugen.
- Vollständige Modelle mit ili2c kompilieren, analysieren und gegen kuratierte Modellierungsregeln prüfen.
- Vorher-/Nachher-Stände semantisch vergleichen und potenziell inkompatible Änderungen sichtbar machen.
- Unterstützte Modelländerungen source-preserving ausführen; aktuell `ADD_ATTRIBUTE` für `CLASS` und `STRUCTURE`.
- Lokale `.ili`-Modelle als Beispiele durchsuchen und vollständig lesen.
- XTF-Beispieldaten erzeugen und XTF mit ilivalidator prüfen.
- INTERLIS-Constraints erklären, automatisch Testfälle erzeugen und mit dem echten ilivalidator verifizieren.
- Neue MANDATORY-, skalare EXISTENCE-, PLAUSIBILITY- und unterstützte SET-Constraints typisiert und source-preserving erstellen.
- Agenten über MCP-Resources und MCP-Prompts einen stabilen Arbeitsablauf und eine klare Tool-Hierarchie bereitstellen.

## Schnellstart

Voraussetzung ist Java 21.

```bash
./gradlew bootJar
java -jar build/libs/interlis-mcp.jar
```

Für die Entwicklung kann der Server direkt über Gradle gestartet werden:

```bash
./gradlew bootRun
```

Da MCP über STDIN und STDOUT kommuniziert, darf beim Containerbetrieb kein TTY erzwungen werden. Ein veröffentlichtes Image kann beispielsweise so gestartet werden:

```bash
docker run --rm -i sogis/interlis-mcp:latest
```

## Typische Aufgaben

| Aufgabe | Bevorzugter Einstieg |
| --- | --- |
| Vollständiges Modell prüfen | `reviewIliModel` |
| Bestehendes Modell semantisch vergleichen | `reviewIliChange` |
| Attribut source-preserving hinzufügen | `applyIliModelChange` mit `ADD_ATTRIBUTE` |
| Passendes Modellbeispiel finden | `findSimilarModels` → `readModelExample` |
| Bestehenden Constraint verstehen | `reviewIliConstraint` |
| Bestehenden Constraint automatisch beweisen | `generateIliConstraintCases` |
| Neuen MANDATORY Constraint erstellen | `authorIliMandatoryConstraint` |
| Neuen EXISTENCE Constraint erstellen | `authorIliExistenceConstraint` |
| Neuen PLAUSIBILITY Constraint erstellen | `authorIliPlausibilityConstraint` |
| Neuen `objectCount(ALL)`-SET-Constraint erstellen | `authorIliSetConstraint` |
| Einfachen UNIQUE-Schlüssel erzeugen | `createUniqueConstraint`, danach `generateIliConstraintCases` |
| Geometrieattribut mit Abhängigkeiten vorbereiten | `ensureGeometryDependencies` |
| XTF erzeugen bzw. prüfen | `generateExampleXtf` / `validateXtf` |

## Agentische Nutzung

Die wichtigste Regel lautet: **Das höchste Tool verwenden, das die Aufgabe vollständig abdeckt.** Low-Level-Tools werden nicht routinemässig zusätzlich ausgeführt.

Beispiele:

- Ein vollständiges Modell wird mit `reviewIliModel` geprüft. Ein zusätzlicher Standarddurchlauf von `validateIliModel`, `analyzeIliModel` und `checkModelingRules` wäre redundant.
- Eine von `applyIliModelChange` unterstützte Änderung wird direkt mit diesem Tool ausgeführt. Ein erfolgreiches `APPLIED`-Resultat enthält bereits semantischen Diff und `afterReview` für den unveränderten Nachher-Stand.
- Ein Constraint-Proof (`proofVerified=true` oder `generationVerified=true`) beweist den Constraint, ersetzt aber bei einer separat vorgenommenen Modelländerung nicht das Modell-Level-Review mit `reviewIliChange`.
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
