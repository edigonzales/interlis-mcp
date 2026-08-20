# Benutzerhandbuch

Dieses Handbuch zeigt, wie `interlis-mcp` gestartet, mit einem MCP-Client verbunden und für typische INTERLIS-Aufgaben verwendet wird. Die Beispiele verwenden kleine Modelle und konzentrieren sich auf den Ablauf. Die vollständige Liste der Werkzeuge steht in der [Tool-Referenz](TOOL_REFERENCE.md).

## Grundidee

`interlis-mcp` ist ein **STDIO-MCP-Server**. Er arbeitet mit Text und strukturierten Payloads, nicht direkt mit Dateien im Workspace.

Ein typischer Ablauf sieht so aus:

```text
Workspace / Repository
      |
      | Agent liest .ili-Datei
      v
MCP-Client / Coding-Agent
      |
      | modelText + strukturierte Aufgabe
      v
interlis-mcp
      |
      | Analyse / Vorschlag / updatedModelText / Proof
      v
MCP-Client / Coding-Agent
      |
      | schreibt bestätigten neuen Stand
      v
Workspace / Repository
```

Das ist eine wichtige Trennung: `interlis-mcp` liefert INTERLIS-Fachlogik, Compilerwissen und Validator-Proofs. Das Lesen und Schreiben von Dateien bleibt Aufgabe des Clients oder Coding-Agenten.

## Voraussetzungen

- Java 21
- für einen lokalen Build: Gradle Wrapper aus dem Repository
- optional Docker
- ein MCP-Client, beispielsweise VS Code oder Claude Desktop

## Server starten

### Aus dem Repository

```bash
./gradlew bootJar
java -jar build/libs/interlis-mcp.jar
```

Für die Entwicklung:

```bash
./gradlew bootRun
```

Wenn das Standard-`java` nicht Java 21 ist, verwende den vollständigen Pfad zum Java-21-Binary.

### Container

Ein veröffentlichtes Image kann ohne TTY gestartet werden:

```bash
docker run --rm -i sogis/interlis-mcp:latest
```

STDIN muss offen bleiben, weil darüber die MCP-Kommunikation läuft.

## MCP-Client konfigurieren

### Claude Desktop

Beispiel für `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "interlis-mcp": {
      "command": "/opt/java-21/bin/java",
      "args": [
        "-jar",
        "/path/to/interlis-mcp/build/libs/interlis-mcp.jar"
      ]
    }
  }
}
```

### VS Code

Beispiel für eine `mcp.json`-Konfiguration:

```json
{
  "servers": {
    "interlis-mcp": {
      "command": "/opt/java-21/bin/java",
      "args": [
        "-jar",
        "/path/to/interlis-mcp/build/libs/interlis-mcp.jar"
      ]
    }
  }
}
```

Die konkreten Dateipfade hängen vom Client und vom lokalen Installationsort ab.

## Lokale Modellbeispiele konfigurieren

`interlis-mcp` kann lokale `.ili`-Dateien durchsuchen. Die Pfade werden über `interlis.knowledge.model-paths` konfiguriert. Mehrere Dateien oder Verzeichnisse werden durch Kommas getrennt; Verzeichnisse werden rekursiv durchsucht.

Beispiel beim Start:

```bash
java -jar build/libs/interlis-mcp.jar \
  --interlis.knowledge.model-paths=/data/models,/data/schema-jobs
```

Alternativ kann Spring Boots Environment-Binding verwendet werden:

```bash
export INTERLIS_KNOWLEDGE_MODEL_PATHS=/data/models,/data/schema-jobs
java -jar build/libs/interlis-mcp.jar
```

Weitere Grenzwerte:

- `interlis.knowledge.max-model-bytes=1048576`
- `interlis.knowledge.max-search-results=10`

Externe INTERLIS-Modell-Repositories werden einmal beim Serverstart konfiguriert:

```bash
export INTERLIS_MCP_MODEL_REPOSITORIES='https://models.interlis.ch;https://geo.so.ch/models'
java -jar build/libs/interlis-mcp.jar
```

