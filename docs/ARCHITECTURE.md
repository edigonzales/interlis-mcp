# Architektur

Dieses Dokument beschreibt die aktuelle technische Architektur von `interlis-mcp` und die wichtigsten Verträge zwischen MCP-Transport, INTERLIS-Compiler, Validator, Wissenskomponenten und agentischem Client.

## Systemgrenze

`interlis-mcp` ist ein **fachlicher MCP-Server**, kein allgemeiner IDE- oder Dateiserver.

```mermaid
flowchart LR
    Workspace["Workspace / .ili-Dateien"]
    Agent["Coding-Agent / MCP-Client"]
    MCP["interlis-mcp"]
    Ili2c["ili2c"]
    Validator["iox-ili / ilivalidator"]
    Knowledge["Regeln, Resources, Prompts, Modellkorpus"]

    Workspace <--> Agent
    Agent <--> |STDIO JSON-RPC| MCP
    MCP --> Ili2c
    MCP --> Validator
    MCP --> Knowledge
```

Der Agent besitzt den Workspace. Er liest Dateien, übergibt vollständigen Modelltext an MCP-Tools und schreibt freigegebenen `updatedModelText` wieder in das Repository. `interlis-mcp` benötigt deshalb keine direkte Kopplung an VS Code, einen Language Server oder ein Dateisystem-Editing-Protokoll.

## Laufzeit

Die Anwendung ist eine nicht-webbasierte Spring-Boot-Anwendung.

Wichtige Eigenschaften:

- Java 21
- Spring Boot 4.1.0
- Spring AI 2.0.0
- STDIO-Transport
- synchroner MCP-Server
- Tools, Resources und Prompts aktiviert
- MCP-Completions deaktiviert

Die Registrierung der MCP-Schnittstellen erfolgt über den Spring-AI-Annotation-Scanner. Es gibt keine manuelle zentrale Tool-Liste im Produktivcode.

## Öffentliche MCP-Oberfläche

Die Oberfläche besteht aus drei Typen:

### Tools

Tools führen deterministische Arbeit aus oder liefern strukturierte Analyseergebnisse. Beispiele:

- `reviewIliModel`
- `reviewIliChange`
- `authorIliModel`
- `applyIliModelChanges`
- `generateIliConstraintCases`
- `authorIliMandatoryConstraint`
- `generateExampleXtf`

### Resources

Resources liefern stabile Wissensblöcke, beispielsweise:

- Modellierungsregeln,
- agentischen Arbeitsablauf,
- Tool-Auswahl,
- Constraint-Arbeitsablauf,
- Index des lokalen Modellkorpus.

### Prompts

Prompts geben einem Agenten aufgabenspezifische Arbeitsanweisungen, ohne dass jeder MCP-Client eine eigene vollständige Tool-Hierarchie pflegen muss.

Die exakte öffentliche Tool-Schemaoberfläche wird durch `ToolRegistrationContractTest` geschützt.

Modell-Repositories sind Serverkonfiguration (`interlis.mcp.model-repositories`) und kein wiederholter Parameter der Tool-Schemas. Das hält die öffentliche Oberfläche kleiner und verhindert, dass einzelne Agentenaufrufe beliebige Repository-Grenzen verschieben.

# Kompilierung als gemeinsame Grundlage

`IliCompilerService` kapselt ili2c und normalisiert Compilerresultate. Viele High-Level-Funktionen verwenden dieselbe Compilerabstraktion, statt eigene Parserpfade einzuführen.

Ein Compilation-Result enthält bei erfolgreicher Kompilierung die `TransferDescription`, die als typisierte Quelle für weitere Analysen dient.

Compilerdiagnosen des vom Benutzer übergebenen Modelltexts können zusätzlich einen kleinen `sourceExcerpt` mit Quellkontext erhalten. Meldungen, die zu importierten oder anderen Dateien gehören, werden nicht fälschlich mit einem Ausschnitt des Hauptmodells angereichert.

## Warum der ili2c-AST wichtig ist

Semantische Werkzeuge verlassen sich nicht auf String-Heuristiken, wenn der Compiler die benötigte Information bereits typisiert kennt.

Beispiele:

- `reviewIliChange` vergleicht analysierte Modellelemente.
- `applyIliModelChanges` löst alle Zielobjekte über dasselbe kompilierte Before-Metamodell auf.
- Constraint-Tools lesen echte ili2c-Constraint-Knoten und Pfade.
- `renameModelElement` arbeitet über das Metamodell und regeneriert anschliessend Modelltext.

# Modellanalyse und Reviews

## Einzelner Modellstand

