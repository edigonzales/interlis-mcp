# Entwicklerhandbuch

Dieses Handbuch beschreibt, wie `interlis-mcp` gebaut, getestet und erweitert wird. Für die fachliche Benutzung der MCP-Werkzeuge sind das [Benutzerhandbuch](USER_GUIDE.md) und die [Tool-Referenz](TOOL_REFERENCE.md) die besseren Einstiege.

## Technische Basis

Der aktuelle Build verwendet:

- Java Toolchain 21
- Gradle Wrapper 9.5.1
- Spring Boot 4.1.1
- Spring AI 2.0.1
- ili2c 5.6.8
- iox-ili 1.24.4
- ilivalidator 1.14.3

Verbindliche Laufzeitquelle für diese Versionen ist `build.gradle`. Es gibt bewusst keinen zweiten, unbenutzten Versionskatalog.

## Projektstruktur

Die wichtigsten Bereiche sind:

```text
src/main/java/ch/so/agi/mcp/
  Application.java
  analysis/      Modellanalyse und Vorher-/Nachher-Review
  change/        typisierte semantische Modelländerungen
  constraint/    Constraint-IR, Binder, Solver, Source-Edit-Infrastruktur
  knowledge/     Regeln, Resources, Prompts, Modellkorpus
  model/         strukturierte DTOs
  service/       ili2c- und XTF-Dienste
  tools/         öffentliche MCP-Tool-Komponenten
  util/          gemeinsame Hilfsfunktionen

src/main/resources/
  application.properties
  knowledge/     kuratierte Regeldateien

src/test/java/   Unit-, Semantik-, Contract- und Golden-Scenario-Tests
src/e2e/java/    STDIO-End-to-End-Tests gegen das gebaute JAR

docs/            aktuelle thematische Dokumentation
```

## Bauen und starten

```bash
./gradlew bootJar
java -jar build/libs/interlis-mcp.jar
```

Der Boot-JAR heisst absichtlich immer:

```text
build/libs/interlis-mcp.jar
```

Für lokale Entwicklung:

```bash
./gradlew bootRun
```

Das JAR verwendet standardmässig STDIO. Für Streamable HTTP unter `/mcp`
das Profil `http` aktivieren.

```bash
java -jar build/libs/interlis-mcp.jar --spring.profiles.active=http
```

Der Standalone-Start beendet den Spring-Kontext, sobald der MCP-Client STDIN
schliesst oder EOF liefert. Ein Client soll deshalb zuerst seine letzte
Nachricht vollständig lesen, danach STDIN schliessen und nur bei einem
Timeout den Prozessbaum explizit terminieren. Ein `java -jar`-Prozess darf
nicht in einer Pipeline auf implizites Prozessende warten.

## MCP-Registrierung

Tools, Resources und Prompts werden über Spring-AI-Annotationen registriert.

Typische Imports:

```java
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpArg;
```

Es gibt keine manuell gepflegte zentrale Produktivliste aller Tools. Das reduziert doppeltes Wiring, erhöht aber die Bedeutung der Contract-Tests: Ein versehentlich neu registriertes oder verschwundenes Tool muss dort sichtbar werden.

Optionale Java-Parameter werden mit `required = false` und, wo passend, `org.jspecify.annotations.Nullable` modelliert.

## Laufzeitkonfiguration

Die Standardwerte liegen in `src/main/resources/application.properties`.

Wichtige Properties:

```properties
spring.main.web-application-type=none
spring.ai.mcp.server.stdio=true
spring.ai.mcp.server.type=SYNC
spring.ai.mcp.server.capabilities.tool=true
spring.ai.mcp.server.capabilities.resource=true
spring.ai.mcp.server.capabilities.prompt=true
spring.ai.mcp.server.capabilities.completion=false

interlis.knowledge.model-paths=
interlis.knowledge.max-model-bytes=1048576
interlis.knowledge.max-search-results=10
interlis.mcp.model-repositories=
```

`interlis.mcp.stdio.shutdown-on-eof` wird beim Standalone-JAR-Start intern auf
`true` gesetzt. In Spring-Tests bleibt der Wert standardmässig `false`, damit ein EOF des
Test-Streams den gemeinsam verwendeten Kontext offen lässt.

`interlis.mcp.model-repositories` konfiguriert die ili2c-/ilivalidator-Repositories einmal pro Server. Öffentliche Tools nehmen keinen Repository-Override pro Aufruf entgegen. Per Environment kann die Property als `INTERLIS_MCP_MODEL_REPOSITORIES` gesetzt werden.

