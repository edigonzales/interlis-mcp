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

Die Anwendung ist kein Webserver. `spring.main.web-application-type=none` deaktiviert den Web-Stack.

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
`true` gesetzt. In eingebetteten Spring-Test- oder Anwendungskontexten bleibt
der Wert standardmässig `false`, damit ein EOF des Test-Streams nicht den
gesamten Kontext beendet.

`interlis.mcp.model-repositories` konfiguriert die ili2c-/ilivalidator-Repositories einmal pro Server. Öffentliche Tools nehmen keinen Repository-Override pro Aufruf entgegen. Per Environment kann die Property als `INTERLIS_MCP_MODEL_REPOSITORIES` gesetzt werden.

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

Der geheimnisfreie Test-Job läuft für Pushes, Pull Requests, manuelle Starts und reine Dokumentationsänderungen:

```text
./gradlew clean test e2eTest --no-daemon
```

Erst nach erfolgreichem Test veröffentlicht ein separater Job auf `main` das Multi-Arch-Image. Registry-Secrets stehen nur diesem Publish-Job zur Verfügung. Externe Actions und das Container-Basisimage sind auf Commits beziehungsweise Digest gepinnt.

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
- `authorIliModel`: genau ein Compile; AST-Roundtrip, Constraint-Proofs und Review verwenden dessen Kontext.
- `applyIliModelChanges`: genau ein Before- und ein Kandidat/After-Compile für den gesamten Batch; Proofs und Review werden daraus abgeleitet.

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

## Abnahme der ergänzenden Ergebnisnachweise (26. September 2026)

Der Arbeitsstand mit `evidence`, automatischer XTF-Beispielvalidierung und
`constraintInteractions` wurde unter Java 21 mit `./gradlew check e2eTest` geprüft:

| Prüfung | Ergebnis |
| --- | --- |
| Java-Tests einschliesslich Schema-, Handler- und Differentialtests | 544 bestanden, keine ausgelassen |
| STDIO-E2E gegen das gebaute JAR | 19 bestanden, keine ausgelassen |
| Python-Benchmark-Prüfer und Recorder | 32 bestanden |
| `git diff --check` | Keine Whitespace-Fehler |

Die Regressionen prüfen insbesondere unabhängige Altersgrenzen (17/18/19), getrennte
Mandatory-Auswertung bei UNDEFINED, numerische Präzision, Enum-Widersprüche, nicht
unterstützte Regeln, das echte 50.000-Zustände-Budget sowie den unveränderten
Ein-/Zwei-Compile-Vertrag. Ein zusätzlicher Befund darf weder die bestehende Freigabe
noch `requiresUserDecision` verändern. Die MCP-Oberfläche bleibt bei 27 Tools mit
unveränderten Eingabeparametern; die vorhandenen Schema-Grössenlimits werden eingehalten.

Bei Constraint-Tests zählt `evidence.constraintTests.errorCount` fehlgeschlagene Testerwartungen,
nicht die erwarteten Validatorverletzungen erfolgreicher negativer Testfälle.
Warnungen der ausgeführten Fälle werden separat zusammengezählt.

Die skalare Analyse ist kein vollständiger Erfüllbarkeitstest für Objektgraphen.
Ein validierter Beispieltransfer prüft nur die tatsächlich übertragenen Daten.
Fachliche Akzeptanz und die Herkunft expliziter Erwartungen werden nicht behauptet.

Die eingefrorenen Benchmark-Suiten und Referenzanforderungen wurden nicht verändert;
ein neuer freier Agentenbenchmark über einen nativen Connector wurde nicht ausgeführt.
Die obigen technischen Testzahlen sind keine Agentenerfolgsquote.

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

Der native Vertrag behält 27 Tools und das Limit von 50.000 Bytes je Tooldeklaration.
`expectationSource` ist eine optionale Herkunftsangabe des Aufrufers, kein Sicherheitsnachweis.
Die strengere Schreibregel lebt im Agentenworkflow, nicht in serverseitigen Freigabefeldern.

