# Entwicklerhandbuch

Dieses Handbuch beschreibt, wie `interlis-mcp` gebaut, getestet und erweitert wird. Für die fachliche Benutzung der MCP-Werkzeuge sind das [Benutzerhandbuch](USER_GUIDE.md) und die [Tool-Referenz](TOOL_REFERENCE.md) die besseren Einstiege.

## Technische Basis

Der aktuelle Build verwendet:

- Java Toolchain 21
- Gradle Wrapper 8.14.3
- Spring Boot 4.1.0
- Spring AI 2.0.0
- ili2c 5.6.8
- iox-ili 1.24.4
- ilivalidator 1.14.3

Verbindliche Laufzeitquelle für diese Versionen ist `build.gradle`. `gradle/libs.versions.toml` existiert ebenfalls, wird für die direkt in `build.gradle` deklarierten INTERLIS-Abhängigkeiten derzeit aber nicht als Quelle verwendet und kann ältere Werte enthalten. Die Dokumentation soll keine dritte, abweichende Versionsverwaltung bilden.

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

Die Anwendung ist kein Webserver. `spring.main.web-application-type=none` deaktiviert den Web-Stack.

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
```

Die Serverversion wird beim `processResources` aus `project.version` in `application.properties` eingesetzt. Lokale Builds verwenden typischerweise `0.0.LOCALBUILD`; CI-Builds verwenden die vom vorhandenen Versionierungsskript berechnete Version.

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
- Required-/Optional-Parameter,
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

Der GitHub-Workflow führt für den JVM-Build aus:

```text
./gradlew clean test
./gradlew build -x test
./gradlew e2eTest
```

Reine Markdown-Pushes sind im aktuellen Workflow über `paths-ignore: '**.md'` ausgenommen. Änderungen an Java-basierten MCP-Prompts/Resources, Properties oder anderen Laufzeitdateien lösen den normalen Build aus.

## Neues Tool hinzufügen

Ein neues Tool sollte nur eingeführt werden, wenn kein bestehendes Tool die Aufgabe sinnvoll erweitern kann.

Empfohlener Ablauf:

1. Verantwortlichkeit bestimmen: `tools`, `analysis`, `change`, `knowledge`, `constraint` oder `service`.
2. Vorhandene Services und Compilerkontexte wiederverwenden, statt einen parallelen Parser-/Compilerpfad anzulegen.
3. Öffentlichen Entry Point mit `@McpTool` und Parameter mit `@McpToolParam` annotieren.
4. Optionalität im Java-Typ und im MCP-Schema konsistent ausdrücken.
5. Fachliche Unsicherheit als Ergebniszustand modellieren; keine fehlende Semantik erfinden.
6. Fokussierte Tests ergänzen.
7. Bei öffentlichem Schema `ToolRegistrationContractTest` aktualisieren.
8. Wenn der vorgesehene Agentenablauf betroffen ist, Prompt/Resource und Golden Scenario prüfen.
9. Benutzer- oder Referenzdokumentation aktualisieren.

## High-Level-Tools statt Toolketten

Neue Features sollten die bestehende Hierarchie stärken, nicht Agenten zu immer längeren Toolketten zwingen.

Wenn beispielsweise ein neuer vollständiger Review-Befund benötigt wird, ist es meist besser, `reviewIliModel` sinnvoll zu erweitern, als den Agenten zu zwingen:

```text
reviewIliModel
-> neuesLowLevelTool
-> validateIliModel
-> checkModelingRules
```

High-Level-Tools sollen Compilation Results und analysierte Daten möglichst wiederverwenden.

## Compile Ownership

Mehrfaches Kompilieren desselben unveränderten Modelltexts ist sowohl teuer als auch ein Zeichen für unklare Zuständigkeit.

Bestehende Verträge:

- `reviewIliModel`: ein Compile.
- `reviewIliChange`: ein Compile für Before und ein Compile für After.
- `generateIliConstraintCases`: ein Compile für den bestehenden Modellstand.
- typisiertes Constraint-Authoring: ein Before- und ein After-Compile; der Proof verwendet danach den kompilierten After-Kontext.
- `applyIliModelChange`: Before und Kandidat/After werden innerhalb des Change-Workflows kontrolliert kompiliert; der enthaltene Review wird aus diesen Resultaten abgeleitet.

Neue Orchestratoren sollten deshalb bevorzugt Methoden verwenden, die bereits kompilierte Kontexte akzeptieren, statt öffentliche Tools intern erneut mit demselben Text aufzurufen.

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
- `SO`: Solothurn-spezifische Ergänzungen; beim Laden wird `CORE` automatisch mitgeführt.

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

## Docker-Publishing

Der Gradle-Task

```bash
./gradlew buildAndPushMultiArchImage
```

ist ein **Publish-Task**. Er ruft `docker buildx build --push` für `linux/amd64` und `linux/arm64` auf und veröffentlicht Tags unter `sogis/interlis-mcp`, darunter `latest` und versionsabhängige Tags.

Er ist nicht als lokaler „build only“-Task zu verstehen und benötigt eine passende Registry-Anmeldung.

Im GitHub-Workflow läuft das Publishing nur auf `main` ausserhalb von Pull Requests.

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
