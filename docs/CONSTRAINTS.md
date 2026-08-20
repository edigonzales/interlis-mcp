# Constraints: Semantik, Authoring und Proofs

`interlis-mcp` behandelt INTERLIS-Constraints nicht als Textbausteine, sondern – soweit unterstützt – als **kompilierte Semantik**. Der Server liest den ili2c-AST, übersetzt relevante Konstrukte in eine typisierte interne Repräsentation, plant modellbewusste Testfälle, erzeugt XTF und lässt das Resultat vom echten ilivalidator prüfen.

Der interne Solver und Evaluator helfen beim Erzeugen geeigneter Fälle. **Der ilivalidator bleibt die abschliessende technische Instanz.** Ein intern plausibles Resultat wird nicht als Proof ausgegeben, bevor die erzeugten Transferdaten das erwartete Verhalten im Validator gezeigt haben.

## Überblick

| Constraint-Art | Bestehenden Constraint automatisch beweisen | Typisiertes High-Level-Authoring |
| --- | --- | --- |
| MANDATORY | ja | `authorIliMandatoryConstraint` |
| UNIQUE | ja | noch nicht; einfacher `createUniqueConstraint`-Snippet als enger Fallback |
| EXISTENCE | ja, mit dokumentierten Typgrenzen | `authorIliExistenceConstraint` für skalare Werte |
| PLAUSIBILITY | ja, mit echten Populationen | `authorIliPlausibilityConstraint` |
| SET | ja für `INTERLIS.objectCount(ALL)`-Subset | `authorIliSetConstraint` für dasselbe sichere Subset |

## Drei unterschiedliche Aufgaben

### Constraint erklären

`reviewIliConstraint` beantwortet Fragen wie:

- In welchem Kontext ist der Constraint definiert?
- Welche Attribute und Pfade werden referenziert?
- Welche Funktionen kommen vor?
- Welche Typen haben die Pfadendpunkte?
- Gibt es strukturelle Randfälle?

Das Tool erzeugt keine Witnesses oder Counterexamples.

### Constraint automatisch beweisen

`generateIliConstraintCases` erzeugt modellbewusste Proof-Fälle für einen bestehenden Constraint und prüft sie mit ilivalidator.

```json
{
  "modelText": "<vollständiger Modelltext>",
  "constraint": "Demo.Data.Item.MinimumValue"
}
```

### Explizite Testfälle prüfen

`testIliConstraint` ist für Testfälle gedacht, die bereits vorgegeben sind. Es ist kein routinemässiger zweiter Durchlauf nach einem erfolgreichen `generateIliConstraintCases`.

## Ergebnisfelder richtig interpretieren

### `generationVerified`

`generationVerified=true` bedeutet:

> Alle **tatsächlich erzeugten** Proof-Fälle haben im echten ilivalidator das erwartete Ergebnis geliefert.

### `coverageComplete`

`coverageComplete=true` bedeutet zusätzlich:

> Alle vom Planner gewünschten semantischen Proof-Ziele konnten mit dem endlichen Solver und der Fixture-Infrastruktur materialisiert werden.

Deshalb ist folgende Kombination möglich und sinnvoll:

```json
{
  "generationVerified": true,
  "coverageComplete": false,
  "coverageUnsolved": [
    { "reasonCode": "...", "reason": "..." }
  ]
}
```

Die erzeugten Fälle sind dann verifiziert, aber ein zusätzlicher gewünschter Randfall konnte nicht sicher erzeugt werden. Ein Agent muss diese Grenze berichten und darf sie nicht als bewiesen darstellen.

### `proofVerified`

Typisierte Authoring-Tools liefern `proofVerified=true`, wenn der neu erzeugte Constraint seinen internen semantischen Roundtrip bestanden hat und die erzeugten Proof-Fälle vom Validator bestätigt wurden.

Der Proof bezieht sich auf **diesen Constraint**. Wenn das Tool damit ein bestehendes Modell verändert hat, wird das vollständige Vorher-/Nachher-Modell danach einmal mit `reviewIliChange` geprüft.

## Gemeinsame technische Pipeline

Vereinfacht:

```text
vollständiges INTERLIS-Modell
        |
        v
      ili2c
        |
        v
kompilierter Constraint + Kontext
        |
        v
Constraint-Level-IR / Expression-IR / Object-Set-IR
        |
        v
Coverage Planner + endlicher Solver
        |
        v
modellgebundener Objektgraph
        |
        v
      XTF-Fixture
        |
        v
   ilivalidator
```

Für einen bestehenden Constraint wird das Modell auf dem erfolgreichen öffentlichen Pfad **einmal** kompiliert. Planner, Binder, Fixture-Erzeugung und Validatoradapter verwenden anschliessend denselben kompilierten Kontext.

Typisiertes Authoring kompiliert auf dem erfolgreichen Pfad **zweimal**:

```text
Before-Compile
-> source-preserving Einfügung
-> After-Compile
-> semantischer Roundtrip
-> Proof mit wiederverwendetem After-Kontext
```

Der Proof löst keine zusätzliche ili2c-Kompilierung des unveränderten After-Modells aus.

# MANDATORY

## Unterstützte Semantik

Die Constraint-Expression-IR unterstützt insbesondere:

- skalare Literale: NUMERIC, BOOLEAN, ENUM, TEXT und MTEXT,
- direkte Attribute,
- Attribut- und Navigationspfade,
- `DEFINED`,
- `NOT`,
- `AND`, `OR`, `IMPLIES`,
- `==`, `!=`, `<`, `<=`, `>`, `>=`,
- numerische Arithmetik,
- bekannte Standardfunktionen,
- SUM-/Collection-Szenarien im unterstützten Pfad-/Kollektionsumfang.

Die semantische Repräsentation ist soweit möglich unabhängig von der INTERLIS-Schreibweise. Beispielsweise können ein Funktionsaufruf aus INTERLIS 2.3 und der entsprechende native arithmetische Operator aus INTERLIS 2.4 auf dieselbe semantische Operation abgebildet werden.

## Pfade und Objektgraphen

Der Binder kann unter anderem modellieren:

- direkte Attribute,
- Association-Rollen,
- `REFERENCE TO`,
- STRUCTURE/COMPOSITION-Navigation,
- mehrere skalare Navigationsschritte,
- gemeinsame Pfadpräfixe,
- Collections mit derzeit höchstens einem mehrwertigen Navigationsschritt pro Pfad.

Beispiel für gemeinsame Präfixe:

```text
Eigentuemer->Info->Land->Code
Eigentuemer->Info->Land->Rate
```

Der Synthesizer erzeugt dafür nicht zwei unabhängige Objektketten, sondern teilt den gemeinsamen `Eigentuemer->Info->Land`-Pfad.

## Coverage

Für einen Constraint

```ili
MANDATORY CONSTRAINT value >= 10 AND value <= 20;
```

kann der Planner – sofern die Modelldomain es erlaubt – insbesondere folgende Werte erzeugen:

```text
9   -> ungültig
10  -> gültig
20  -> gültig
21  -> ungültig
```

Logische Operatoren erhalten zusätzliche direkte Branch-Probes. Bei

```ili
MANDATORY CONSTRAINT left == 1 OR right == 1;
```

sind beispielsweise diese Fälle relevant:

```text
left=true,  right=false -> gültig
left=false, right=true  -> gültig
left=false, right=false -> ungültig
```

Die logische Coverage ist bewusst endlich und für direkte Operanden MC/DC-ähnlich; sie ist kein vollständiger mathematischer Beweis für beliebig verschachtelte, abhängige Ausdrücke.

## Typisiertes Authoring

`authorIliMandatoryConstraint` erhält eine flache Node-Liste. Kind-spezifische Kinder werden über IDs referenziert.

Fachliche Regel:

> `value` muss zwischen 10 und 20 liegen.

Beispiel-Payload:

```json
{
  "modelText": "<vollständiger Modelltext ohne neuen Constraint>",
  "context": "Demo.Data.Item",
  "constraintName": "ValueRange",
  "rootNodeId": "root",
  "nodes": [
    { "id": "value", "kind": "ATTRIBUTE", "name": "value" },
    { "id": "low", "kind": "NUMERIC", "value": 10 },
    { "id": "ge", "kind": "COMPARE", "operator": ">=", "children": ["value", "low"] },
    { "id": "high", "kind": "NUMERIC", "value": 20 },
    { "id": "le", "kind": "COMPARE", "operator": "<=", "children": ["value", "high"] },
    { "id": "root", "kind": "AND", "children": ["ge", "le"] }
  ]
}
```

Bei Erfolg enthält das Resultat unter anderem:

- `generated=true`
- `proofVerified=true`
- `constraintExpression`
- `typedCanonicalExpression`
- `updatedModelText`
- `sourceEdit`
- `typedReferences`
- `proof`

Für Standardfunktionen sollte die stabile `semanticId` aus `listConstraintFunctions` verwendet werden. Ein Agent soll nicht versionsabhängige Funktionssyntax raten.

# UNIQUE

## Globaler UNIQUE

Für unterstützte Pfade kann der Planner unter anderem prüfen:

- ein einzelnes teilnehmendes Objekt als Witness,
- zwei Objekte mit gleichem Schlüssel im selben Basket als Counterexample,
- denselben Schlüssel über zwei Baskets,
- zusammengesetzte Schlüssel,
- optionale/undefinierte Schlüsselkomponenten,
- `WHERE`-ausgeschlossene Objekte.

Beispiel:

```ili
UNIQUE code, version;
```

Wichtig: Ein normaler globaler Schlüssel wird **ohne** Klammern um die Attributliste geschrieben. `(BASKET)` ist ein eigener Modifier.

## `(BASKET)`

```ili
UNIQUE (BASKET) code;
```

Hier wird Eindeutigkeit pro Basket beurteilt. Derselbe Schlüssel darf daher in zwei unterschiedlichen Baskets vorkommen, aber nicht zweimal im selben Basket.

Die Testinfrastruktur kann mehrere Baskets desselben Topics erzeugen und setzt bei Cross-Basket-Referenzen die nötige `BID`-Information.

## `WHERE`

Der Planner versucht sowohl teilnehmende als auch ausgeschlossene Fälle zu erzeugen.

Beispiel:

```ili
UNIQUE WHERE leftValue > rightValue : code;
```

Ein Objekt, dessen WHERE-Bedingung falsch ist, darf den UNIQUE-Schlüssel nicht so beeinflussen, als würde es am Schlüsselraum teilnehmen.

## `LOCAL`

Der automatisch beweisbare Umfang unterstützt direkte STRUCTURE-/COMPOSITION-Präfixe mit direkten skalaren Member-Schlüsseln.

Geprüft werden können beispielsweise:

- ein Member als Witness,
- doppelter Member-Schlüssel innerhalb desselben Parents als Counterexample,
- derselbe Member-Schlüssel in zwei unterschiedlichen Parents als Witness.

Navigierte LOCAL-Schlüssel, die nicht sicher synthetisiert werden können, werden als ungelöste Coverage ausgewiesen.

## Authoring

Es gibt derzeit kein gleichwertiges typisiertes High-Level-Authoring für UNIQUE. Für einen **einfachen globalen Attributschlüssel** kann `createUniqueConstraint` als Snippet-Hilfe verwendet werden:

```json
{
  "attrs": ["code", "version"]
}
```

Erzeugt wird sinngemäss:

```ili
UNIQUE code, version;
```

Danach wird der integrierte Constraint mit `generateIliConstraintCases` bewiesen und die Modelländerung mit `reviewIliChange` abgeschlossen.

Komplexe `WHERE`, `(BASKET)`- oder `LOCAL`-Syntax darf nicht aus dem einfachen Snippet-Schema erfunden werden.

# EXISTENCE

## Skalare Werte

Für NUMERIC, BOOLEAN, ENUM, TEXT und MTEXT versucht der Planner insbesondere:

- definierter Source-Wert ohne passendes Target → Counterexample,
- gleicher Source-/Target-Wert → Witness,
- anderer Target-Wert → Counterexample,
- optionaler undefinierter Source-Wert → Witness.