Die Serverversion wird beim `processResources` aus `project.version` in `application.properties` eingesetzt. Lokale Builds verwenden `0.0.LOCALBUILD`. In GitHub Actions legt `IMAGE_VERSION` die Serverversion und den Image-Tag fest.

## Tests

### Unit-, Semantik-, Contract- und Golden-Scenario-Tests

```bash
./gradlew test
```

Diese Tests enthalten mehrere unterschiedliche Verträge.

#### Fokussierte Unit-/Semantiktests

Sie prüfen beispielsweise Renderer, Parseradapter, Binder, Solver, Modelländerungen und Constraint-Planner.

#### `ToolRegistrationContractTest`

Dieser Test schützt die öffentliche MCP-Tooloberfläche:

- registrierte Toolnamen,
- die exakte Menge der Required-/Optional-Parameter,
- eine Obergrenze für die serialisierte Tool-Deklaration,
- ausgewählte Beschreibungen,
- JSON-Deserialisierung komplexer DTOs,
- reale Handleraufrufe für wichtige strukturierte Werkzeuge.

Wenn ein öffentliches Tool oder sein Payload geändert wird, muss dieser Test bewusst geprüft und gegebenenfalls angepasst werden.

#### Golden Scenarios

Golden-Scenario-Tests modellieren deterministisch den vorgesehenen Agentenablauf, ohne ein LLM-Testframework einzuführen.

Beispiele für geschützte Verträge:

- ein vollständiges Modell wird mit einem High-Level-Review abgeschlossen,
- `reviewIliChange` kompiliert Vorher und Nachher genau einmal,
- fehlende fachliche Kardinalitäten bleiben offene Fragen,
- Modellbeispiele werden zuerst gesucht und danach vollständig gelesen,
- Constraint-Authoring beweist den Constraint ohne redundante Recompiles,
- ein separat geändertes Modell wird danach einmal mit `reviewIliChange` abgeschlossen.

Golden Scenarios prüfen die **Orchestrierung und Verträge**, nicht die Intelligenz eines LLM-Planners.

#### Validator-Differentialtests

`ConstraintValidatorDifferentialTest` vergleicht repräsentative explizite Assignments zwischen interner Constraint-Semantik und realem ilivalidator. Diese Tests sind absichtlich unabhängig von Solver und Coverage Planner.

`ConstraintDefinednessReachability` abstrahiert skalare `TEXT`-/`MTEXT`-Referenzen als vorhanden beziehungsweise undefiniert. Die vorhandenen Modellbindungen bestimmen Pflichtangaben und optionale Navigationsschritte; wiederholte Referenzen bleiben korreliert. Diese Erweiterung betrifft ausschliesslich Definiertheit: Textliterale, Textvergleiche und Textfunktionen erhalten keine zusätzliche Unerreichbarkeitsanalyse. Das Zustandslimit und die Ausschlussregeln für notwendige Witnesses/Gegenbeispiele bleiben unverändert; die separate Constraint-Interaktionsanalyse wird nicht erweitert.

### STDIO-E2E

```bash
./gradlew e2eTest
```

`e2eTest` hängt von `bootJar` ab und startet das gebaute JAR mit demselben Java-21-Toolchain-Kontext wie die Tests.

Damit werden unter anderem geprüft:

- MCP-Initialisierung,
- Tool-/Resource-/Prompt-Discovery,
- JSON-RPC über STDIO,
- Annotation-Scanning,
- DTO-Deserialisierung,
- echte Tool-Aufrufe gegen das gebaute Artefakt,
- ausgewählte vollständige Constraint-Authoring-/Proof-Pfade.

### CI

Der Workflow prüft Pull Requests, Pushes nach `main` und manuelle Läufe.

```bash
./gradlew --no-daemon clean check e2eTest bootJar
```

Die HTTP-Probe startet Container für `linux/amd64` und `linux/arm64`, prüft
`/mcp` und führt parallele Toolaufrufe mit unterschiedlichen Ergebnissen aus.
Nach erfolgreicher Prüfung verwendet der Publish-Job das JAR aus diesem Lauf.
Nur Main-Pushes und manuelle Läufe auf `main` veröffentlichen via Buildx
Versions- und `latest`-Tags für beide Architekturen auf Docker Hub und GHCR.
Registry-Zugangsdaten stehen ausschliesslich diesem Job zur Verfügung.