`reviewIliModel` ist das High-Level-Gate für einen vollständigen aktuellen Modellstand.

```text
modelText
   |
   v
IliCompilerService  ----> Compilerdiagnosen
   |
   v
ModelAnalysisTools  ----> Struktur
   |
   v
ModelingRuleTools   ----> automatische Findings / manuelle Checks
   |
   v
reviewIliModel
```

Der Modelltext wird dabei einmal kompiliert. Nachgelagerte Auswertungen nutzen den kompilierten Zustand weiter.

## Vorher-/Nachher-Review

`reviewIliChange` kompiliert beide Stände jeweils einmal:

```text
Before -> compile -> analysis --\
                              semantic diff -> impact / breaking changes
After  -> compile -> analysis --/                  |
                                                 afterReview
```

`afterReview` wird aus dem bereits kompilierten Nachher-Modell erzeugt; für denselben Zustand ist keine dritte Kompilierung nötig.

# Source-preserving Modelländerungen

Bei source-preserving Änderungen soll möglichst wenig Originaltext verändert werden. Kommentare, Reihenfolge, Whitespace und Zeilenendungen ausserhalb der Einfügestelle bleiben erhalten.

`applyIliModelChanges` folgt vereinfacht diesem Muster:

```text
typisierter atomarer Änderungsbatch
        |
        v
Before kompilieren
        |
        v
Ziel im ili2c-Modell auflösen
        |
        v
exakte Einfüge- und Deklarationsstellen im Originaltext bestimmen
        |
        v
deterministisch gruppierte Patches anwenden
        |
        v
After kompilieren
        |
        v
semantischen Diff prüfen
```

`updatedModelText` wird nur freigegeben, wenn der semantische Diff zum gesamten verlangten Batch passt. Unerwartete zusätzliche Änderungen führen beispielsweise zu `UNEXPECTED_SEMANTIC_CHANGE`. Potenziell brechende Batches benötigen zusätzlich `allowPotentiallyBreaking=true`.

## Source-preserving ist nicht dasselbe wie Regeneration

`renameModelElement` verfolgt eine andere Strategie. Es nutzt das ili2c-Metamodell für ein robustes Rename und regeneriert danach das Modell. Das schützt die Semantik, kann aber Whitespace oder Deklarationslayout verändern.

Die beiden Werkzeugklassen erfüllen deshalb unterschiedliche Zwecke:

- `applyIliModelChanges`: möglichst kleine Quelltext-Patches plus atomarer semantischer Guard.
- `renameModelElement`: robuste modellweite Umbenennung, Formatierung darf sich ändern.

# Constraint-Architektur

Constraints besitzen eine eigene semantische Pipeline. Details zu den unterstützten Arten stehen in [CONSTRAINTS.md](CONSTRAINTS.md).

## Compiled Constraint Context

Ein aufgelöster Constraint-Kontext bündelt unter anderem:

- vollständigen Modelltext,
- erfolgreiches Compilerresultat,
- `TransferDescription`,
- ausgewählten ili2c-Constraint,
- constraint-level semantische IR.

Dadurch muss ein unverändertes Modell während Coverage, Solver, Objektgraph-Synthese und Validator-Fixture nicht immer wieder kompiliert werden.

## Semantische Repräsentationen

Je nach Constraint kommen verschiedene typisierte Ebenen zum Einsatz:

- constraint-level IR für MANDATORY, UNIQUE, EXISTENCE, PLAUSIBILITY und SET,
- `ConstraintExpression` für boolesche/skalar auswertbare Ausdrücke,
- Object-Set-IR für SET-Objektmengen wie `ALL` und navigierte Objektpfade,
- typisierte Pfadinformationen für Attribute und Navigation.

Nicht unterstützte Semantik wird nicht durch String-Heuristiken approximiert. Sie bleibt als expliziter nicht übersetzter oder nicht beweisbarer Fall sichtbar.

## Proof-Pipeline

```text
kompilierter Constraint
       |
       v
semantische IR
       |
       v
Coverage Planner
       |
       v
endlicher Goal Solver
       |
       v
ConstraintModelSynthesizer
       |
       v
modellbewusste Testobjekte / Links / Baskets
       |
       v
ConstraintTestTools
       |
       v
XTF
       |
       v
ilivalidator
```

Der interne Evaluator ist ein Hilfsmittel, nicht die finale Instanz. `generationVerified` oder `proofVerified` wird nur auf Grundlage der echten Validatorergebnisse freigegeben.

## Source-preserving Constraint-Authoring