Sie sind kein Parameter jedes Tool-Aufrufs. Ohne Konfiguration verwendet ili2c seine Standard-Repositories.

Für eingereichte Payloads gelten feste Schutzgrenzen: 2 MiB Modelltext, 20 MiB XTF-Text, maximal 100 explizite Constraint-Testfälle und maximal 20 erzeugte XTF-Objekte je Klasse.

Die Suche ist lokal und lexikalisch. Sie verwendet keine Embeddings, keine Datenbank und keinen externen Suchdienst.

## Regelprofile

Bei Reviews und Regelchecks kann ein Regelprofil gewählt werden:

- `CORE`: portable technische und agentische Basisregeln.
- `SO`: enthält `CORE` und zusätzlich die kuratierten Regeln aus dem Solothurner Modellierungshandbuch.

Für ein Modell, das nach den Vorgaben des Kantons Solothurn geprüft werden soll, ist `SO` die passende Wahl.

## Aufgabe: ein vollständiges Modell prüfen

Für einen einzelnen vollständigen Modellstand ist `reviewIliModel` der Standard. Das Tool kombiniert Compilerstatus, Strukturanalyse, automatisierte Regeln, manuelle Checks und offene Fragen.

Beispiel:

```json
{
  "modelText": "INTERLIS 2.4;\n\nMODEL Demo (de) AT \"https://example.org/demo\" VERSION \"2026-08-20\" =\nEND Demo.\n",
  "modelPurpose": "PUBLICATION",
  "ruleProfile": "SO"
}
```

Wichtige Ergebnisfelder sind unter anderem:

- `compilerValid`
- `compilerDiagnostics`
- `validForAutomatedRules`
- `structure`
- `ruleFindings`
- `manualChecks`
- `openQuestions`

Wenn `reviewIliModel` die benötigte Antwort bereits liefert, sollten `validateIliModel`, `analyzeIliModel` und `checkModelingRules` nicht noch einmal routinemässig separat aufgerufen werden.

## Aufgabe: ein Attribut zu einer bestehenden Klasse hinzufügen

Für eine unterstützte semantische Änderung ist `applyIliModelChange` dem manuellen Umschreiben vorzuziehen. Aktuell unterstützt das Tool `ADD_ATTRIBUTE` für lokale `CLASS`- und `STRUCTURE`-Elemente.

Ausgangsmodell:

```ili
INTERLIS 2.4;

MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-20" =
  TOPIC Data =
    CLASS Building =
      name : TEXT*80;
    END Building;
  END Data;
END Demo.
```

Passender Payload:

```json
{
  "modelText": "<vollständiger Modelltext>",
  "request": {
    "operation": "ADD_ATTRIBUTE",
    "addAttribute": {
      "containerFqn": "Demo.Data.Building",
      "attribute": {
        "name": "egid",
        "mandatory": true,
        "typeSpec": {
          "baseType": {
            "kind": "TEXT",
            "length": 14
          }
        }
      }
    }
  },
  "modelPurpose": "CAPTURE",
  "ruleProfile": "CORE"
}
```

Bei Erfolg liefert das Tool `status=APPLIED` und `updatedModelText`. Es kompiliert Vorher und Nachher, prüft den semantischen Diff auf unerwartete Kollateraleffekte und enthält bereits das `afterReview` des neuen Stands.

Für denselben unveränderten Nachher-Stand ist deshalb kein zusätzliches `reviewIliChange` oder `reviewIliModel` nötig.

## Aufgabe: eine nicht unterstützte Modelländerung durchführen

Nicht jede Änderung hat ein eigenes High-Level-Change-Tool. In diesem Fall bearbeitet der Coding-Agent den Modelltext gezielt und verwendet anschliessend `reviewIliChange`.

Beispiel: Eine Klasse wird absichtlich von `OldBuilding` in `Building` umbenannt und der Client besitzt bereits den Vorher- und Nachher-Text.

```json
{
  "beforeModelText": "<vorher>",
  "afterModelText": "<nachher>",
  "modelPurpose": "CAPTURE",
  "ruleProfile": "SO"
}
```