## Typisiertes Authoring

Beispielmodell mit Source und zwei möglichen Targets:

```ili
CLASS TargetA =
  code : 0..10;
END TargetA;

CLASS TargetB =
  code : 0..10;
END TargetB;

CLASS Source =
  code : MANDATORY 0..10;
END Source;
```

Payload:

```json
{
  "modelText": "<vollständiger Modelltext>",
  "context": "Demo.Data.Source",
  "constraintName": "CodeExists",
  "restrictedPath": "code",
  "requiredIn": [
    { "viewableFqn": "Demo.Data.TargetA", "attributePath": "code" },
    { "viewableFqn": "Demo.Data.TargetB", "attributePath": "code" }
  ]
}
```

Das Tool verlangt `viewableFqn` **und** `attributePath`, weil echte `REQUIRED IN`-Semantik nicht mit einer blossen Klassenliste beschrieben werden kann.

Freie EXISTENCE-Snippet-Helper sind nicht Teil der MCP-Oberfläche; für neue skalare Regeln dient das typisierte Authoring.

## Direkte STRUCTURE/COMPOSITION-Werte

Für bestehende Constraints kann `generateIliConstraintCases` auch einen bewusst konservativen Strukturumfang beweisen. Unterstützt werden direkte Struktur-/Composition-Werte, wenn unter anderem:

- Source und Target denselben Component-Type verwenden,
- die transferierten Member-Namen kompatibel sind,
- die Kardinalität klein und sicher materialisierbar ist,
- vergleichbare Member als sichere skalare Werte erzeugt werden können.

Geprüft werden unter anderem fehlendes Target, member-wise Gleichheit, eine gezielte Member-Differenz und – wenn zulässig – ein undefinierter Source-Wert.

## Explizite Safety-Grenzen

Bestimmte EXISTENCE-Formen werden **nicht** durch eine vermeintlich ähnliche skalare Semantik ersetzt:

- `REFERENCE TO`: `EXISTENCE_REFERENCE_VALUE_PROOF_UNSAFE`
- COORD: `EXISTENCE_COORD_FIXTURE_NOT_VALUE_AWARE`
- POLYLINE/SURFACE/AREA: `EXISTENCE_COMPLEX_GEOMETRY_FIXTURE_UNAVAILABLE`
- nicht unterstützte navigierte Spezialpfade: eigener Safety-/Coverage-Grund

Solche Constraints können mit `reviewIliConstraint` untersucht und bei Bedarf über explizit bereitgestellte Testdaten geprüft werden.

# PLAUSIBILITY

PLAUSIBILITY ist **keine** pro Objekt ausgewertete Mandatory-Regel. Die tatsächliche Validator-Semantik arbeitet mit einer Population.

## Validator-Semantik

Für jedes relevante Objekt wird die Bedingung ausgewertet:

- `TRUE` zählt als erfolgreich,
- `FALSE` zählt zum Total, aber nicht als erfolgreich,
- `skipEvaluation` – beispielsweise wegen eines undefinierten Eingabewerts – zählt in der aktuellen iox-ili-Semantik ebenfalls als erfolgreich.

Anschliessend wird berechnet:

```text
successful / total * 100
```

und mit dem Constraint-Schwellwert verglichen.

## Beispiel: mindestens 80 Prozent

Angenommen, die Bedingung lautet `value >= 5` und der Schwellwert ist 80 Prozent.

Mögliche Proof-Populationen:

```text
15 erfolgreich / 19 total = 78.947... % -> ungültig
 4 erfolgreich /  5 total = 80 %         -> gültig
13 erfolgreich / 16 total = 81.25 %      -> gültig
```

Der Planner arbeitet mit exakten Quotienten für die Gültigkeitsentscheidung; gerundete Anzeigeprozente werden nicht zur Entscheidung verwendet.

Populationen werden aktuell auf höchstens 20 Kontextobjekte pro Proof-Fall begrenzt. Ein exakter Schwellwertfall wird nur erzeugt, wenn er innerhalb dieses Bounds darstellbar ist.