## Neues Tool hinzufügen

Ein neues Tool sollte nur eingeführt werden, wenn kein bestehendes Tool die Aufgabe sinnvoll erweitern kann.

Empfohlener Ablauf:

1. Verantwortlichkeit bestimmen: `tools`, `analysis`, `change`, `knowledge`, `constraint` oder `service`.
2. Vorhandene Services und Compilerkontexte wiederverwenden, statt einen parallelen Parser-/Compilerpfad anzulegen.
3. Öffentlichen Entry Point mit `@McpTool` und Parameter mit `@McpToolParam` annotieren.
4. Optionalität im Java-Typ und im MCP-Schema konsistent ausdrücken.
5. Fachliche Unsicherheit als Ergebniszustand modellieren; keine fehlende Semantik erfinden.
6. Fokussierte Tests ergänzen.
7. Bei Änderungen am öffentlichen Schema `ToolRegistrationContractTest` aktualisieren.
8. Wenn der vorgesehene Agentenablauf betroffen ist, Prompt/Resource und Golden Scenario prüfen.
9. Benutzer- oder Referenzdokumentation aktualisieren.

## High-Level-Tools statt Toolketten

Neue Features sollten die bestehende Hierarchie stärken, nicht Agenten zu immer längeren Toolketten zwingen.

Wenn beispielsweise ein neuer vollständiger Review-Befund benötigt wird, ist es meist besser, `reviewIliModel` sinnvoll zu erweitern, als den Agenten zu zwingen:

```text
reviewIliModel
-> neuesLowLevelTool
-> validateIliModel
```

High-Level-Tools sollen Compilation Results und analysierte Daten möglichst wiederverwenden.

## Compile Ownership

Mehrfaches Kompilieren desselben unveränderten Modelltexts ist sowohl teuer als auch ein Zeichen für unklare Zuständigkeit.

Bestehende Verträge:

- `reviewIliModel`: ein Compile.
- `reviewIliChange`: ein Compile für Before und ein Compile für After.
- `generateIliConstraintCases`: ein Compile für den bestehenden Modellstand.
- typisiertes Constraint-Authoring: ein Before- und ein After-Compile; der Proof verwendet danach den kompilierten After-Kontext.
- `authorIliModel`: genau ein Compile; AST-Roundtrip, Constraint-Proofs und Review verwenden dessen Kontext.
- `applyIliModelChanges`: genau ein Before- und ein Kandidat/After-Compile für den gesamten Batch; Proofs und Review werden daraus abgeleitet.

Neue Orchestratoren sollten deshalb bevorzugt Methoden verwenden, die bereits kompilierte Kontexte akzeptieren, statt öffentliche Tools intern erneut mit demselben Text aufzurufen.

**Bekannte ili2c-Einschränkung:** Bereits vor R1 wurde beobachtet, dass derselbe
Prozess ein ungültiges Modell mit `INTERLIS.MissingSyntheticType` zuerst mit
`valid=false` und bei Wiederholungen mit `valid=true` meldete. Die Einschränkung
gilt weiterhin als offen. Ihre Korrektur liegt ausserhalb von R1.1.

## Source-preserving Änderungen erweitern

Source-preserving bedeutet: Nur der beabsichtigte Quelltextbereich soll verändert werden.

Bei einer neuen semantischen Change-Operation sind mindestens folgende Guards wichtig:

- Ziel über ili2c auflösen,
- Änderungen an importierten Modellen verhindern,
- Originaltext und Zeilenendungen ausserhalb des Patches bewahren,
- Kandidatenmodell kompilieren,
- semantischen Vorher-/Nachher-Diff prüfen,
- `updatedModelText` nur bei erwarteter Semantik freigeben.

Ein Tool darf nicht „source-preserving“ genannt werden, wenn es das Modell vollständig regeneriert.

## Constraint-Funktionen erweitern

Vor einer Erweiterung muss geklärt werden, auf welcher Ebene sie gehört:

- neue Constraint-Art,
- neue Expression-Semantik,
- neue Pfad-/Objektgraph-Semantik,
- neue Fixture-Fähigkeit,
- neue Coverage-Strategie,
- reine Authoring-Syntax.

Wichtige Regeln:

### Validator bleibt Oracle

Der interne Evaluator darf Kandidaten bewerten und den Solver unterstützen. Öffentlich verifizierte Proof-Fälle müssen weiterhin durch ilivalidator laufen.

### Keine Approximation unbekannter Semantik