`reviewIliChange` liefert unter anderem:

- `added`, `removed`, `changed`
- `potentiallyBreakingChanges`
- `impact`
- `afterCompilerValid`
- `afterDiagnostics`
- `afterReview`

Das enthaltene `afterReview` ist der Abschlussreview für genau diesen Nachher-Stand. Wird der Modelltext danach erneut geändert, muss der neue Stand wieder geprüft werden.

## Aufgabe: ein Element umbenennen

`renameModelElement` ist ein spezialisiertes Tool für robuste Renames über das ili2c-Metamodell.

```json
{
  "modelText": "<vollständiger Modelltext>",
  "elementFqn": "Demo.Data.OldBuilding",
  "newName": "Building",
  "expectedKind": "CLASS_OR_STRUCTURE"
}
```

Das Tool liefert einen vollständig neu generierten `updatedModelText`. Anders als `applyIliModelChange` ist dieser Vorgang **nicht source-preserving** bezüglich Whitespace und Deklarationslayout. Verwende ihn deshalb, wenn semantische Robustheit wichtiger ist als die Beibehaltung der ursprünglichen Formatierung.

## Aufgabe: ein ähnliches Modell als Vorbild finden

Verwende zuerst `findSimilarModels` und lies danach einen ausgewählten Treffer vollständig mit `readModelExample`.

Beispiel für die Suche:

```json
{
  "query": "Gebäude Adresse Publikationsmodell"
}
```

Ein Suchtreffer ist nur Discovery-Metadaten. Aus Snippet, Score oder Trefferbegriffen allein sollte kein Modellierungsmuster abgeleitet werden.

Anschliessend:

```json
{
  "path": "/data/models/BuildingPublication.ili"
}
```

`readModelExample` akzeptiert nur Pfade innerhalb des konfigurierten Modellkorpus.

## Aufgabe: ein Geometrieattribut vorbereiten

Für Geometrieattribute ist `ensureGeometryDependencies` der bevorzugte Einstieg. Ohne CHBase muss die bereits fachlich gewählte Koordinatendomain explizit angegeben werden; der Server erfindet kein CRS und keine Achsgrenzen.

Beispiel:

```json
{
  "attributeName": "Perimeter",
  "geometryType": "SURFACE",
  "coordDomainFqn": "Demo.Coord2",
  "arcs": true
}
```

Das Resultat enthält:

- `importLinesToAdd`
- `domainsToAdd`
- `attributeLine`
- `notes`

`domainsToAdd` bleibt in diesem Fall leer. Eine neue Domain kann separat mit expliziten Achsgrenzen über den Koordinatendomain-Helper erzeugt werden. Der Agent integriert die Bausteine anschliessend in den Modelltext und prüft den vollständigen Stand.

## Aufgabe: einen neuen Constraint erstellen

Für neue Constraints sollte das höchste semantische Authoring-Tool verwendet werden:

| Constraint-Art | Tool |
| --- | --- |
| MANDATORY | `authorIliMandatoryConstraint` |
| EXISTENCE | `authorIliExistenceConstraint` |
| PLAUSIBILITY | `authorIliPlausibilityConstraint` |
| SET mit `INTERLIS.objectCount(ALL)` | `authorIliSetConstraint` |
| UNIQUE | noch kein gleichwertiges typisiertes Authoring; einfacher Snippet-Helper `createUniqueConstraint` |

Ein typisiertes Authoring-Tool liefert bei Erfolg `proofVerified=true` und `updatedModelText`. Der Constraint-Proof ist damit abgeschlossen. Wurde ein bestehendes Modell durch Constraint-Authoring geändert, wird das gesamte Modell danach **einmal** mit `reviewIliChange(before, updatedModelText)` geprüft.

Ein ausführliches MANDATORY-Beispiel sowie Beispiele für EXISTENCE, PLAUSIBILITY, UNIQUE und SET stehen in [Constraints](CONSTRAINTS.md).

## Aufgabe: einen bestehenden Constraint automatisch testen

