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

Die Java-21-Images `sogis/interlis-mcp:latest` und
`ghcr.io/edigonzales/interlis-mcp:latest` unterstützen `linux/amd64` und `linux/arm64`.
`interlis-mcp-jvm` ist ein Alias desselben Images. Native-Builds sind momentan deaktiviert.

HTTP startet standardmässig; der MCP-Endpunkt ist `http://127.0.0.1:8080/mcp`:

```bash
docker run --rm -p 127.0.0.1:8080:8080 sogis/interlis-mcp:latest
```

Für einen STDIO-Client ohne TTY und mit offenem STDIN:

```bash
docker run --rm -i -e SPRING_PROFILES_ACTIVE=stdio sogis/interlis-mcp:latest
```

Wenn der Client beendet wird, schliesst er zuerst seine STDIN-Verbindung. Der
Standalone-Server erkennt EOF und beendet seinen Spring-Kontext kontrolliert.
Falls ein Client nach einem Timeout noch läuft, soll er den gestarteten
Prozessbaum explizit terminieren.

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

## Aufgabe: ein bestehendes Modell atomar ändern

Für unterstützte semantische Änderungen ist `applyIliModelChanges` dem manuellen Umschreiben vorzuziehen. Ein Batch kann Imports, Topics, Domains, Units, Klassen, Strukturen, Assoziationen, Attribute und Constraints ergänzen sowie Attribute ändern oder löschen.

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
    "changes": [
      {
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
      }
    ],
    "allowPotentiallyBreaking": false
  },
  "modelPurpose": "CAPTURE",
  "ruleProfile": "CORE"
}
```

Bei Erfolg liefert das Tool `status=APPLIED` und `updatedModelText`. Es kompiliert Vorher und Nachher, prüft den semantischen Diff auf unerwartete Kollateraleffekte und enthält bereits das `afterReview` des neuen Stands.

`UPDATE_ATTRIBUTE` enthält einen Patch. Nicht angegebene fachliche Eigenschaften bedeuten `KEEP`; IliDoc und Metaattribute besitzen explizite KEEP-/SET-/REMOVE- beziehungsweise KEEP-/REPLACE-/REMOVE-Aktionen. `REMOVE_ATTRIBUTE` braucht nur das eindeutige lokale `attributeFqn` und entfernt zusätzlich eindeutig zugeordnete Annotationen. Referenzen auf ein gelöschtes Attribut werden durch den After-Compile abgewiesen.

Bei potenziell brechender Semantik liefert das Tool ohne Bestätigung nur `candidateModelText` und `status=BREAKING_CHANGE_REQUIRES_CONFIRMATION`. Der unveränderte Batch muss danach explizit mit `allowPotentiallyBreaking=true` erneut aufgerufen werden.

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

Das Tool liefert einen vollständig neu generierten `updatedModelText`. Anders als `applyIliModelChanges` ist dieser Vorgang **nicht source-preserving** bezüglich Whitespace und Deklarationslayout. Verwende ihn deshalb, wenn semantische Robustheit wichtiger ist als die Beibehaltung der ursprünglichen Formatierung.

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

## Aufgabe: ein Geometrieattribut modellieren

Geometrieattribute werden über `GeometryTypeSpec` direkt in `authorIliModel` oder `applyIliModelChanges` modelliert. Ohne CHBase muss die fachlich gewählte Koordinatendomain explizit angegeben werden; der Server erfindet kein CRS und keine Achsgrenzen.

Beispiel:

```json
{
  "provider": "INTERLIS",
  "kind": "SURFACE",
  "coordDomainFqn": "Demo.Coord2",
  "arcs": true,
  "overlapMm": 0.001
}
```

Die erforderlichen Imports werden ausschliesslich aus dem explizit gewählten Typ abgeleitet und in `derivedImports` ausgewiesen. INTERLIS-Geometrien verlangen alle anwendbaren Angaben; CHBase akzeptiert nur bekannte Typen der tatsächlichen INTERLIS-Version.

## Aufgabe: einen neuen Constraint erstellen

Für neue Constraints sollte das höchste semantische Authoring-Tool verwendet werden:

| Constraint-Art | Tool |
| --- | --- |
| MANDATORY | `authorIliMandatoryConstraint` |
| EXISTENCE | `authorIliExistenceConstraint` |
| PLAUSIBILITY | `authorIliPlausibilityConstraint` |
| SET mit `OBJECT_COUNT` oder `BOOLEAN_EXPRESSION` | `authorIliSetConstraint` |
| UNIQUE | `authorIliUniqueConstraint` |

Ein typisiertes Authoring-Tool liefert bei Erfolg `proofVerified=true`, den semantischen Diff, `afterReview` und `updatedModelText`. Proof und Modellreview sind damit für genau diesen Text abgeschlossen; ein zusätzliches `reviewIliChange` ist nicht nötig.

Alle fünf Tools erwarten `modelText`, `contextFqn` und ein `spec` mit verbindlichem `kind`. Alte flache Payloads werden abgewiesen. Ein kompilierbarer, aber nicht vollständig bewiesener Stand erscheint nur als `candidateModelText`, beispielsweise mit `PROOF_INCOMPLETE` oder `EXTERNAL_FUNCTION_SEMANTICS_REQUIRED`.

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
- `coverageComplete`: alle verbleibenden semantischen Proof-Ziele sind durch verifizierte Fälle abgedeckt; nachweislich unerreichbare strukturelle Ziele stehen separat in `coverageExcludedGoals`.
- `coverageUnsolved`: Proof-Ziele, die aufgrund einer bewussten Grenze oder des endlichen Solvers nicht erzeugt werden konnten.
- `verification`: reale ilivalidator-Ergebnisse.

`generationVerified=true` und `coverageComplete=false` sind kein Widerspruch: Die erzeugten Fälle können vollständig verifiziert sein, obwohl zusätzliche gewünschte Coverage-Fälle nicht synthetisierbar waren.

Bei `DEFINED` über skalare `TEXT`- und `MTEXT`-Attribute kann der Server unerreichbare Definiertheitszustände nachweisen. Dabei berücksichtigt er Pflichtangaben, Domain-Aliase und bereits unterstützte einwertige Pfade mit optionalen Zwischenbeziehungen. Textinhalte, Textvergleiche und Textfunktionen werden dadurch nicht zusätzlich analysiert. Nicht unterstützte Typen und ausgeschöpfte Analysebudgets bleiben offene Proof-Grenzen.


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

## Prüfnachweise und ihre Grenzen

Modellreviews, Änderungsreviews, High-Level-Authoring und Constraint-Tests enthalten
zusätzlich `evidence`. Bestehende Statuswerte und Freigabebedingungen bleiben unverändert.
Insbesondere verändern die neuen Befunde weder `updatedModelText` noch `requiresUserDecision`.

| Teil | Aussage |
| --- | --- |
| `compiler` | Tatsächliches ili2c-Ergebnis; beim Änderungsreview für beide Modellstände. |
| `modelingRules` | Automatisierte Regeln des gewählten Profils; manuelle Checks bleiben offen. |
| `constraintTests` | Automatisch abgeleitete Fälle oder explizit übergebene Erwartungen. |
| `constraintInteractions` | Begrenzte gemeinsame Untersuchung skalarer Mandatory-Regeln. |
| `businessAcceptance` | `NOT_RUN`: Übereinstimmung mit einer unabhängigen Fachquelle ist nicht geprüft. |

Jeder Teil enthält `status`, `scope`, `basis`, `checkedCount`, `errorCount`,
`warningCount` und `reasonCodes`. Statuswerte sind `PASSED`, `FAILED`, `HAS_WARNINGS`,
`INCOMPLETE`, `NOT_RUN` und `NOT_APPLICABLE`. Warnungen der Modellierungskonventionen
erscheinen als `HAS_WARNINGS`, auch wenn das historische `validForAutomatedRules=false` ist.
`checkedCount` zählt je nach Prüfumfang Modellstände, automatisierte Regeln, automatisch
getestete Constraints, explizite Testfälle oder untersuchte Constraint-Kontexte.

`proofVerified=true` bei einer Modellerzeugung ohne Constraints bleibt aus Kompatibilitätsgründen
möglich; `evidence.constraintTests.status=NOT_APPLICABLE` macht deutlich, dass keine
Constraint-Tests nötig waren. Ein Modellreview führt keine solchen Tests aus und meldet
`NOT_RUN`. Auch erfolgreiche endliche Tests beweisen keine allgemeine fachliche Richtigkeit.

### Unabhängige Erwartungen

Automatische Tests werden aus dem bereits formulierten Constraint abgeleitet. Ein versehentliches
`Alter > 18` kann deshalb seine technischen Tests bestehen. Für die Fachanforderung „mindestens
18“ sind getrennt vorgegebene Erwartungen nötig: 17 ungültig, 18 gültig, 19 gültig. Diese werden
über `testIliConstraint` geprüft; der Fall 18 deckt den Fehler auf. `CALLER_SUPPLIED_EXPECTATIONS`
kennzeichnet solche Fälle, ohne ihre fachliche Herkunft zu bestätigen.

### Gemeinsame skalare Regeln

`constraintInteractions` steht im Modellreview sowie in `afterReview`. Unterstützt sind konkrete
Klassen ohne Klassenvererbung, direkte numerische/Boolean-/Enum-Attribute, Literalvergleiche,
`DEFINED`, `NOT` und geordnete `AND`/`OR`. Andere Kontexte, Constraint-Arten, Pfade und Funktionen
stehen mit Gründen in `unsupported`. Die Analyse verwendet den bereits kompilierten Modellstand.

Pro Kontext gibt es `CONTRADICTION_PROVEN`, `SCALAR_ASSIGNMENT_FOUND`, `UNKNOWN` oder
`NOT_APPLICABLE`. Attribute mit fehlenden Werten werden separat in `undefinedAttributes`
angegeben. Jede Mandatory-Regel wird einzeln ausgewertet: Eine undefinierte erste Regel darf eine
Verletzung einer zweiten nicht verdecken. Nach maximal 50.000 untersuchten Zuständen über den
gesamten Review liefert eine nicht abgeschlossene Suche `UNKNOWN`.

Ein Widerspruch in einer unterstützten Teilmenge bleibt relevant, auch wenn andere Regeln nicht
untersucht werden können. Eine erfüllbare Teilmenge beweist dagegen keine Gesamtkonsistenz.
Eine skalare Belegung ist kein validierter Objektgraph. Widersprüchliche Klassenregeln können
Objekte ausschliessen, während ein leerer Transfer weiterhin gültig ist.

### Validierung generierter Beispiele

`generateExampleXtf` validiert jeden erzeugten Transfer einmal und liefert zusätzlich `validation`:
`status` (`VALID`, `INVALID`, `ERROR`, `NOT_RUN`), nullable `valid`, `errorCount`, `warningCount`,
`messages`, `scope` und `limitation`. `ERROR` bedeutet einen technischen Abbruch, `INVALID` einen
regulär abgelehnten Transfer. Ohne erzeugte Daten ist die Validierung `NOT_RUN`.

`generated=true` bedeutet weiterhin nur, dass XTF erzeugt wurde. Auch ungültiges XTF bleibt zur
Untersuchung erhalten; seine Validierungsdiagnosen sind von Generierungsdiagnosen getrennt.
Die Prüfung deckt nur den konkreten Transfer ab, nicht ausgelassene Klassen oder unausgeübte Regeln.
Für denselben unveränderten Transfer ist keine zweite routinemässige `validateXtf`-Runde nötig.

## Constraints aus Prosa mit fachlichen Erwartungen

Der Agent hält zuerst Anforderung, Modellbezug und entscheidende Erwartungsfälle fest. Mit
`analyzeIliModel(modelText, contextFqn=...)` erhält er dafür einen begrenzten `authoringContext`:
Attribute und Domains, Optionalität, Enum-Werte, numerische Grenzen, Vererbung, Rollen und
vorhandene Regeln. Ohne `contextFqn` bleibt die vollständige Analyse unverändert. Es erfolgt
keine automatische Zuordnung fachlicher Begriffe zu Modellelementen.

Eindeutige Anforderungen werden direkt bearbeitet. Nur fachlich wirksame Unklarheiten erfordern
Rückfragen: etwa fehlende Werte, eine leere Beziehung oder der Bereich einer Eindeutigkeit.
„Wenn A, dann B“ erlaubt keine automatisch ergänzte Umkehrung.

Vor dem Schreiben prüft der Agent die zuvor festgelegten Fälle mit `testIliConstraint` gegen
exakt den vom Authoring zurückgegebenen Modellstand. `expectationSource` kennzeichnet die
Herkunftsangabe: `USER_PROVIDED`, `USER_CONFIRMED`, `AGENT_DERIVED` oder standardmässig
`UNSPECIFIED`. Der Server verifiziert diese Herkunft nicht. Auch vom Agenten aus der Anforderung
abgeleitete Fälle sind keine unabhängige Fachabnahme; `businessAcceptance` bleibt `NOT_RUN`.

Beispiel: „mindestens 18, Alter erforderlich“ verlangt die Fälle 17 unzulässig, 18 zulässig,
19 zulässig und fehlendes Alter unzulässig. Ein automatischer Proof für `age > 18` ersetzt diese
Erwartungen nicht. Fehlgeschlagene oder nicht ausführbare notwendige Tests, ungültige Fixtures
und offene Fachentscheide verhindern das Schreiben im Agentenablauf. Diese Regel ändert keine
MCP-Statuswerte und kann vom Server gegenüber anderen Clients nicht erzwungen werden.

`explanation` in Constraint-Reviews und den `constraintProofs` beschreibt die tatsächlich
kompilierte Regel. `COMPLETE` bedeutet vollständige Unterstützung durch den Erklärer,
`PARTIAL` nennt Erklärungslücken und `UNAVAILABLE` bezeichnet eine fehlende Erklärung.
Keiner dieser Werte bestätigt fachliche Akzeptanz. Nicht vollständig unterstützte Funktionen
oder Views werden ausdrücklich begrenzt erklärt. Widerspruchsbefunde aus `constraintInteractions`
bleiben advisory und müssen sichtbar berichtet werden. Einzeltests isolieren den ausgewählten
Constraint; sie belegen keine vollständige Modellkonsistenz.

### Modellstand und Antwortumfang

`modelHashes` bindet Ergebnisse an den exakten übergebenen UTF-8-Modelltext (SHA-256, ohne
Normalisierung). Einzelanalysen und Constraint-Tests liefern `model`, Änderungsreviews
`before`/`after`; Authoring liefert je nach vorhandenem Text `before`, `after` und `candidate`.
Neue Modelle haben keinen `before`-Hash. Ein Hash auf einem Fehlerergebnis identifiziert nur
den Text und bestätigt keine erfolgreiche Prüfung. Importe und Validatorumgebung sind nicht
Bestandteil dieses Hashes.

Vor dem Schreiben muss `modelHashes.model` der Fachtests mit `modelHashes.after` des Authorings
übereinstimmen. Vor dem Überschreiben liest der Agent die Ausgangsdatei erneut und vergleicht
sie mit `before`. Bei Änderung setzt er auf dem aktuellen Stand neu an. Das ist keine atomare
Dateisperre und keine automatische Zusammenführung.

Für Authoring, Entscheidungstabellen, Fallgenerierung und explizite Tests kann der Agent
`includeSuccessfulTestXtf=false` setzen. Ausschliesslich XTF-Texte bestandener, tatsächlich
ausgeübter Fälle mit gültiger Fixture und ausdrücklich null Warnungen entfallen.
`omittedSuccessfulTestXtfCount` nennt dann deren Anzahl. Auch bestandene negative Fälle können
so verkürzt werden. Alle Erwartungen, Diagnosen, Coverage und Freigaben bleiben erhalten.
Ohne Parameter oder mit `true` bleibt die vollständige Ausgabe erhalten; der normale
Agentenablauf verwendet `false`. Ein erneuter Aufruf mit `true` dient nur konkreter Diagnose.

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