Die zusätzliche Suite unter `evals/constraint-authoring-workflow` und ihr Offline-Prüfer werden
mit `check` geprüft. Ihre unabhängigen Referenzfälle werden gegen den echten Validator getestet.
Ein freier nativer Agentenlauf ist mangels Connector ausstehend; synthetische Prüfer-Tests oder
Java-/STDIO-Tests dürfen nicht als Agentenerfolgsquote ausgegeben werden.

### Technische Abnahme vom 26. September 2026

Mit Java 21.0.10 wurde `./gradlew check e2eTest --console=plain` erfolgreich ausgeführt:
554 Java-Tests, 20 STDIO-E2E-Tests, 32 bestehende Benchmark-/Recorder-Tests und 6 neue
Workflow-Prüfertests; keine Fehler oder übersprungenen Java-/E2E-Tests. Native Verträge für
27 Tools, Deklarationen unter 50.000 Bytes und bestehende Compileranzahlen bestehen.
`git diff --check` ist sauber; eingefrorene Rekonstruktions-Suiten und Automation bleiben
unverändert. Diese technische Abnahme enthält keinen freien Agentenlauf.

## Schlanke Ausgabegrenzen für Agenten

`ModelHashes` berechnet SHA-256 des unveränderten UTF-8-Texts und ergänzt die jeweiligen
öffentlichen Ergebnisse. Die bestehenden frühen Rückgaben bleiben im vollständigen
Implementierungspfad; die öffentliche Grenze ergänzt auch dort die zutreffenden Hashes.
Java-Kompatibilitätsüberladungen delegieren mit unverändertem Standardumfang.

`TestXtfOutput` kennt nur die öffentlichen `cases`, `verification.cases` und typisierten
`constraintProofs[].verification.cases`. Es gibt keinen rekursiven Feldfilter. Die Map-Projektion
kopiert nur betroffene Ausgabecontainer; die typisierte Projektion bearbeitet das frisch erzeugte
öffentliche DTO. Interne Validatorergebnisse, Evidence und Freigabeentscheidungen bleiben
unberührt. Die Projektion benötigt keine Compiler- oder Validatoraufrufe.

Es entstehen keine Tools, Aktionszustände, Caches, Sitzungen oder Detailabruf-Mechanismen.
Bei fehlendem `warningCount` wird konservativ kein XTF entfernt. Die Standards bleiben
kompatibel; `omittedSuccessfulTestXtfCount` erscheint nur bei tatsächlichen Auslassungen.

`SlimAgentOutputTest` misst serialisierte Antworten derselben vollständig geprüften Fixtures
vor und nach der Projektion und schreibt `build/reports/slim-response-sizes.txt`.
Diese Bytes sind keine Tokenmessung und belegen keine höhere Agentenerfolgsquote.

### Abnahme der schlanken Ausgabe vom 26. September 2026

Unter Java 21.0.10 besteht `./gradlew check e2eTest --console=plain`: 559 Java-Tests,
20 STDIO-E2E-Tests und 38 Python-Prüfertests (32 bestehende, 6 Workflow-Prüfertests).
Keine Java-/E2E-Fehler oder übersprungenen Tests; `git diff --check` ist sauber.
27 Tools, Schema-Grössenlimits und Compileranzahl-Verträge bleiben erhalten.

Gemessen an denselben vollständig geprüften Constraint-Fixtures, jeweils inklusive Modellhash:

| Fixture | Vollständiges JSON | Reduziertes JSON | Ersparnis | Entfernte XTF-Texte |
| --- | ---: | ---: | ---: | ---: |
| `age >= 18` | 9.136 Bytes | 7.866 Bytes | 13,9 % | 2 |
| `age > 18 AND age < 90` | 14.609 Bytes | 11.410 Bytes | 21,9 % | 5 |

Die Projektion selbst führt keine Kompilierung oder Validierung aus. Diese Fixture-Messung
ist keine allgemeine Grössengarantie und keine Agentenerfolgsquote. Ein freier nativer
Agentenlauf bleibt mangels Connector ausstehend; bestehende eingefrorene Suiten und Automation
wurden nicht verändert.

