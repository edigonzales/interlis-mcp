# Constraints: Semantik, Authoring und Proofs

`interlis-mcp` behandelt INTERLIS-Constraints nicht als Textbausteine, sondern – soweit unterstützt – als **kompilierte Semantik**. Der Server liest den ili2c-AST, übersetzt relevante Konstrukte in eine typisierte interne Repräsentation, plant modellbewusste Testfälle, erzeugt XTF und lässt das Resultat vom echten ilivalidator prüfen.

Der interne Solver und Evaluator helfen beim Erzeugen geeigneter Fälle. **Der ilivalidator bleibt die abschliessende technische Instanz.** Ein intern plausibles Resultat wird nicht als Proof ausgegeben, bevor die erzeugten Transferdaten das erwartete Verhalten im Validator gezeigt haben.

## Überblick

| Constraint-Art | Bestehenden Constraint automatisch beweisen | Typisiertes High-Level-Authoring |
| --- | --- | --- |
| MANDATORY | ja | `authorIliMandatoryConstraint` |
| UNIQUE | ja | `authorIliUniqueConstraint` für GLOBAL, BASKET und LOCAL |
| EXISTENCE | ja, mit dokumentierten Validator-/Navigationsgrenzen | `authorIliExistenceConstraint` |
| PLAUSIBILITY | ja, mit echten Populationen | `authorIliPlausibilityConstraint` |
| SET | ja für typisierte `OBJECT_COUNT`- und boolesche Formen | `authorIliSetConstraint` |

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

Der Proof bezieht sich auf **diesen Constraint**. Die Authoring-Tools liefern zusätzlich den semantischen Vorher-/Nachher-Diff und `afterReview` aus denselben Compilations; für den unveränderten Resultattext ist kein zusätzlicher Review-Aufruf nötig.

Die gemeinsamen Authoring-Resultate verwenden ein geschlossenes Status-Enum mit `GENERATED`, `APPLIED`, `BREAKING_CHANGE_REQUIRES_CONFIRMATION`, `NEEDS_INPUT`, `INVALID_SPEC`, `BEFORE_MODEL_INVALID`, `CANDIDATE_MODEL_INVALID`, `AST_ROUND_TRIP_FAILED`, `PROOF_INCOMPLETE`, `PROOF_FAILED`, `EXTERNAL_FUNCTION_SEMANTICS_REQUIRED` und `UNEXPECTED_SEMANTIC_CHANGE`. `updatedModelText` erscheint nur bei `GENERATED` beziehungsweise `APPLIED`; jeder kompilierbare, aber nicht freigegebene Stand erscheint ausschliesslich als `candidateModelText`.

## Eingabevertrag und frühe Diagnosen

Alle Constraint-Authoring-Tools prüfen die rekursiven Ausdrucksknoten vor dem Rendern.
Die gleichen Regeln gelten für Constraints in Modell- und Änderungswerkzeugen.
Die nativen Schemas zeigen pro `kind` die erlaubten Felder und Kinderzahlen; die Java-DTOs,
Toolnamen und Top-Level-Parameter bleiben bestehen.

- `spec.name` beziehungsweise `constraintName` ist ein technischer Name gemäß
  `[A-Za-z][A-Za-z0-9_]*`. Für eine fachliche Nummer `42` kann der Server `Regel42`
  vorschlagen; er benennt den Constraint nicht automatisch um.
- `ENUM.value` akzeptiert einen String mit oder ohne genau ein führendes `#`, beispielsweise
  `Drainage`, `#Drainage` oder `Gruppe.Drainage`. Äußere Leerzeichen werden entfernt.
  Nullwerte, leere Werte, wiederholte `#` und Assoziationspfeile sind unzulässig.
  Renderer, Entscheidungstabelle und AST-Vergleich verwenden dieselbe kanonische Schreibweise.
  Request-Objekte sowie TEXT-/MTEXT-Werte bleiben unverändert.
- STANDARD-Funktionen verwenden eine `semanticId` aus `listConstraintFunctions` als `name`:
  `COLLECTION_SUM` und `NUMERIC_ADD`, nicht `Math.sum` und `Math.add`.
  Ein zur INTERLIS-Version passender qualifizierter Name erhält einen konkreten Hinweis aus
  dem Funktionsregister; die Kennung wird nicht automatisch ersetzt und die Herkunft nicht
  auf MODEL geändert. Externe Funktionen benötigen weiterhin ihre echte Signatur und bleiben
  ohne ausführbare Semantik eine Proof-Grenze.