Wenn eine undefinierte Bedingung sicher erzeugt werden kann, kann zusätzlich ein `UNDEFINED_COUNTS_AS_SUCCESS`-Fall die `skipEvaluation`-Semantik verifizieren.

## Populationssicherheit

Ein wiederverwendetes TRUE-/FALSE-Mitglied muss genau **ein** Objekt des Constraint-Kontexts zur Population beitragen. Würde ein synthetisierter Hilfsgraph zusätzliche Kontextobjekte erzeugen und damit unbemerkt den Nenner verändern, wird der Proof nicht behauptet.

## Typisiertes Authoring

```json
{
  "modelText": "<vollständiger Modelltext>",
  "context": "Demo.Data.Item",
  "constraintName": "UsuallyHigh",
  "direction": "AT_LEAST",
  "percentage": 80,
  "rootNodeId": "root",
  "nodes": [
    { "id": "value", "kind": "ATTRIBUTE", "name": "value" },
    { "id": "threshold", "kind": "NUMERIC", "value": 5 },
    { "id": "root", "kind": "COMPARE", "operator": ">=", "children": ["value", "threshold"] }
  ]
}
```

`direction` akzeptiert `AT_LEAST`/`>=` oder `AT_MOST`/`<=`; `percentage` liegt zwischen 0 und 100.

Der erzeugte INTERLIS-Block hat sinngemäss die Form:

```ili
CONSTRAINTS OF Demo.Data.Item =
  !!@ name = "UsuallyHigh"
  CONSTRAINT
    >= 80% (value >= 5);
END;
```

# SET

## Object-Set-Semantik

`OBJECTS OF` bezeichnet in INTERLIS den semantischen Parametertyp für Objektmengen. Der konkrete Objektmengen-Ausdruck, den der unterstützte SET-Proof verwendet, steht im Modell als `ALL` und wird von ili2c als eigener `Objects`-AST-Knoten repräsentiert.

Die interne Object-Set-IR bewahrt zusätzlich Base-/`RESTRICTION`-Metadaten auf. Automatisch bewiesen wird derzeit bewusst nur der sichere `plain ALL`-Umfang.

## `objectCount(ALL)`

Beispiel:

```ili
SET CONSTRAINT
  INTERLIS.objectCount(ALL) >= 2;
```

Der Planner erzeugt Objektanzahlen nahe der Schwelle und lässt ilivalidator entscheiden.

Sinngemäss:

```text
1 Objekt  -> ungültig
2 Objekte -> gültig
3 Objekte -> gültig
```

Die tatsächlich erzeugbaren Fälle hängen vom Modell und der Fixture-Sicherheit ab.

## `WHERE`

Bei einem SET-Constraint mit Precondition wählt der Validator zuerst die Kontextobjekte aus, für die `WHERE` wahr ist. `ALL` bezeichnet danach diese ausgewählte Menge.

Beispiel:

```ili
SET CONSTRAINT WHERE value >= 5:
  INTERLIS.objectCount(ALL) >= 2;
```

Der Planner versucht daher bewusst sowohl ein eingeschlossenes als auch ein ausgeschlossenes Objekt zu erzeugen. Count-Fälle enthalten zusätzliche ausgeschlossene Objekte, damit der Proof zeigt, dass diese **nicht** zu `objectCount(ALL)` beitragen.

## Global versus `(BASKET)`

```ili
SET CONSTRAINT (BASKET)
  INTERLIS.objectCount(ALL) >= 2;
```

Ein nützlicher Zweibasket-Proof ist:

```text
Basket A: 1 ausgewähltes Objekt
Basket B: 1 ausgewähltes Objekt
```

Dann gilt:

```text
global:    2 >= 2 -> gültig
(BASKET):  1 >= 2 -> pro Basket ungültig
```

Damit lässt sich die Scope-Semantik mit echten Multi-Basket-XTF-Fixtures unterscheiden.

## Typisiertes Authoring

```json
{
  "modelText": "<vollständiger Modelltext>",
  "context": "Demo.Data.Item",
  "constraintName": "AtLeastTwoHigh",
  "operator": ">=",
  "threshold": 2,
  "perBasket": false,
  "where": {
    "attribute": "value",
    "operator": ">=",
    "valueKind": "NUMERIC",
    "value": 5
  }
}
```