Die typisierten Authoring-Tools verwenden dieselbe `IliConstraintSpec`-Hierarchie und den gemeinsamen `ConstraintAuthoringEngine`. Die JSON-Schemas bilden MANDATORY, UNIQUE, EXISTENCE, PLAUSIBILITY und SET als echte, über `kind` diskriminierte `oneOf`-Unionen ab; das gemeinsame Resultat publiziert auch die zwölf zulässigen Statuswerte als geschlossenes Enum. `ConstraintSourceEditService` gruppiert Constraint und abgeleitete Imports in einem source-preserving Patchsatz.

Der Renderer übergibt ein Constraint-Fragment einschliesslich Dokumentation und Metadaten. Anhand des kompilierten Kontexttyps fügt der Quelltexteditor dieses Fragment innerhalb einer View vor deren `END` ein; für Klassen, Strukturen und Assoziationen ergänzt er einen externen `CONSTRAINTS OF`-Block am Topic-Ende. Der Locator berücksichtigt mehrteilige View-Header mit Projektion und `WHERE`-Klauseln sowie den umschliessenden Topic. Einrückung wird nur aus führenden Leerzeichen und Tabs gewonnen, auch bei `VIEW TOPIC` und `CONTRACTED MODEL`.

Die Compilerregressionen `ConstraintViewAuthoringRegressionTest` verwenden die unveränderten öffentlichen v1-Modelle und eingefrorene typisierte Testspezifikationen für P04/P05/P07/P08/P10/N11. Sie prüfen Einfügung und Auflösung im Compiler-AST unabhängig vom nachgelagerten Proof. P05 verlangt zusätzlich einen vollständigen Mandatory-Proof für `objectCount(Knoten_vonRef) == 1`; N11 erreicht nach erfolgreicher Kompilierung weiterhin `EXTERNAL_FUNCTION_SEMANTICS_REQUIRED`.

Der erfolgreiche Ablauf ist:

```text
Before-Compile
-> Constraint-Block rendern
-> source-preserving einfügen
-> After-Compile und Constraint auflösen
-> semantischen Roundtrip prüfen
-> Proof mit demselben kompilierten After-Kontext
-> semantischen Diff und afterReview aus Before/After ableiten
```

Damit besitzt das Authoring einen klaren Zwei-Compile-Vertrag.

# XTF-Infrastruktur

`XtfService` deckt allgemeine XTF-Erzeugung und -Validierung ab.

## `generateExampleXtf`

Die Generierung ist konservativ und deterministisch. Es werden nur Klassen und Pflichtwerte ausgegeben, die sicher erzeugt werden können. Nicht unterstützte Klassen werden in `skippedClasses` ausgewiesen, statt möglicherweise ungültige Platzhalter zu schreiben.

## `validateXtf`

Der Modelltext wird kompiliert und das übergebene XTF mit dem Validator geprüft. Fehler und Warnungen werden strukturiert gesammelt.

## Constraint-Fixtures

Constraint-Tests verwenden `TypedValueFixtureFactory` und `NavigationGraphSynthesizer` für skalare Werte, Referenz-OIDs, COORD, Linien-/Flächen-/Multigeometrien, eingebettete Strukturen, Association-Links und mehrere Baskets. Diese Infrastruktur erlaubt es, Proof-Fälle exakt auf eine erwartete Constraint-Verletzung auszurichten und andere Fixture-Fehler davon zu unterscheiden. Kann der installierte Validator eine Wertgleichheit nicht ausführen oder würde die Fixture bereits eine andere Modellregel verletzen, bleibt der Kandidat mit präzisem Reason-Code zurückgehalten.

# Multi-Basket-Semantik

Testobjekte können optional eine `basketId` tragen. Objekte desselben Topics und derselben ID werden in denselben Basket geschrieben.

Cross-Basket-Referenzen erhalten bei Bedarf `BID`. Damit können Scope-Unterschiede wie diese real geprüft werden:

```text
UNIQUE               -> global über Baskets
UNIQUE (BASKET)      -> getrennt pro Basket

SET CONSTRAINT       -> globale Objektmenge
SET CONSTRAINT (BASKET) -> getrennte Auswertung pro Basket
```

# Wissens- und Regelarchitektur

## Regelprofile

Kuratierte Regeln liegen als versionierte YAML-Ressourcen im Repository:

- `modeling-rules.core.yml`
- `modeling-rules.so.yml`

`CORE` ist portabel. `SO` erweitert `CORE` um Regeln des Solothurner Modellierungshandbuchs.

Jede Regel unterscheidet explizit zwischen automatischer und manueller Prüfung. Fachlich nicht deterministisch prüfbare Regeln bleiben `MANUAL`; der Server simuliert keine Gewissheit.