- Funktionsargumente stehen geordnet in `children`. `objects` ist kein FUNCTION-Feld.
  Blätter haben keine Kinder, DEFINED/NOT eines, COMPARE/IMPLIES zwei und AND/OR mindestens eines.
- `OBJECT_COUNT` als **Ausdruck** liefert eine Zahl und verwendet ausschließlich `objects`.
  Ein Vergleich ist ein umgebender COMPARE-Knoten:

```json
{
  "kind": "COMPARE",
  "operator": "==",
  "children": [
    { "kind": "OBJECT_COUNT", "objects": { "kind": "ALL" } },
    { "kind": "NUMERIC", "value": 1 }
  ]
}
```

Die separate **SET-Condition** OBJECT_COUNT verwendet dagegen `objects`, `operator` und
`threshold`. Ein `value` an ihrer Stelle ist kein Vergleichsgrenzwert. In Entscheidungstabellen
bleibt OBJECT_COUNT ein `aggregate` mit numerischem `value`.
`defined=true/false` bezeichnet dort ausschließlich SUM-Präsenz und erfordert `aggregate=SUM`
ohne `operator`, `value` oder `addAttribute`; direkte DEFINED-Ausdrücke gehören ins typisierte Authoring.

Bisher ignorierte, nichtleere Felder wie `FUNCTION.objects` oder `OBJECT_COUNT.operator/value`
werden nun abgewiesen. Das ist eine bewusste Verschärfung für fehlerhafte Payloads, damit daraus
kein irreführender Compilerfehler entsteht. Reguläre gültige Payloads behalten ihre Bedeutung;
Enum-Werte mit einem führenden `#` werden im typisierten Authoring zusätzlich akzeptiert.

`IliAuthoringResult` enthält additiv `specDiagnostics`. Der erste Vertragsfehler wird deterministisch
mit einem RFC-6901-JSON-Pointer gemeldet, auch innerhalb eingebetteter Modell- oder Batch-Constraints:

```json
{
  "status": "INVALID_SPEC",
  "reasonCode": "INVALID_SPEC",
  "complete": false,
  "generated": false,
  "proofVerified": false,
  "specDiagnostics": [{
    "code": "INVALID_FIELD",
    "path": "/spec/condition/objects",
    "message": "objects is not allowed for FUNCTION.",
    "hint": "FUNCTION arguments belong in the ordered children list."
  }]
}
```

Codes: `MISSING_FIELD`, `INVALID_FIELD`, `INVALID_LITERAL`, `INVALID_IDENTIFIER`,
`INVALID_ARITY` und `UNKNOWN_STANDARD_FUNCTION`. `reason` bleibt als lesbare Kurzmeldung erhalten;
`specDiagnostics` ist bei Ergebnissen ohne solche Fehler leer. Direktes Constraint-Authoring
kompiliert bei diesen Formfehlern kein Modell und liefert keinen Kandidaten. Der Batch-Workflow
kann bereits das unveränderte Eingabemodell kompiliert haben. Fehler, die der Client oder die native
Schema-Prüfung vor dem Handler erkennt, bleiben Schema-/Transportfehler.

### Benchmark-Kompatibilität

Diese Erweiterung verändert native Tooldeklarationen und Schemas. Die eingefrorene Benchmark-v2,
ihr aktiver Zeiger, Scheduled Task und historische Ergebnisse werden nicht angepasst. Vor einem
weiteren gewerteten Lauf gegen diesen Server ist eine neue geprüfte Benchmark-Revision erforderlich.
Die bestehende Katalogprüfung muss die Abweichung weiterhin erkennen und den Lauf sperren.
Technische Regressionstests ersetzen keine native Abnahme; ein besserer End-to-End-Score ist damit
noch nicht nachgewiesen.

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

`authorIliMandatoryConstraint` erhält eine rekursive `MandatoryConstraintSpec`. Jeder Ausdrucksknoten trägt seinen `kind`; Kinder sind direkt als weitere `ExpressionSpec` eingebettet. Freie Syntax und Node-ID-Verweise werden nicht akzeptiert.

Fachliche Regel:

> `value` muss zwischen 10 und 20 liegen.

Beispiel-Payload:

```json
{
  "modelText": "<vollständiger Modelltext ohne neuen Constraint>",
  "contextFqn": "Demo.Data.Item",
  "spec": {
    "kind": "MANDATORY",
    "name": "ValueRange",
    "condition": {
      "kind": "AND",
      "children": [
        {
          "kind": "COMPARE",
          "operator": ">=",
          "children": [
            { "kind": "ATTRIBUTE", "name": "value" },
            { "kind": "NUMERIC", "value": 10 }
          ]
        },
        {
          "kind": "COMPARE",
          "operator": "<=",
          "children": [
            { "kind": "ATTRIBUTE", "name": "value" },
            { "kind": "NUMERIC", "value": 20 }
          ]
        }
      ]
    }
  }
}
```

Bei Erfolg enthält das Resultat unter anderem:

- `generated=true`
- `proofVerified=true`
- `updatedModelText`
- `sourceEdits`
- `semanticDiff` und `afterReview`
- `constraintProofs` mit typisierten Coverage-Gaps, Fällen und Validatorresultaten

Für Standardfunktionen sollte die stabile `semanticId` aus `listConstraintFunctions` verwendet werden. Ein Agent soll nicht versionsabhängige Funktionssyntax raten.

## Dreiwertige Coverage und ausgeschlossene Ziele

Die gemeinsame Coverage-Planung für Mandatory, Decision Tables und boolesche SET-Bedingungen unterscheidet `TRUE`, `FALSE` und `UNDEFINED`. Zusätzlich zu strikten Belegungsmustern wird die tatsächliche Erreichbarkeit von AND-/OR-Zweigen geprüft: Vor einem späteren AND-Operanden müssen alle Vorgänger TRUE sein, vor einem späteren OR-Operanden FALSE. Nachfolgende Operanden sind nach dem Abbruch irrelevant. Zusammengesetzte Ziele verwenden interne Zustandsbedingungen; sie werden nicht als künstliche INTERLIS-Ausdrücke ausgeführt. In der Diagnostik kennzeichnet `STATE CONDITIONS:` solche Prüfbedingungen. Diese Ziele verändern den erzeugten Constraint nicht.

`coverageGoalCount` zählt anwendbare Ziele, `coverageSolvedCount` erfüllte Ziele. Mehrere Ziele können dieselbe Fixture verwenden; `generatedCases[*].coveredGoals` bewahrt ihre Zuordnung. Die Zahl der Fixtures ist deshalb unabhängig von der Zahl erfüllter Ziele. Historische Coverage-Brüche, die deduplizierte Fixtures zählten, sind nicht direkt mit diesen Zählerständen vergleichbar.

`coverageExcludedCount` und `coverageExcludedGoals` führen nachweislich unerreichbare strukturelle Ziele separat auf. Jeder Ausschluss enthält `goal`, `reason`, `expression`, `reasonCode=PROVEN_UNREACHABLE` und `justification`. Die skalare Prüfung berücksichtigt vollständige Boolean-/Enum-Domänen, numerische Vergleichspartitionen und Optionalität entlang einwertiger Pfade. Eine ergänzende Definiertheitsabstraktion kann Widersprüche unter SUM und strikter ADD/SUB/MUL/DIV-Undefiniert-Fortpflanzung beweisen; sie berechnet keine numerischen Funktionswerte. Unbekannte Funktionssemantik und überschrittene Analysegrenzen liefern keinen Ausschluss.

Ein erfolgloser endlicher Solver-Lauf ist kein Unerreichbarkeitsbeweis. Suchlimits, unbekannte Semantik und Fixture-Probleme bleiben Lücken. Globale Witness-/Counterexample-Ziele bleiben auch dann Pflicht, wenn sie unerreichbar sind. `coverageComplete=true` setzt alle anwendbaren Ziele voraus; `proofVerified=true` verlangt zusätzlich erzeugte und erfolgreich geprüfte Validatorfälle. P02 prüft diesen Ablauf mit unverändertem Constraint einschliesslich des Falls ohne pH-Wert.

### Geordnete Auswertung und Validator-Grenze

Gemäss INTERLIS-Referenzhandbuch, Abschnitt 2.13, wird von links nach rechts ausgewertet:
AND setzt nur nach TRUE fort, OR nur nach FALSE. Ein undefinierter Operand beendet die
Auswertung. Insbesondere ergibt `UNDEFINED AND FALSE` keinen FALSE-Wert; entsprechend
ist `UNDEFINED OR TRUE` kein TRUE-Wert. NOT propagiert die nicht berechenbare Auswertung.
Die frühere Zuordnung des ersten Falls als Validatorfehler war falsch: Der interne Evaluator
hatte unzulässig weitergerechnet. Die Regressionen verlangen nun Übereinstimmung sowohl
beim Auswertungszustand als auch bei der Mandatory-Gültigkeit unter INTERLIS 2.3 und 2.4.