`generateIliConstraintCases` erzeugt modellbewusste Witnesses, Counterexamples, Grenz- oder Scope-Fälle und prüft sie mit dem echten ilivalidator.

```json
{
  "modelText": "<vollständiger Modelltext>",
  "constraint": "Demo.Data.Item.MinimumValue"
}
```

Wichtige Felder:

- `generationVerified`: alle tatsächlich erzeugten Fälle hatten im Validator das erwartete Ergebnis.
- `coverageComplete`: alle geplanten semantischen Proof-Ziele konnten synthetisiert werden.
- `coverageUnsolved`: Proof-Ziele, die aufgrund einer bewussten Grenze oder des endlichen Solvers nicht erzeugt werden konnten.
- `verification`: reale ilivalidator-Ergebnisse.

`generationVerified=true` und `coverageComplete=false` sind kein Widerspruch: Die erzeugten Fälle können vollständig verifiziert sein, obwohl zusätzliche gewünschte Coverage-Fälle nicht synthetisierbar waren.

## Aufgabe: eigene Constraint-Testfälle prüfen

Wenn konkrete Testdaten vorgegeben sind, ist `testIliConstraint` das richtige Tool. Es ist **nicht** als zusätzlicher Standarddurchlauf nach einem bereits erfolgreichen automatischen Proof gedacht.

Typische Gründe für explizite Testfälle:

- ein fachlich wichtiger Produktionsfall,
- eine bewusst nicht automatisch synthetisierte Geometriekonstellation,
- ein Regressionstest für einen bekannten Validator-Randfall.

## Aufgabe: XTF erzeugen oder validieren

### Minimales XTF erzeugen

```json
{
  "modelText": "<vollständiger Modelltext>",
  "maxObjectsPerClass": 1
}
```

`generateExampleXtf` liefert unter anderem `xtfText`, `basketCount`, `objectCount`, `objectsByClass` und `skippedClasses`.

Kann für eine Klasse kein sicherer Pflichtwert erzeugt werden, wird die Klasse mit Begründung in `skippedClasses` aufgeführt, statt fragwürdige Beispieldaten zu erfinden.

### XTF validieren

```json
{
  "modelText": "<vollständiger Modelltext>",
  "xtfText": "<?xml version=\"1.0\" encoding=\"UTF-8\"?> ..."
}
```

`validateXtf` liefert `valid`, strukturierte `messages`, `errorCount` und `warningCount`.

## IliDoc und Metaattribute

- `iliDoc` erzeugt einen INTERLIS-Dokumentationskommentar wie `/** Beschreibung */`.
- `metaAttributes` erzeugt echte INTERLIS-Metaattribute wie `!!@ title="Beispiel"`.
- Für Stringwerte ist `value` gedacht; `rawValue` wird unverändert hinter `=` ausgegeben.

Beispiel:

```json
{
  "name": "Building",
  "iliDoc": "Gebäude im Bestand",
  "metaAttributes": [
    { "name": "title", "value": "Gebäude" }
  ],
  "attrLines": []
}
```

## Umgang mit Fehlern und offenen Fragen

Technische Fehler und fachliche Unsicherheiten sind unterschiedliche Dinge:

- Compilerfehler werden technisch behoben. `validateIliModel` kann dafür `sourceExcerpt` mit dem relevanten Quellausschnitt liefern.
- Automatisierte Regelverletzungen werden als technische Findings behandelt.
- `manualChecks` und `openQuestions` sind bewusst nicht automatisch entscheidbar.
- Generierte Namen für Beziehungen oder Rollen sind technische Platzhalter, solange sie fachlich nicht bestätigt wurden.
- `coverageUnsolved` oder Safety-Reason-Codes bei Constraint-Proofs werden berichtet und nicht durch angenäherte Semantik ersetzt.

## Weiterführende Dokumentation

- [Tool-Referenz](TOOL_REFERENCE.md)
- [Agentische Arbeitsabläufe](AGENT_WORKFLOWS.md)
- [Constraints](CONSTRAINTS.md)
- [Architektur](ARCHITECTURE.md)
