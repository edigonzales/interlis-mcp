# Tool-Referenz

Diese Referenz ordnet die öffentlichen MCP-Resources, Prompts und Tools nach ihrem Einsatzzweck. Die **exakte** Parameterstruktur wird zur Laufzeit über `tools/list` veröffentlicht und in `ToolRegistrationContractTest` geprüft. Die Beispiele hier sollen die Auswahl und den typischen Payload erklären.

## Grundregel zur Tool-Auswahl

Verwende das höchste Tool, das eine Aufgabe vollständig abdeckt.

```text
Gesamtmodell prüfen?             -> reviewIliModel
Vorher/Nachher vergleichen?      -> reviewIliChange
Unterstützte Modelländerung?     -> applyIliModelChange
Constraint erstellen?            -> typed Authoring-Tool, wenn vorhanden
Constraint automatisch beweisen? -> generateIliConstraintCases
Gezielte Diagnose?               -> passendes Low-Level-Tool
```

Ein höheres Tool darf intern Compiler, Analyse, Regeln oder Validator verwenden. Dieselben Low-Level-Tools danach noch einmal routinemässig aufzurufen erzeugt meist nur doppelte Arbeit.

# MCP-Resources

## `interlis://knowledge/handbook-rules`

Enthält die kuratierten Modellierungsregeln des Profils `SO`, einschliesslich der portablen `CORE`-Regeln.

Verwenden, wenn ein Agent die Regeln und deren Begründung lesen soll.

## `interlis://knowledge/agent-workflow`

Kompakter agentischer Arbeitsablauf für Modellierung, Änderung und Review.

## `interlis://knowledge/tool-guide`

Entscheidungshilfe für High-Level- und Low-Level-Tools, Modellbeispiele und Constraint-Werkzeuge.

## `interlis://knowledge/constraint-workflow`

Entscheidungsmatrix für Constraint-Authoring, automatische Proofs und das abschliessende Modell-Level-Review.

## `interlis://knowledge/model-corpus-index`

Markdown-Index der über `interlis.knowledge.model-paths` konfigurierten `.ili`-Dateien.

# MCP-Prompts

## `interlis-modeling-agent`

Allgemeine Systemanweisung für agentisches INTERLIS-Modellieren. Der Prompt bevorzugt High-Level-Reviews, semantische Änderungen und typisierte Constraint-Werkzeuge und verbietet das Erfinden fachlicher Semantik.

## `review-interlis-model`

Strukturierter Ablauf für die Prüfung eines vollständigen Modells mit `reviewIliModel`.

Optionales Argument:

```json
{ "modelPurpose": "PUBLICATION" }
```

Mögliche Werte sind `CAPTURE`, `PUBLICATION`, `VALIDATION` und `UNKNOWN`.

## `extend-interlis-model`

Ablauf für eine kontrollierte Änderung eines bestehenden Modells. Der Prompt unterscheidet zwischen `applyIliModelChange`, Constraint-Authoring und gezielter manueller Bearbeitung mit `reviewIliChange`.

## `author-interlis-constraint`

Entscheidungshilfe für MANDATORY, UNIQUE, EXISTENCE, PLAUSIBILITY und SET.

Beispiel:

```json
{ "constraintKind": "PLAUSIBILITY" }
```

# High-Level-Review und Modelländerung

## `reviewIliModel`

**Verwendung:** ein vollständiger aktueller Modellstand ohne Vorher-Stand.

Wichtige Eingaben:

- `modelText`
- optional `modelPurpose`
- optional `ruleProfile`
- optional `modelRepositories`

Wichtige Ausgaben:

- `compilerValid`
- `compilerDiagnostics`
- `structure`
- `validForAutomatedRules`
- `ruleFindings`
- `manualChecks`
- `openQuestions`

## `reviewIliChange`

**Verwendung:** semantischer Vergleich eines Vorher-/Nachher-Modells.

Eingaben:

```json
{
  "beforeModelText": "<vorher>",
  "afterModelText": "<nachher>",
  "modelPurpose": "CAPTURE",
  "ruleProfile": "CORE"
}
```

Wichtige Ausgaben:

- `added`, `removed`, `changed`
- `potentiallyBreakingChanges`
- `impact`
- `afterCompilerValid`
- `afterDiagnostics`
- `afterReview`

Das enthaltene `afterReview` ist der Abschlussreview für diesen unveränderten Nachher-Stand.

## `applyIliModelChange`

**Verwendung:** unterstützte source-preserving Änderung eines vollständigen Modells.

Aktuell unterstützt:

- `ADD_ATTRIBUTE` für lokale `CLASS`- und `STRUCTURE`-Elemente.