Intern bezeichnet `Undefined` einen fehlenden Wert und `NotComputable` eine fachlich
abgebrochene Auswertung. DEFINED liefert für einen fehlenden Wert FALSE und propagiert
eine bereits abgebrochene Auswertung. Die Coverage fasst beide Fälle als UNDEFINED zusammen;
technische Fehler und nicht unterstützte Funktionen zählen zu keinem dieser Zustände.
Ein Mandatory Constraint wird nur durch ein ausdrückliches FALSE verletzt.

Implikationen werden verzögert wie `NOT(A) OR B` ausgewertet und von beiden Renderern
in dieser Schreibweise erzeugt. `IMPLIES` ist kein INTERLIS-Schlüsselwort. Der strukturelle
Roundtrip vergleicht geordnete Operatorbäume, Pfade, Funktionsidentitäten und Literalwerte;
er entfernt keine Klammern oder Leerzeichen aus Textliteralen.

Ein separater Fehler bleibt im gepinnten iox-ili 1.24.4: `NOT(A => B)` wird bei
`A=false, B=false` fälschlich akzeptiert. Native Implikationsknoten bleiben in der aus dem
Compiler-AST übersetzten IR erhalten und sperren den automatischen Proof vor jeder
verlustbehafteten Normalisierung mit `VALIDATOR_NATIVE_IMPLICATION_UNSUPPORTED`.
Dies gilt auch in WHERE-Bedingungen; andere Constraints desselben Modells lösen die
Sperre nicht aus. Explizite Validator-Tests zeigen weiterhin das reale Ergebnis. Neue
Authoring-Kandidaten verwenden NOT/OR. Ohne erfolgreichen Proof wird ausschliesslich
`candidateModelText`, niemals `updatedModelText` freigegeben. Ein Dependency-Upgrade
ist nicht Teil dieser Absicherung.