## Lokaler Modellkorpus

`ModelCorpusService` durchsucht die konfigurierten Pfade lokal und rekursiv nach `.ili`-Dateien. Die Suche ist:

- read-only,
- lexikalisch,
- in-memory,
- ohne Embeddings,
- ohne Netzwerkzugriff.

`readModelExample` darf nur Dateien innerhalb des konfigurierten Korpus lesen. Suchergebnisse sind Discovery-Metadaten; vollständiger Quelltext wird erst über dieses explizite Read-Tool geliefert.

# Fehler- und Sicherheitsprinzipien

## Keine erfundene Semantik

Wenn der Server oder Solver einen Fall nicht sicher bestimmen kann, wird dies als offene Frage, `coverageUnsolved`, Safety-Reason-Code oder anderer expliziter Fehlerzustand zurückgegeben.

## Compiler- und Validatorfehler nicht vermischen

Ein Constraint-Test unterscheidet:

- Fehler der Fixture oder des Modells,
- die erwartete gezielte Constraint-Verletzung.

Nur die gezielte Verletzung darf als erwarteter Counterexample gelten.

## Keine versteckten Seiteneffekte

Der MCP-Server schreibt keine Benutzerdateien und führt keine produktiven Datenbankoperationen aus. Temporäre Dateien für Compiler- und Validatoraufrufe sind interne Implementierungsdetails.

Modelltext ist auf 2 MiB, XTF auf 20 MiB und eine explizite Constraint-Suite auf 100 Fälle begrenzt. Temporäre Compilerpfade werden in öffentlichen Diagnosen als `<submitted-model>` normalisiert und Dateien in `finally`-Pfaden entfernt.

# Logging und STDIO

STDOUT ist für das MCP-Protokoll reserviert. Logging wird deshalb über STDERR geführt. `logback-spring.xml` reduziert unnötiges Framework-Logging, damit die Transportkommunikation sauber bleibt.

# Testarchitektur

Die Testschichten erfüllen unterschiedliche Aufgaben:

```text
Unit-/Semantiktests
       |
       v
Contract-Tests für MCP-Schemas
       |
       v
Golden Scenarios für Workflow-Verträge
       |
       v
STDIO-E2E gegen das gebaute JAR
```

Zusätzlich schützen Validator-Differentialtests die Übereinstimmung zwischen interner Constraint-Semantik und realem ilivalidator.

Die E2E-Tests starten das tatsächlich gebaute `interlis-mcp.jar` über STDIO. Damit werden nicht nur Java-Methoden, sondern auch Annotation-Scanning, JSON-Deserialisierung, MCP-Registrierung und Laufzeit-Wiring geprüft.

# Bewusste Architekturgrenzen

`interlis-mcp` soll ein fokussierter INTERLIS-Fachdienst bleiben. Insbesondere gehören folgende Verantwortungen nicht in den Server:

- allgemeines Workspace-Dateimanagement,
- IDE-spezifische Editierlogik,
- direkte Kopplung an einen bestimmten LSP-Client,
- fachliche Entscheidungen ohne Quelle,
- produktive DB- oder Deployment-Operationen.

Neue Funktionen sollten diese Grenze respektieren und möglichst vorhandene Compiler-, Review-, Source-Edit- und Proof-Infrastruktur wiederverwenden.

### Geordnete Constraint-Semantik

Die Ausdrucksauswertung beendet AND nach dem ersten nicht wahren und OR nach dem ersten
nicht falschen Operanden. Fehlende Werte (`Undefined`), fachlich abgebrochene Auswertung
(`NotComputable`) und technische Fehler bleiben getrennt. Implikationen werden intern
verzögert ausgewertet und in beiden Sprachversionen als NOT/OR gerendert.

Coverage verwendet für Belegungsmuster eigene `StateCondition`-Prädikate. Solver und
unabhängige Unerreichbarkeitsanalysen prüfen diese Zustände ohne synthetische DEFINED-Ausdrücke.
`ConstraintExpressionComparison` vergleicht geordnete Strukturen anstelle bereinigter Texte.
`ConstraintValidatorCompatibility` erkennt die vom Compiler erhaltenen nativen
Implikationsknoten vor der Proof-Planung und meldet die bekannte Grenze von iox-ili 1.24.4.
Diese Schritte verwenden den bestehenden kompilierten Kontext; Authoring bleibt bei zwei
Kompilierungen. Öffentliche Payloads und Werkzeugnamen ändern sich nicht.


### Gemeinsamer View-Kontext