Das Resultat enthält bei Erfolg `proofVerified=true`, `updatedModelText` und den eingebetteten SET-Proof.

## Bewusste Grenzen

Der automatische SET-Proof behauptet derzeit keine Semantik für:

- `ALL(base)` bzw. `ALL(... RESTRICTION ...)`,
- komplexe WHERE-Objektgraphen mit Hilfsobjekten oder Links,
- bestimmte Nullobjekt-Fixtures ohne WHERE,
- geometry-aware Funktionen wie `INTERLIS.areAreas` / `areAreas2`,
- unbekannte SET-spezifische AST-/Funktionsformen.

Solche Fälle werden als `coverageUnsolved` bzw. mit einem expliziten Reason-Code sichtbar.

# Multi-Basket-Fixtures

Die Constraint-Fixture-Infrastruktur unterstützt mehrere Baskets desselben Topics.

- `TestObject.basketId` kann den Basket eines Objekts explizit bestimmen.
- `TestLink.basketId` kann dies für transferierte Association-Objekte tun.
- Ohne explizite ID wird weiterhin ein deterministischer impliziter Basket pro Topic verwendet.
- Ein Basket-Identifier darf innerhalb eines Testfalls nicht für verschiedene Topics wiederverwendet werden.
- Cross-Basket-Referenzen erhalten `BID`, sofern das Modell diese Referenzsemantik zulässt.

Diese Infrastruktur ist insbesondere für `UNIQUE (BASKET)` und `SET CONSTRAINT (BASKET)` relevant.

# Der endliche Solver und seine Grenzen

Der Solver ist bewusst deterministisch und endlich. Er leitet Kandidaten unter anderem aus folgenden Quellen ab:

- Modell-Domains,
- Literalen im Ausdruck,
- numerischen Grenzen und Präzisionsschritten,
- BOOLEAN-/ENUM-Werten,
- kleinen Collection-Kandidaten,
- ausgewählten arithmetischen Rückwärtsableitungen.

`NO_SOLUTION_FOUND` bedeutet deshalb:

> In der abgeleiteten endlichen Kandidatenmenge wurde keine Lösung gefunden.

Es bedeutet **nicht**, dass die Bedingung mathematisch unlösbar bewiesen wurde. Es gibt keine allgemeine SMT-/Z3-Abhängigkeit und keinen vollständigen symbolischen Solver für beliebige nichtlineare Ausdrücke.

# Differentialtests gegen den Validator

Zusätzlich zu den Planner-/Solver-Tests existieren Differentialtests, die explizite Assignments sowohl mit der internen Expression-Engine als auch mit dem echten Validator auswerten:

```text
explizites Assignment
   |                     |
   v                     v
Expression-Engine    XTF-Synthese
   |                     |
   |                     v
   |                ilivalidator
   |                     |
   +------ Vergleich ----+
```

Damit kann ein Solver- oder Coverage-Problem nicht verdecken, dass sich die interne Semantik vom Validator unterscheidet.

# Empfohlener Agentenablauf

Für einen neuen typisiert unterstützten Constraint:

```text
fachliche Regel
-> passendes authorIli...Constraint
-> proofVerified=true prüfen
-> coverageUnsolved/Safety-Codes berichten
-> updatedModelText übernehmen
-> bei bestehendem Modell genau ein reviewIliChange(Vorher, Nachher)
```

Für einen bestehenden Constraint:

```text
bei Bedarf reviewIliConstraint
-> generateIliConstraintCases
-> generationVerified prüfen
-> coverageComplete / coverageUnsolved berichten
```

Für einen einfachen neuen UNIQUE-Schlüssel:

```text
createUniqueConstraint oder gezielte Quelltextänderung
-> Constraint in Modell integrieren
-> generateIliConstraintCases
-> generationVerified prüfen
-> reviewIliChange(Vorher, Nachher)
```

Diese Abläufe vermeiden sowohl unbewiesene Semantik als auch redundante doppelte Validator-Durchläufe.