Beispiel:

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
  }
}
```

Bei `status=APPLIED` enthält `updatedModelText` nur den freigegebenen neuen Stand. Unerwartete semantische Kollateraleffekte werden als Fehler behandelt und nicht still übernommen.

# Modellanalyse und gezielte Diagnostik

## `validateIliModel`

Low-Level-Compilerdiagnostik mit ili2c. Für Repair-Loops können Meldungen einen `sourceExcerpt` enthalten.

```json
{
  "modelText": "<vollständiger Modelltext>"
}
```

## `analyzeIliModel`

Parserbasierte Struktur- und Semantikanalyse. Liefert Modelle, Topics, Klassen, Strukturen, Domains, Units, Beziehungen, Attribute, Vererbungen, Topic-Abhängigkeiten und weitere ausgewählte Metamodellinformationen.

## `listModelingRules`

Listet die kuratierten Regeln eines Profils. `CORE` ist Standard; `SO` enthält zusätzlich Solothurn-spezifische Regeln.

## `checkModelingRules`

Gezielter Regelcheck. Geeignet, wenn einzelne Regel-IDs oder ein bestimmter Modellzweck untersucht werden sollen.

Für den normalen Gesamtmodell-Review ist `reviewIliModel` vorzuziehen.

# Lokaler Modellkorpus

## `indexConfiguredModels`

Inventar-/Administrationstool für die konfigurierten Modellpfade. Meldet gefundene, ignorierte und fehlerhafte Dateien.

## `findSimilarModels`

Lexikalische lokale Suche nach ähnlichen `.ili`-Modellen.

```json
{
  "query": "Grundstück Gebäude Publikation"
}
```

Treffer dienen der Auswahl eines Vorbilds, nicht als vollständige Modellierungsquelle.

## `readModelExample`

Liest einen ausgewählten Treffer vollständig. Der Pfad muss innerhalb des konfigurierten Modellkorpus liegen.

# Modell- und Snippet-Werkzeuge

Die folgenden Tools erzeugen kleine INTERLIS-Bausteine und liefern typischerweise `iliSnippet`:

- `createModelSnippet`
- `createTopicSnippet`
- `createClassSnippet`
- `createStructureSnippet`
- `createAssociationSnippet`
- `createEnumDomainSnippet`
- `createEnumTreeDomainSnippet`
- `createNumericDomainSnippet`
- `createUnitSnippet`
- `createCoordDomainSnippet`
- `createStructureAttributeLine`
- `createMetaAttributeBlock`

Sie sind für lokale Konstruktion gedacht. Sobald daraus ein vollständiger Modellstand entsteht, sollte das passende High-Level-Review folgen.

## `createAttributeLine`

Strikt typisierter Attribut-Helper. Der strukturierte Payload liegt unter `req`.

Beispiel für einen numerischen Wertebereich:

```json
{
  "req": {
    "name": "hoehe",
    "mandatory": true,
    "typeSpec": {
      "baseType": {
        "kind": "NUM_RANGE",
        "min": 0,
        "max": 100,
        "unitFqn": "INTERLIS.m"
      }
    }
  }
}
```

Beispiel für eine externe Referenz:

```json
{
  "req": {
    "name": "ziel",
    "typeSpec": {
      "referenceType": {
        "targetClassFqn": "Demo.Data.Target",
        "external": true
      }
    }
  }
}
```

`typeSpec` wählt genau eine Typfamilie, beispielsweise `domainFqn`, `baseType`, `referenceType`, `blackboxType`, `enumTreeValueType`, `basketType`, `objectType` oder `metaobjectType`.

## `createAssociationSnippet`

Jede Rolle benötigt `classFQN`; `name`, `card` und `external` sind optional.

```json
{
  "roles": [
    { "classFQN": "Demo.Data.Source", "card": "{1}" },
    { "classFQN": "Demo.Data.Target", "card": "{0..*}" }
  ]
}
```

Fehlende Namen werden deterministisch als technische Platzhalter erzeugt und in `openQuestions` ausgewiesen. Fehlende Kardinalitäten werden nicht geraten.

## `renameModelElement`

Robustes Rename über das ili2c-Metamodell. Das Resultat ist semantisch neu generiert und deshalb nicht source-preserving bezüglich Formatierung.

## `createImportLine`

Erzeugt eine einzelne korrekte `IMPORTS`-Zeile.

## `formatIliModel`

Formatiert vollständigen INTERLIS-Modelltext. Formatierung ist keine semantische Prüfung; ein fachliches Review wird dadurch nicht ersetzt.

# Geometrie

## `listGeometryTypes`

Listet unterstützte Geometrietypen und die benötigten Modelle.

## `ensureGeometryDependencies`

Bevorzugter Einstieg für Geometrieattribute. Liefert zusammenhängend:

- `importLinesToAdd`
- `domainsToAdd`
- `attributeLine`
- `notes`

Beispiel:

```json
{
  "attributeName": "Perimeter",
  "arcs": true
}
```

## `createCoordDomainSnippet`

Erzeugt eine COORD-Domain, wenn explizit eine solche Domain benötigt wird. Für ein komplettes Geometrieattribut ist `ensureGeometryDependencies` meist hilfreicher.

# Funktionen

## `listMathFunctions`

Listet mathematische Standardfunktionen für die gewünschte INTERLIS-Version.

## `listTextFunctions`

Listet Text-/String-Funktionen.

## `listConstraintFunctions`

Liefert Constraint-Funktionen mit stabilen semantischen IDs. Diese IDs sollten beim typisierten MANDATORY-/PLAUSIBILITY-Authoring verwendet werden, statt versionsabhängige Funktionssyntax zu raten.

# Constraints

Die Semantik und Beispiele sind ausführlich in [CONSTRAINTS.md](CONSTRAINTS.md) beschrieben.

## `reviewIliConstraint`

Erklärt einen bestehenden Constraint anhand des kompilierten ili2c-ASTs: Kontext, Pfade, Typen, Funktionen und strukturelle Randfälle.

Dieses Tool erzeugt keine Testdaten.

## `generateIliConstraintCases`

Automatischer semantischer Proof für unterstützte MANDATORY-, UNIQUE-, EXISTENCE-, PLAUSIBILITY- und SET-Constraints.

```json
{
  "modelText": "<vollständiger Modelltext>",
  "constraint": "Demo.Data.Item.MinimumValue"
}
```

Wichtige Ergebnisfelder:

- `generationVerified`
- `generatedCases`
- `coverageGoalCount`
- `coverageSolvedCount`
- `coverageComplete`
- optional `coverageUnsolved`
- `verification`

## `testIliConstraint`

Prüft explizit vorgegebene Testfälle mit modellbewusst erzeugtem XTF und ilivalidator. Nicht als redundanter zweiter Proof nach `generateIliConstraintCases` verwenden.

## `authorIliMandatoryConstraint`

Typisiertes source-preserving Authoring über eine flache Expression-Node-Liste.

Unterstützte Knotenarten umfassen `ATTRIBUTE`, `PATH`, `NUMERIC`, `BOOLEAN`, `ENUM`, `TEXT`, `MTEXT`, `FUNCTION`, `DEFINED`, `NOT`, `AND`, `OR`, `IMPLIES` und `COMPARE`.

## `authorIliExistenceConstraint`

Typisiertes Authoring für skalare NUMERIC-/BOOLEAN-/ENUM-/TEXT-/MTEXT-EXISTENCE-Constraints mit expliziten `REQUIRED IN`-Zielen.

## `authorIliPlausibilityConstraint`

Typisiertes Authoring mit `direction`, `percentage` und derselben semantischen Expression-Struktur wie MANDATORY.

## `authorIliSetConstraint`

Typisiertes Authoring für den unterstützten `INTERLIS.objectCount(ALL)`-Umfang mit `operator`, `threshold`, optionalem `where` und `perBasket`.

## `generateIliConstraintFromDecisionTable`

Spezialisiertes Frontend, wenn eine fachliche Regel als Entscheidungstabelle mit erlaubten Zeilen vorliegt. Es erzeugt einen MANDATORY Constraint und verifiziert die abgeleiteten Fälle über dieselbe Validator-Infrastruktur.

## Legacy-/Snippet-Constraint-Helper

- `createMandatoryConstraint`
- `createSetConstraint`
- `createExistenceConstraint`

Diese Tools erzeugen nur freie Snippets. Für neue Regeln sind die typisierten Authoring-Tools vorzuziehen, wenn sie die benötigte Semantik ausdrücken können.

`createUniqueConstraint` ist derzeit die bewusst eng begrenzte Ausnahme, weil noch kein gleichwertiges typisiertes UNIQUE-Authoring existiert. Es erzeugt einen einfachen globalen Schlüssel wie:

```ili
UNIQUE code, version;
```

Komplexe Formen mit `WHERE`, `(BASKET)` oder `LOCAL` sollten nicht aus dem einfachen Snippet-Schema abgeleitet werden.

Weitere ältere Spezial-Helper wie `createPresentIfConstraint` und `createValueRangeConstraint` erzeugen ebenfalls Snippets; sie ersetzen keinen semantischen Proof.

# XTF

## `generateExampleXtf`

Erzeugt deterministische minimale XTF-Daten für sicher unterstützte Typen. Nicht sicher erzeugbare Klassen erscheinen in `skippedClasses`.

## `validateXtf`

Validiert übergebenen XTF-Text gegen das Modell mit ilivalidator.

# Namens- und FQN-Helfer

## `sanitizeIdentifier`

Bereinigt Freitext zu einem INTERLIS-kompatiblen, nicht reservierten Identifier.

## `validateIdentifier`

Prüft einen Identifier.

## `validateFqn`

Prüft einen vollqualifizierten Namen; `INTERLIS` ist als erstes Segment für eingebaute Referenzen wie `INTERLIS.m` zulässig.