Referenzen: [INTERLIS 2.4, Abschnitt 2.13](https://geostandards-ch.github.io/doc_refhb24/),
[INTERLIS 2.3, Abschnitt 2.13](https://interlis.ch/download/interlis2/ili2-refman_2006-04-13_d.pdf).

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

`authorIliUniqueConstraint` erstellt UNIQUE vollständig typisiert und source-preserving. Unterstützt werden GLOBAL, BASKET und LOCAL, mehrere Schlüsselpfade, ein optionaler typisierter WHERE-Ausdruck und bei LOCAL ein explizites Präfix:

```json
{
  "modelText": "<vollständiger Modelltext>",
  "contextFqn": "Demo.Data.Item",
  "spec": {
    "kind": "UNIQUE",
    "name": "UniqueCodeVersion",
    "scope": "GLOBAL",
    "keyPaths": ["code", "version"]
  }
}
```

Erzeugt wird sinngemäss:

```ili
UNIQUE code, version;
```

Der Aufruf kompiliert Before und After genau einmal und liefert Source-Edit, AST-Roundtrip, Validator-Proof, semantischen Diff und `afterReview`. Bei Erfolg ist weder eine zweite Proof-Runde noch ein zusätzliches `reviewIliChange` nötig.

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
  "contextFqn": "Demo.Data.Source",
  "spec": {
    "kind": "EXISTENCE",
    "name": "CodeExists",
    "restrictedPath": "code",
    "requiredIn": [
      { "viewableFqn": "Demo.Data.TargetA", "attributePath": "code" },
      { "viewableFqn": "Demo.Data.TargetB", "attributePath": "code" }
    ]
  }
}
```

Das Tool verlangt `viewableFqn` **und** `attributePath`, weil echte `REQUIRED IN`-Semantik nicht mit einer blossen Klassenliste beschrieben werden kann.

Freie EXISTENCE-Snippet-Helper sind nicht Teil der MCP-Oberfläche; für neue skalare Regeln dient das typisierte Authoring.

## Mandatory- und Decision-Table-Proofs in Strukturkontexten

Ein Constraint auf einer konkreten STRUCTURE wird im ursprünglichen Modell geprüft. Der
Fixture-Resolver bettet die vom Solver belegte Struktur in eine vorhandene identifizierbare
Besitzerklasse ein. Direkte und verschachtelte Composition-Pfade, BAG/LIST sowie geerbte
Besitzerattribute werden unterstützt. Die Struktur erhält keine eigene OID; Referenzen auf
Klassenobjekte bleiben erhalten. Es entstehen keine zusätzlichen Modellklassen und keine
zusätzliche Authoring-Kompilierung.

Routen werden nach Pfadlänge, Besitzer-FQN und Attributpfad geordnet. Höchstens acht Routen
mit bis zu acht Composition-Schritten werden betrachtet. Die erste Route, deren gesamte
Fallmenge gültige Fixtures liefert, wird verwendet. Ein abweichendes Constraint-Ergebnis
verhindert einen Wechsel auf eine andere Route. Pflichtvorkommen sind auf fünf pro Attribut,
Strukturinstanzen auf insgesamt 64 pro Fixture begrenzt. Abstrakte Strukturtypen werden
nicht automatisch durch Untertypen ersetzt; zyklische Einbettungspfade werden abgebrochen.

Die IOM-Objekte werden einmal aufgebaut, rekursiv nach tatsächlichen Kontextinstanzen gezählt
und anschließend serialisiert. Ein Besitzer ohne Zielstruktur oder mit leerer Ziel-Collection
beweist keinen Strukturconstraint (`constraintExercised=false`). Explizite leere Collections
und fehlende Werte werden nicht durch Defaults ersetzt. Fehlende Pflichtwerte und
Kardinalitätsverletzungen bleiben Fixture-Fremdfehler; sie gelten nicht als erfolgreiches
Counterexample.

Zusätzliche Resultatfelder:

- `generatedCases[].ownerClassFqn` und `structurePath`: gewählte Einbettung.
- `verification.cases[].subjectCount`, `constraintExercised`, `fixtureValid`,
  `fixtureErrors` und `fixturePreparationReasonCode`: auch im typisierten Authoring-Resultat.
- Die typisierten Felder `expectedValid` und `actualValid` übernehmen ebenfalls die
  entsprechenden `expectedConstraintValid`-/`actualConstraintValid`-Werte des Rohresultats.

Bekannte Grenzen werden als strukturierte Proof-Resultate gemeldet:
`STRUCTURE_OWNER_NOT_FOUND`, `STRUCTURE_PATH_UNSUPPORTED`,
`STRUCTURE_FIXTURE_BUDGET_EXCEEDED`, `STRUCTURE_MATERIALIZATION_FAILED` beziehungsweise
`FIXTURE_MATERIALIZATION_FAILED`. Ohne verifizierten vollständigen Proof bleibt nur
`candidateModelText` verfügbar; `updatedModelText` wird nicht freigegeben.

Die P01-Regression verwendet das eingecheckte öffentliche Modell und eine feste typisierte
Spezifikation. Sie verlangt vollständige Coverage, reale Validator-Prüfung und zusätzliche,
unabhängig formulierte Grenzfälle für alle 13 Körnungsklassen. Für zwei Ziele des Ton-Zweigs
reichte die erste numerische Kandidatenliste mit 18 Werten nicht aus: Nach erfolgloser Suche
folgt deshalb eine Nachsuche mit den bereits abgeleiteten ungekürzten numerischen Kandidaten.
Beide Suchphasen teilen sich 50.000 Versuche. Enum-, Text- und Collection-Kandidatengrenzen
sowie die unabhängige Unerreichbarkeitsprüfung bleiben unverändert; Sucherschöpfung ist
weiterhin kein Unerreichbarkeitsbeweis.

Die P06-Regression verwendet eine separat festgelegte fachlich korrekte Decision Table mit
expliziten SUM-Präsenzbedingungen. Leere Beziehungen beziehungsweise leere Compositions
bleiben echte leere Mengen: Hauptgewicht 100 ist im geprüften Summenfall gültig, 99 ungültig.
Historische Requests werden dadurch nicht geändert oder stillschweigend ergänzt.
Direkte Identitätsprojektionen in VIEW TOPIC werden durch die gemeinsame View-Proof-Pipeline unterstützt (siehe unten). Die frühere UNDEFINED-Abweichung wurde im Zwischenpaket durch geordnete Auswertung korrigiert; native Validator-Implikationen bleiben eine explizite Proof-Grenze.

## Direkte STRUCTURE/COMPOSITION-Werte

Für bestehende Constraints kann `generateIliConstraintCases` auch einen bewusst konservativen Strukturumfang beweisen. Unterstützt werden direkte Struktur-/Composition-Werte, wenn unter anderem:

- Source und Target denselben Component-Type verwenden,
- die transferierten Member-Namen kompatibel sind,
- die Kardinalität klein und sicher materialisierbar ist,
- vergleichbare Member als sichere skalare Werte erzeugt werden können.

Geprüft werden unter anderem fehlendes Target, member-wise Gleichheit, eine gezielte Member-Differenz und – wenn zulässig – ein undefinierter Source-Wert.

## Typisierte Werte und Safety-Grenzen

Direkte COORD-, POLYLINE-, SURFACE- und AREA-Werte erhalten gleiche Witnesses und verschiedene Counterexamples aus den tatsächlichen Metamodelldomains. UNIQUE unterstützt zusätzlich Referenz-OIDs, Strukturen sowie INTERLIS-2.4-Multigeometrien, soweit die übrigen Modellregeln isolierbare Fixtures erlauben.

Safety-Grenzen bleiben sichtbar und werden nicht als Erfolg umgedeutet:

- Die aktuell eingebundene ilivalidator-Version bricht bei `EXISTENCE`-Gleichheitsvergleichen für `REFERENCE` und Multigeometrien intern ab. Die gültigen OID-/Geometrie-Fixtures werden deshalb mit `REFERENCE_EQUALITY_VALIDATOR_FAILURE` beziehungsweise `GEOMETRY_EQUALITY_VALIDATOR_FAILURE` zurückgehalten.
- AREA-/MULTIAREA-Duplikate können die Topologieregel gegen überlappende Flächen bereits vor UNIQUE verletzen. Dann lautet der Grund `UNIQUE_AREA_DUPLICATE_NOT_MODEL_VALID`.
- Nicht sicher materialisierbare navigierte oder polymorphe Spezialpfade liefern einen eigenen Coverage-Grund.

In allen Fällen erscheint kein `updatedModelText`. Der kompilierbare Stand bleibt als `candidateModelText` verfügbar.

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
  "contextFqn": "Demo.Data.Item",
  "spec": {
    "kind": "PLAUSIBILITY",
    "name": "UsuallyHigh",
    "direction": "AT_LEAST",
    "percentage": 80,
    "condition": {
      "kind": "COMPARE",
      "operator": ">=",
      "children": [
        { "kind": "ATTRIBUTE", "name": "value" },
        { "kind": "NUMERIC", "value": 5 }
      ]
    }
  }
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

Die interne Object-Set-IR bewahrt zusätzlich Base-/`RESTRICTION`- und Polymorphie-Metadaten auf. Öffentlich typisiert sind `ALL` und ein navigierter Objektpfad (`PATH`). Konkrete Klassentypen an Zwischen- und Endpositionen werden bis zum harten Routenbudget einzeln und in stabiler FQN-Reihenfolge bewiesen. Nicht materialisierbare Base-/Restriction-Routen bleiben mit einem präzisen Coverage-Grund zurückgehalten.

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

Diese Populationsaddition gilt für `objectCount(ALL)`. Ein `objectCount(PATH)` wird dagegen für jedes Kontextobjekt über dessen eigenen navigierten Pfad ausgewertet; unabhängige Pfadzahlen verschiedener Wurzelobjekte dürfen deshalb nicht zu einem globalen Count addiert werden.

## Navigierter Objektpfad und boolescher SET-Ausdruck

Ein `PATH`-Objektset materialisiert Rollen, Referenzattribute und konkrete Composition-Zwischenstufen aus dem kompilierten Metamodell. Mehrere mehrwertige Schritte und Klassen ohne skalare Attribute werden unterstützt. Konkrete polymorphe Zwischen- und Endtypen erhalten getrennte Proofs; mehrwertige Schritte zusätzlich gemischte Typen. Gemeinsame Präfixe behalten identische Belegungen. Die Grenzen betragen acht Navigationsschritte, acht konkrete Routenkombinationen und 64 explizite Klassenobjekte plus Beziehungen pro Fixture. Eine Überschreitung ist eine Proof-Lücke, kein Ausschluss.

`BOOLEAN_EXPRESSION` verwendet dieselbe rekursive Expression-IR, Domain-Bindung und Wahr-/Falsch-Coverage wie MANDATORY. Externe Funktionen ohne bekannte ausführbare Semantik führen auch hier zu `EXTERNAL_FUNCTION_SEMANTICS_REQUIRED`.

## Typisiertes Authoring

```json
{
  "modelText": "<vollständiger Modelltext>",
  "contextFqn": "Demo.Data.Item",
  "spec": {
    "kind": "SET",
    "name": "AtLeastTwoHigh",
    "scope": "GLOBAL",
    "where": {
      "kind": "COMPARE",
      "operator": ">=",
      "children": [
        { "kind": "ATTRIBUTE", "name": "value" },
        { "kind": "NUMERIC", "value": 5 }
      ]
    },
    "condition": {
      "kind": "OBJECT_COUNT",
      "objects": { "kind": "ALL" },
      "operator": ">=",
      "threshold": 2
    }
  }
}
```

Das Resultat enthält bei Erfolg `proofVerified=true`, `updatedModelText` und den eingebetteten SET-Proof.

## Bewusste Grenzen

Der automatische SET-Proof behauptet derzeit keine Semantik für:

- Objektmengenrouten oberhalb des Polymorphie-Budgets oder mit nicht materialisierbarer tiefer Polymorphie,
- komplexe WHERE-Objektgraphen, die nicht sicher mit dem Objektmengengraphen vereinigt werden können,
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
-> Diff und afterReview aus demselben Aufruf prüfen
```

Für einen bestehenden Constraint:

```text
bei Bedarf reviewIliConstraint
-> generateIliConstraintCases
-> generationVerified prüfen
-> coverageComplete / coverageUnsolved berichten
```

Für einen neuen UNIQUE-Constraint:

```text
authorIliUniqueConstraint
-> proofVerified prüfen
-> Diff und afterReview prüfen
-> updatedModelText übernehmen
```

Diese Abläufe vermeiden sowohl unbewiesene Semantik als auch redundante doppelte Validator-Durchläufe.


## View-Proofs für direkte Projektionen

Prio 4 unterstützt direkte `PROJECTION OF`-Views mit `ALL OF` in einem `VIEW TOPIC`,
auch mit Alias, geerbten Basisattributen und mehreren `WHERE`-Klauseln. Mandatory,
Decision Table, PLAUSIBILITY sowie globale UNIQUE- und SET-Proofs erzeugen echte
Objekte der konkreten Basisklasse. Der ursprüngliche View-Constraint bleibt das
Compiler- und Validatorziel; die Pipeline benötigt weiterhin genau zwei
Authoring-Kompilierungen.

Die Filterauswertung bildet die installierte Validator-Version ab: FALSE und ein
roher fehlender boolescher Wert schliessen ein Objekt aus. `skipEvaluation` setzt
hingegen die Filterkette fort. Insbesondere nimmt der Validator bei P08 ein Objekt
mit fehlendem Status auf, wenn `isEnumSubVal` deshalb abbricht. Das ist ausdrücklich
geprüftes Laufzeitverhalten, keine Behauptung über eine allgemeine WHERE-Semantik.
`skippedFilterCount` macht diese Fälle sichtbar. Hierarchische Enum-Filter verwenden
nur compilerbekannte Werte; unbekannte Funktionssemantik bleibt eine Proof-Grenze.

Filter und Zielbelegungen werden gemeinsam gesucht. Zusätzliche Scope-Fälle prüfen
TRUE, FALSE und UNDEFINED pro erreichbarer Filterposition; frühere Filter müssen
passiert werden. Bestehende Schlüsselwerte und Beziehungsidentitäten bleiben dabei
fest. Ausgeschlossene Populationen ergänzen eine bereits ausgeübte Population.
Unerreichbare Zustände werden nur nach unabhängiger vollständiger Partitionierung
ausgeschlossen; Sucherschöpfung bleibt eine Coverage-Lücke.

Die Verifikation zählt die tatsächlichen Basisklassen- und Unterklassenobjekte nach
Aufbau des Validator-Pools, einschliesslich ergänzter Pflichtbeziehungen. Sie meldet
`viewFqn`, `baseClassFqn`, `baseSubjectCount`, `subjectCount`, `excludedSubjectCount`
und `skippedFilterCount`; die geplante und tatsächliche Mitgliederzahl müssen
übereinstimmen. Andere Constraints werden auch in importierten Modellen deaktiviert,
Typ-, OID-, Datums-, Referenz- und Kardinalitätsprüfungen bleiben aktiv.

Für leere View-SETs werden ein leerer Datenbasket und ein leerer View-Basket übertragen.
Ein erfolgreicher Proof mit `subjectCount=0` setzt die protokollierte Ausführung des
originalen SET-Constraints voraus (`setExecuted=true`). Der Gegenversuch
`objectCount(ALL)>0` muss auf derselben leeren Fixture eine Zielverletzung melden.
Bei Mandatory, UNIQUE und PLAUSIBILITY reicht eine Population ohne Mitglieder nicht
als ausgeübter Constraint.

Die eingecheckten Regressionen verlangen vollständige Proofs für P04, P05, P07, P08 und
P10. P05 prüft null/einen Startknoten und alle hydraulischen Filteralternativen; zwei
Verknüpfungen verletzen die Rollenkardinalität und sind Fixture-Fehler. N11 bleibt an der Grenze externer
Funktionssemantik. Joins, Union, Aggregation, Inspection, verkettete Views,
berechnete Attribute, abstrakte Basisklassen sowie BASKET/LOCAL-View-Proofs werden
nicht approximiert. Automatische Filter-Fixtures unterstützen direkte skalare
Basisattribute; navigierte Filterpfade erhalten eine explizite Grenze.

View-Grenzen verwenden unter anderem `VIEW_PROOF_SHAPE_UNSUPPORTED`,
`VIEW_FILTER_SEMANTICS_UNSUPPORTED`, `VIEW_SCOPE_UNSOLVED`,
`VIEW_SCOPE_VERIFICATION_FAILED`, `VIEW_SET_NOT_EXECUTED` und
`VIEW_FIXTURE_BUDGET_EXCEEDED`. Ohne verifizierten Proof bleibt der Kandidat zur
Analyse erhalten, während `updatedModelText` nicht freigegeben wird.


## Gemeinsame Objektzählung für Mandatory und Decision Table

Die öffentliche `OBJECT_COUNT`-Spezifikation ist auch innerhalb verschachtelter Mandatory-Ausdrücke verwendbar. Decision Table unterstützt im bestehenden Feld `aggregate` zusätzlich `OBJECT_COUNT`:

```json
{
  "attribute": "mids->leaves",
  "aggregate": "OBJECT_COUNT",
  "operator": ">=",
  "value": 2
}
```

`attribute` bezeichnet hier den Objektpfad, `value` ist numerisch. `defined` und `addAttribute` sind für `OBJECT_COUNT` unzulässig. Die Bedingung wird in die gemeinsame Mandatory-Spezifikation übersetzt; bestehende SUM-Payloads behalten ihre Bedeutung.

Fehlende optionale Verbindungen ergeben null Pfadvorkommen. Nicht auflösbare Referenzen und technische Auswertungsfehler gelten als Fixture-Fehler. Der installierte Validator zählt dasselbe Ziel mehrfach, wenn unterschiedliche Wege dorthin führen. `objectCounts` in jeder Fallverifikation weist daher `plannedCount` (für generierte Fälle), `actualCount`, `distinctTargetCount`, `concreteTypeRoute` und `countingPolicy=PINNED_VALIDATOR_PATH_OCCURRENCES` aus. Das ist ausdrücklich die Zählpolitik des installierten Validators. Endliche zyklische Pfade werden anhand ihrer vorgegebenen Schritte ausgewertet; es gibt keine rekursive Suche ohne Pfadgrenze.

Nach Fixture-Ergänzung werden ursprüngliche kompilierte Zählausdrücke erneut gegen den Validator-Pool geprüft. `OBJECT_PATH_COUNT_MISMATCH` und `OBJECT_PATH_TOPOLOGY_MISMATCH` verhindern einen erfolgreichen Proof. `OBJECT_PATH_CONCRETE_ROUTE_UNAVAILABLE`, `OBJECT_PATH_STEP_BUDGET_EXCEEDED`, `OBJECT_PATH_ROUTE_BUDGET_EXCEEDED` und `OBJECT_PATH_FIXTURE_BUDGET_EXCEEDED` unterscheiden nicht unterstützte Routen und Budgets. Ungültige Zweifachverknüpfungen einer `{0..1}`-Rolle liefern `OBJECT_PATH_CARDINALITY_VIOLATION`; sonstige bekannte XTF-Schreibfehler `FIXTURE_XTF_SERIALIZATION_FAILED`.

Ohne vollständige Coverage und verifizierten Witness sowie Counterexample bleiben `generated=false`, `proofVerified=false` und der Kandidat erhalten. `updatedModelText` wird nicht freigegeben. `elementCount` für Strukturzählungen, polymorphe Strukturtypwahl, neue View-Formen und ein Validator-Upgrade gehören nicht zu dieser Unterstützung.