Wenn beispielsweise eine Geometriefunktion nicht korrekt materialisiert werden kann, ist ein expliziter Safety-Reason-Code besser als ein „ähnlicher“ skalarer Ersatztest.

### Solver bleibt endlich

Ein neuer Solverpfad soll seine endlichen Kandidaten und Suchgrenzen transparent halten. `NO_SOLUTION_FOUND` darf nicht als mathematische Unlösbarkeit ausgegeben werden.

### Compile-Kontext wiederverwenden

Planner und Validator-Fixtures erhalten nach Möglichkeit den bestehenden `CompiledConstraintContext`.

### Differentialtests ergänzen

Wenn eine neue interne Evaluatorsemantik eingeführt wird, sollte mindestens ein explizites Assignment gegen den realen Validator abgesichert werden.

## XTF-Erzeugung erweitern

Die allgemeine Beispieldatengenerierung ist konservativ. Für einen Pflichtwert gilt:

> Wenn kein sicher modellgültiger Wert erzeugt werden kann, wird die Klasse übersprungen und der Grund gemeldet.

Unsichere Platzhalterdaten sind schlechter als ein sichtbares `skippedClasses`.

Constraint-Fixtures dürfen spezifischer sein, müssen aber Nebenfehler sauber von der erwarteten Ziel-Constraint-Verletzung trennen.

## Modellierungsregeln pflegen

Regeln liegen unter:

```text
src/main/resources/knowledge/modeling-rules.core.yml
src/main/resources/knowledge/modeling-rules.so.yml
```

- `CORE`: portable Regeln.
- `SO-GIS`: Solothurn-spezifische Ergänzungen; beim Laden wird `CORE` automatisch mitgeführt.
- `SO-DATA`: CORE/SO-GIS ohne MDE-060, MDE-209, MDE-210 und mit zusätzlichen Prüfungen DATA-001/002.

Eine Regel muss klar angeben:

- `id`
- `title`
- `severity`
- `appliesTo`
- `checkKind`
- Quelle und Abschnitt
- Begründung
- Empfehlung

Nur deterministisch aus Modelltext oder ili2c-Metamodell prüfbare Regeln sollten `AUTOMATED` sein. Fachliche Entscheide bleiben `MANUAL` und erscheinen in `manualChecks`.

## Lokalen Modellkorpus erweitern

Der Modellkorpus ist absichtlich read-only und lokal. Änderungen an der Suche sollen folgende Eigenschaften bewahren, sofern nicht bewusst neu entschieden:

- keine Schreiboperationen in den Modellpfaden,
- kein implizites Netzwerk-Crawling,
- vollständiges Lesen nur innerhalb erlaubter Korpuspfade,
- Search-Hit und vollständiges Modell als getrennte Operationen.

## Logging

STDOUT ist Teil des MCP-Transports. Normale Logs gehören deshalb auf STDERR.

`logback-spring.xml` hält Framework-Noise klein. Neue Bibliotheken sollten nicht unkontrolliert auf STDOUT schreiben.

## Container-Image

`./gradlew buildImage` baut das ausführbare JAR und das lokale Image
`sogis/interlis-mcp:latest`. Der Container startet als Nicht-Root-Benutzer
mit dem Profil `http`. Für STDIO beim Start `-i -e SPRING_PROFILES_ACTIVE=stdio`
übergeben.

```bash
docker run --rm -p 127.0.0.1:8080:8080 sogis/interlis-mcp:latest
# Aus einem zweiten Terminal
python3 tools/mcp-smoke.py http://127.0.0.1:8080/mcp
```

## Dokumentation pflegen

Die Dokumentation unter `docs/` beschreibt den aktuellen Produktzustand. Sie soll nicht zu einem zweiten Issue-Tracker oder Implementierungsjournal werden.

### Wohin mit Spezifikationen?

- Offene geplante Arbeit: GitHub Issue oder PR-Beschreibung.
- Längerer Arbeitsentwurf: Datei auf dem Feature-Branch, wenn sie für Agent/Review hilfreich ist.
- Nach Umsetzung: dauerhafte Aussagen in die thematische Referenz übernehmen und den Arbeitsentwurf löschen.
- Historie: Git-Commits und PRs.
- Langfristig begründungsbedürftige Architekturentscheidung: bei Bedarf ein ADR unter `docs/adr/`.

### Keine chronologischen „Step/Epic“-Dokumente