### Sichtbarer Mandatory-Eingabevertrag und Freigabehinweise

`authorIliMandatoryConstraint` beschreibt `spec.condition` und geordnete `children`
bereits in seiner Toolbeschreibung. Das dortige allgemeine `DEFINED`-Beispiel stammt
wie das Beispiel im Authoring-Prompt aus `ConstraintAuthoringGuidance`; es setzt ein
vorhandenes Attribut `value` voraus. Das rekursive Eingabeschema bleibt verbindlich,
auch wenn ein Connector die Argumente lediglich als `unknown` darstellt.

Die sichtbaren Beschreibungen für skalare Constraint-Ausdrücke nennen zusätzlich
`{"kind":"NUMERIC","value":7}` sowie `COMPARE` mit `operator` aus `==`, `!=`, `<`,
`<=`, `>` und `>=` und genau zwei geordneten `children`. `DEFINED`/`NOT` haben ein
Kind; `AND`/`OR` behalten die Reihenfolge. Diese Angaben gelten auch für Constraints
in `authorIliModel` und in `applyIliModelChanges`. Im Batch liegt die Spezifikation
unter `request.changes[i].addConstraint.constraint`, der Mandatory-Ausdruck dort
in `condition`. `NUMBER` und `=` sind keine alternativen Schreibweisen.


Eine vorgelagerte MCP-Schemavalidierung kann einen Aufruf ablehnen, bevor der Handler
läuft. Diese Diagnose ist kein reguläres Ergebnis mit `status=INVALID_SPEC` und darf
weder im Protokoll noch in der Bewertung so bezeichnet werden. Sie erweitert das
bestehende Reparaturlimit nicht; `expression` ist kein Alias für `spec.condition`.

Bei `requiresUserDecision=true` nennt der Agent die konkrete Frage und seinen Umgang
damit in der Abschlussantwort. Tatsächlich offene Fachentscheide verhindern das
Schreiben. Beurteilt er eine allgemeine Frage anhand des vorhandenen Auftrags als
für die konkrete Änderung unerheblich, muss er dies sichtbar begründen. Das Flag
bleibt unverändert; weder ein erfundener Modellzweck noch erfolgreiche Proofs ersetzen
diese Beurteilung. Es gibt keine pauschale Ausnahme für Modellzweckfragen.

### Gezielte Schemadiagnosen und verlustfreie Laufprotokolle

`ConstraintSpecSchema` wählt die 15 rekursiven Ausdrucksformen mit `allOf` und
`if`/`then` anhand des erforderlichen `kind` aus. Die bisherigen Feld-, Typ- und
Kindanzahlregeln bleiben erhalten. Andere diskriminierte Unions behalten `oneOf`.
So werden Kinder nicht nochmals unter allen unpassenden Ausdrucksformen geprüft.
Die Diagnose bleibt ein vorgelagerter Schemafehler. Das Deklarationslimit von
50.000 Bytes ist keine allgemeine Grenze für Fehlerantworten; Regressionen begrenzen
die repräsentativen verschachtelten Einzel- und Batchdiagnosen auf unter 8 KiB.

Der Workflow-Recorder unter `evals/constraint-authoring-workflow/tools` verwendet
native Tools und den vorhandenen `store`. Er archiviert Requests vor dem Aufruf,
sichert Antworten vor Dateizugriffen und schreibt höchstens 8 KiB UTF-8 je Block.
JSON und Blockhashes werden vor der abschliessenden Umbenennung geprüft. Seine
`persist`-Funktion wiederholt nur die Archivierung; sie ruft kein MCP-Tool auf.
Ein ausstehendes Archiv sperrt weitere Aufrufe. Toolausnahmen stehen separat in
`*-tool-exception.json`; sie ersetzen niemals eine empfangene Antwort.
Der Recorder ist kein Runner und trifft keine fachlichen oder Freigabeentscheidungen.