`ViewProofScope` leitet eine direkte Identitätsprojektion ausschliesslich aus dem
Compiler-AST ab. Die Planungs-IR erhält den Basisklassenkontext; der ursprüngliche
Constraint und die Compilation bleiben erhalten. Die gemeinsame Modellbindung
nimmt zusätzlich die Filterreferenzen auf. `ConstraintGoalSolver` kann festgelegte
Werte sperren, und die unabhängige skalare Unerreichbarkeitsprüfung berücksichtigt
die geordnete View-Selektion.

`ViewProofFixtures` materialisiert diese Belegungen als übertragbare Basisobjekte
mit konsistent umbenannten OIDs, Beziehungen und Basket-IDs. Für notwendige abstrakte
Beziehungsziele wählt sie deterministisch eine konkrete Fixtureklasse; deren gültige
Materialisierung wird vollständig vom Validator geprüft. Diese Wahl ist keine
Änderung an fachlichen Zielbelegungen und kein Unerreichbarkeitsbeweis.
`ViewProofCoverage` ergänzt erreichbare Filterzustände und vergleicht geplante
Mitgliederzahlen mit der realen Population. Zusätzliche Basisobjekte aus der
Pflichtwert-Ergänzung dürfen den Nenner oder SET-Zähler nicht unbemerkt verändern.

`ConstraintTestTools` konfiguriert das View-Modell als zusätzliches Validierungsmodell,
deaktiviert Fremdconstraints im gesamten kompilierten TransferDescription und zählt
die View-Selektion anhand des gefüllten Validator-Pools. Für leere SET-Populationen
registriert ein expliziter View-Basket den Constraint. Ein Ausführungsnachweis aus
dem Validatorprotokoll verhindert einen erfolgreichen Proof durch blosse Abwesenheit
von Fehlermeldungen. Typisierte Proof-Resultate übernehmen die View-Diagnostik.


### Objektzählungen über mehrstufige Pfade

`ConstraintExpression.ObjectCount` trägt einen Objektmengenpfad als eigenes IR-Element. Der strukturelle Roundtrip erhält ihn auch innerhalb verschachtelter boolescher Ausdrücke. `ConstraintModelSynthesizer` bindet jeden Schritt an das kompilierte Rollen-, Referenz- oder Composition-Modell und materialisiert Klassenobjekte direkt, auch ohne skalare Attribute. `NavigationGraphSynthesizer` verwendet diese gemeinsame Implementierung für SET. Decision Table übersetzt `aggregate=OBJECT_COUNT` in die vorhandene typisierte Mandatory-Spezifikation.

`ObjectPathRoutes` ermittelt konkrete Klassenrouten nach Pfadposition, berücksichtigt gemeinsame Präfixe und prüft alle Routen. `ObjectCountTopologyPlanner` ergänzt Solverziele für leere optionale Zwischenstufen, Verzweigungen, gemeinsame Zielidentitäten und gemischte konkrete Typen. Die Graph-Synthese prüft nach allen Belegungen die Zählungen erneut; inverse Rollen und überlappende Pfade dürfen keine Belegung unbemerkt verändern. Pro Solverziel teilen sich erste Suche und numerische Nachsuche weiterhin 50.000 Versuche. Das Modell begründet Kardinalitätsausschlüsse unabhängig von dieser Suche.

Nach Fixture-Ergänzung und Aufbau des echten Validator-Pools wertet `ObjectCountVerification` die ursprünglichen kompilierten Zählausdrücke und ihre Pfadpräfixe aus. `objectCounts` in Rohresultat und typisierter Fallverifikation enthält geplante und tatsächliche Anzahl, unterschiedliche Zielidentitäten, konkrete Typen je Schritt sowie geprüfte Topologiepflichten. Die Zählpolitik `PINNED_VALIDATOR_PATH_OCCURRENCES` bezeichnet ausdrücklich das Verhalten des installierten Validators: zwei Wege zu derselben OID zählen zweimal. Abweichungen sperren den Proof.

Es gelten höchstens acht Navigationsschritte, acht konkrete Routenkombinationen und 64 explizite Klassenobjekte plus Beziehungen pro Fixture. Ergänzte Pflichtbeziehungen und Strukturinstanzen behalten zusätzliche eigene Budgets. Fehlende konkrete Routen, Budgetüberschreitungen, Zählabweichungen und Kardinalitäts-/XTF-Fehler bleiben strukturierte Proof- beziehungsweise Fixture-Grenzen. Kein Solver- oder Fixture-Fehler begründet einen erfolgreichen Ausschluss. Authoring verwendet unverändert genau zwei Kompilierungen.