Dateien wie `01-...`, `Epic-X`, „MVP-Status“ oder „nächster Umsetzungsschritt“ werden nach Abschluss nicht auf `main` als aktuelle Doku weitergeführt. Sie werden entweder in fachliche Referenzdokumente überführt oder entfernt.

### Dokumentations-Checkliste für öffentliche Änderungen

Bei jeder Änderung an einer öffentlichen Fähigkeit prüfen:

- Muss `README.md` angepasst werden?
- Muss das Benutzerhandbuch oder die Tool-Referenz angepasst werden?
- Ändert sich ein agentischer Ablauf?
- Ändert sich Constraint-Semantik oder ein Safety-Gate?
- Muss eine Architekturannahme dokumentiert werden?
- Müssen MCP-Prompt/Resource und deren Tests angepasst werden?

Codebeschreibung, maschinenwirksamer Agentenvertrag und menschliche Dokumentation sollen dieselbe Wahrheit ausdrücken.

## Prosa-Authoring-Workflow

`analyzeIliModel` besitzt den optionalen Parameter `contextFqn`. Der klassische Aufruf bleibt
unverändert; die gezielte Variante liefert begrenzte Listen und `authoringContext` mit
`AVAILABLE`/`UNAVAILABLE` und strukturierten Fehlercodes. Vererbung wird compilerbasiert aufgelöst,
Beziehungsziele werden nur eine Ebene tief mit skalaren Attributen beschrieben.

`ConstraintAnalysisService.reviewCompiled` und `ConstraintKnowledgeTools.resolveCompiledPath`
verwenden den vorhandenen `TransferDescription`. Auch String-Pfade in Funktionen verursachen
bei eingebetteten Erklärungen keine zusätzlichen Kompilierungen. Der automatische Proof
transportiert die Erklärung zum gemeinsamen `ConstraintProof`-Ergebnis. Nicht auflösbare
Proof-Kontexte bekommen `UNAVAILABLE`; Compiler-, Proof- und Freigabestatus bleiben unverändert.

Der native Vertrag behält das Limit von 50.000 Bytes je Tooldeklaration.
`expectationSource` ist eine optionale Herkunftsangabe des Aufrufers, kein Sicherheitsnachweis.
Die strengere Schreibregel lebt im Agentenworkflow, nicht in serverseitigen Freigabefeldern.

## Fachliche Constraint-Regressionen

Die Rekonstruktionsfälle liegen unter `src/test/resources/constraint/reconstruction`.
Die sechs INTERLIS-Modelle enthalten die Originalregeln. Markierungen grenzen die Regel ein,
die `ConstraintReconstructionTest` für den jeweiligen Authoring-Aufruf entfernt. Die JSON-Dateien
enthalten die MCP-Eingaben. Die importierten Modelle liegen unter `src/test/resources/constraint/imports`
und werden als lokales Modellrepository verwendet.

Die JUnit-Tests führen die registrierten MCP-Handler aus und kompilieren die Kandidaten mit ili2c.
Feste Sollwerte für XTF-Fälle prüfen mit ilivalidator die Zielregel und die Gültigkeit der Fixtures.
`ProseConstraintWorkflowTest` prüft die Altersgrenzen, undefinierte Werte und die gemeinsame
Wirkung zweier Regeln. `./gradlew check` führt diese Tests aus.

## Schlanke Ausgabegrenzen für Agenten

`ModelHashes` berechnet SHA-256 des unveränderten UTF-8-Texts und ergänzt die jeweiligen
öffentlichen Ergebnisse. Die bestehenden frühen Rückgaben bleiben im vollständigen
Implementierungspfad; die öffentliche Grenze ergänzt auch dort die zutreffenden Hashes.

`TestXtfOutput` kennt nur die öffentlichen `cases`, `verification.cases` und typisierten
`constraintProofs[].verification.cases`. Es gibt keinen rekursiven Feldfilter. Die Map-Projektion
kopiert nur betroffene Ausgabecontainer; die typisierte Projektion bearbeitet das frisch erzeugte
öffentliche DTO. Interne Validatorergebnisse, Evidence und Freigabeentscheidungen bleiben
unberührt. Die Projektion benötigt keine Compiler- oder Validatoraufrufe.

Die Standardausgabe lässt nur XTFs bestandener, ausgeübter Fälle mit gültiger Fixture
und `warningCount=0` aus. `includeSuccessfulTestXtf=true` liefert auch diese XTFs.
`omittedSuccessfulTestXtfCount` erscheint bei tatsächlichen Auslassungen.

`SlimAgentOutputTest` vergleicht beide Ausgabeformen vollständig geprüfter Fixtures und
prüft, dass die Projektion keine zusätzliche Kompilierung auslöst.

### Typed Authoring und Freigabehinweise

`authorIliConstraint` erwartet `spec.kind` und die Felder dieser Variante.
Das Beispiel im Prompt `author-interlis-constraint` zeigt einen DEFINED-Ausdruck
für ein vorhandenes Attribut. Details zu den rekursiven Knoten stehen in
[Constraints](CONSTRAINTS.md#typisiertes-authoring). Im Batch liegt dieselbe
Spezifikation unter `request.changes[i].addConstraint.constraint`.

MCP prüft das Eingabeschema vor dem Handler. Fachliche Spezifikationsfehler im
Handler liefern `INVALID_SPEC` mit `specDiagnostics`.

Bei Einträgen in `openQuestions` nennt der Agent die konkrete Frage und seinen Umgang
damit in der Abschlussantwort. Tatsächlich offene Fachentscheide verhindern das
Schreiben. Beurteilt er eine allgemeine Frage anhand des vorhandenen Auftrags als
für die konkrete Änderung unerheblich, begründet er das in der Abschlussantwort. Eine Frage mit
`decisionImpact=BLOCKING` klärt er mit dem Nutzer. Fragen mit `decisionImpact=ADVISORY`
bleiben als Hinweise sichtbar.

### Gezielte Schemadiagnosen

`ConstraintSpecSchema` wählt die 15 rekursiven Ausdrucksformen mit `allOf` und
`if`/`then` anhand des erforderlichen `kind` aus. Die bisherigen Feld-, Typ- und
Kindanzahlregeln bleiben erhalten. Andere diskriminierte Unions behalten `oneOf`.
So werden Kinder nicht nochmals unter allen unpassenden Ausdrucksformen geprüft.
Die Diagnose bleibt ein vorgelagerter Schemafehler. Das Deklarationslimit von
50.000 Bytes ist keine allgemeine Grenze für Fehlerantworten; Regressionen begrenzen
die repräsentativen verschachtelten Einzel- und Batchdiagnosen auf unter 8 KiB.

## Modellbasierte Pfadsuche

`ConstraintPathSearch` kompiliert nicht selbst: Der Tool-Handler übergibt die einmal kompilierte
`TransferDescription`. `ConstraintPathAnalysis` teilt Parserprüfung, Typ-/Kardinalitätsfakten
und Verwendungshinweise mit `resolveConstraintPath`.

Die Suche hält auch die Warteschlange begrenzt und verarbeitet Präfixe deterministisch nach
Länge und Name. Sie verwirft keine besuchte Klasse global, da unterschiedliche oder zyklische
Pfade fachlich verschieden sein können. Suchgrenzen und nicht unterstützte Formen bleiben
sichtbar. Die Verwendungshinweise sind Ausdrucksbausteine ohne Vergleichsoperator oder
fachlichen Grenzwert; Tests führen Einzelwert, Summe und Objektzählung durch das bestehende
Authoring und den echten Validator. Der STDIO-Test prüft zudem identische Pfadfakten aus
Suche und expliziter Auflösung.

## SO-DATA: Implementierung und Prüfungen

Das interne Enum `SO_DATA` serialisiert ausschliesslich als `SO-DATA`.
`McpDiscriminatorSchemaConfig` normalisiert auch die tatsächlich veröffentlichten
Werkzeugschemas. `KnowledgeRuleLoader` übernimmt CORE/SO-GIS ohne MDE-060, MDE-209 und
MDE-210 und ergänzt DATA-001/DATA-002. `IliComments` erkennt Kommentarspannen
lexikalisch ausserhalb von Strings; `REMOVE_COMMENTS` führt sie durch die atomare
Patch-/Compiler-/Review-Pipeline. Technische Constraint-Namen bleiben als native
INTERLIS-Bezeichner erhalten. Der profilgebundene Renderer ist aufruflokal;
CORE/SO-GIS verwenden weiterhin das bisherige Rendering.

`SoDataTest`, die Authoring-/Änderungstests und `ToolRegistrationContractTest`
prüfen Regeln, Quellerhaltung, fünf Constraint-Arten und reale MCP-Schemas.
Die Nutzung ist im [Benutzerhandbuch](USER_GUIDE.md#so-data-csv-validierungsmodelle) beschrieben.
