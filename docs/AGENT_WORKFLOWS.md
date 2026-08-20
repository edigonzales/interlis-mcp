# Agentische Arbeitsabläufe

`interlis-mcp` ist dafür gebaut, von Coding-Agenten in längeren, kontrollierten Arbeitsabläufen verwendet zu werden. Ein Agent soll nicht einfach INTERLIS-Text generieren, sondern Kontext sammeln, geeignete Werkzeuge auswählen, technische Ergebnisse prüfen und fachliche Unsicherheiten sichtbar lassen.

Dieses Dokument beschreibt den **aktuellen** Agentenvertrag. Die maschinenwirksamen Pendants sind die MCP-Prompts und MCP-Resources im Server; Golden-Scenario- und E2E-Tests schützen zentrale Teile dieses Verhaltens.

## Verantwortungsgrenzen

Drei Rollen sind sauber getrennt:

```text
Mensch / Fachperson
  - liefert fachliche Ziele und Regeln
  - bestätigt Kardinalitäten, Rollen, Schlüssel und Constraints

Coding-Agent / MCP-Client
  - liest und schreibt Dateien im Workspace
  - wählt MCP-Tools
  - integriert Resultate in das Repository
  - führt den Arbeitsablauf aus

interlis-mcp
  - kennt INTERLIS-Werkzeuge und Modellierungsregeln
  - kompiliert mit ili2c
  - analysiert Modelle
  - erzeugt source-preserving Änderungen, wo unterstützt
  - erzeugt und verifiziert Constraint-Proofs mit ilivalidator
  - schreibt selbst keine Workspace-Dateien
```

Diese Grenze ist absichtlich. Ein MCP-Tool wie `applyIliModelChange` oder `authorIliSetConstraint` liefert `updatedModelText`; der Agent entscheidet, ob und wo dieser Text in eine Datei geschrieben wird.

## Leitprinzip: das höchste passende Tool verwenden

Ein Agent soll nicht mehrere Low-Level-Werkzeuge kombinieren, wenn ein High-Level-Tool dieselbe Aufgabe bereits vollständig abdeckt.

| Aufgabe | Bevorzugtes Tool |
| --- | --- |
| vollständigen Modellstand prüfen | `reviewIliModel` |
| Vorher/Nachher-Modell prüfen | `reviewIliChange` |
| unterstützte semantische Änderung ausführen | `applyIliModelChange` |
| neuen unterstützten Constraint erstellen | passendes `authorIli...Constraint`-Tool |
| bestehenden Constraint automatisch beweisen | `generateIliConstraintCases` |
| einzelnen Compilerfehler untersuchen | `validateIliModel` |
| einzelne Strukturfrage beantworten | `analyzeIliModel` |
| einzelne Modellierungsregel prüfen | `checkModelingRules` |
| bestehenden Constraint erklären | `reviewIliConstraint` |
| explizit vorgegebene Constraint-Testdaten prüfen | `testIliConstraint` |

### Warum das wichtig ist

`reviewIliModel` kompiliert das Modell bereits und kombiniert Compilerdiagnostik, Struktur und Regelcheck. Folgender Ablauf wäre deshalb unnötig:

```text
validateIliModel
-> analyzeIliModel
-> checkModelingRules
-> reviewIliModel
```

Der normale Ablauf ist stattdessen:

```text
reviewIliModel
```

Low-Level-Tools kommen nur hinzu, wenn danach eine **konkrete Detailfrage** offen ist.

## Fachliche Semantik niemals erfinden

INTERLIS enthält viele Aussagen, die fachlich und nicht nur technisch sind. Dazu gehören insbesondere:

- Klassen- und Attributzuschnitt,
- Kardinalitäten,
- Rollennamen,
- Schlüssel und UNIQUE-Regeln,
- Mandatory-/Existence-/Plausibility-/Set-Constraints,
- fachliche Domains und Enumerationswerte,
- Datenumbau- und Mappinglogik.

Ein Agent darf solche Informationen nur verwenden, wenn sie aus einer Quelle, einem bestehenden Modell oder einer expliziten Benutzeranforderung stammen.

### Beispiel: fehlende Kardinalität

Anforderung:

> Modellieren Sie eine Beziehung zwischen `Gebaeude` und `Adresse`.

Ohne weitere Fachinformation darf der Agent **nicht** automatisch `{1}` und `{0..*}` festlegen. Eine mögliche technische Struktur kann vorbereitet werden, die Kardinalität bleibt aber eine offene Frage.

`createAssociationSnippet` unterstützt dieses Prinzip: fehlende Kardinalitäten und automatisch erzeugte Namen werden in `openQuestions` sichtbar gemacht.

## Vollständiges Modell prüfen

Wenn nur ein aktueller Modellstand vorliegt, ist `reviewIliModel` das Abschlussgate.

```json
{
  "modelText": "<vollständiger Modelltext>",
  "modelPurpose": "PUBLICATION",
  "ruleProfile": "SO"
}
```

Der Agent berichtet die Resultate in dieser Reihenfolge:

1. blockierende Compilerfehler,
2. automatisierte ERROR-Findings,
3. WARNING-/INFO-Findings,
4. `manualChecks`,
5. `openQuestions`.

Technische Befunde und fachliche Fragen werden getrennt dargestellt.

## Bestehendes Modell ändern

Vor einer Änderung muss klar sein, ob ein spezielles semantisches Change-Tool existiert.

### Unterstützte Änderung: Attribut hinzufügen

Für `ADD_ATTRIBUTE` verwendet der Agent `applyIliModelChange` statt selbst eine Einfügestelle im Quelltext zu suchen.

```json
{
  "modelText": "<vorher>",
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

Bei `status=APPLIED` hat das Tool bereits:

- den Vorher-Stand kompiliert,
- die Änderung source-preserving eingefügt,
- den Nachher-Stand kompiliert,
- einen semantischen Diff erstellt,
- unerwartete Kollateraleffekte ausgeschlossen,
- `afterReview` erstellt.

Für genau diesen unveränderten Nachher-Stand folgt **kein** zusätzliches `reviewIliChange` und kein `reviewIliModel`.

### Nicht unterstützte Änderung

Wenn kein passendes semantisches Change-Tool existiert, darf der Agent den Quelltext gezielt bearbeiten. Der neue Stand wird anschliessend einmal mit `reviewIliChange` geprüft:

```text
Vorher-Text
   |
   | gezielte Workspace-Änderung durch den Agenten
   v
Nachher-Text
   |
   v
reviewIliChange(Vorher, Nachher)
```

Wichtig sind insbesondere `potentiallyBreakingChanges`, `impact`, `afterCompilerValid`, `afterDiagnostics` und `afterReview`.

Wird der Nachher-Stand danach noch einmal geändert, gilt das Review des alten Stands nicht mehr; der neue Stand muss erneut geprüft werden.

## Einen Constraint erstellen

Constraint-Authoring hat zwei getrennte technische Gates:

```text
fachliche Constraint-Regel
        |
        v
Constraint-Authoring + Validator-Proof
        |
        | proofVerified=true
        v
aktualisierter Modelltext
        |
        v
reviewIliChange(Vorher, Nachher)
        |
        v
Modell-Level-Abschlussreview
```

Das zweite Gate ist nur nötig, wenn ein **bestehendes Modell** durch Constraint-Authoring verändert wurde. Der Constraint-Proof sagt aus, dass die erzeugten Proof-Fälle die erwartete Validator-Semantik haben. `reviewIliChange` beurteilt dagegen den gesamten Modellunterschied.

### Toolwahl

- MANDATORY → `authorIliMandatoryConstraint`
- EXISTENCE → `authorIliExistenceConstraint`
- PLAUSIBILITY → `authorIliPlausibilityConstraint`
- unterstütztes SET → `authorIliSetConstraint`
- UNIQUE → noch kein gleichwertiges typisiertes Authoring; einfacher `createUniqueConstraint`-Snippet oder gezielte Bearbeitung, danach `generateIliConstraintCases`

Bei einem typisierten Authoring-Tool muss `proofVerified=true` sein. Bei einem bestehenden oder separat integrierten Constraint ist `generationVerified=true` aus `generateIliConstraintCases` das Proof-Gate.

### Keine redundante zweite Proof-Runde

Nach einem erfolgreichen automatischen Proof wird derselbe unveränderte Constraint nicht routinemässig nochmals über `testIliConstraint` oder `validateXtf` geprüft.

`testIliConstraint` ist sinnvoll, wenn der Benutzer **konkrete eigene Testfälle** vorgibt oder wenn eine automatisch nicht unterstützte Konstellation bewusst mit handgebauten Daten geprüft werden soll.

Mehr Details stehen in [CONSTRAINTS.md](CONSTRAINTS.md).

## Compilerfehler reparieren

`reviewIliModel` ist das normale Gesamtreview. Für einen fokussierten Repair-Loop kann danach `validateIliModel` verwendet werden.

Beispiel für ein fehlerhaftes Fragment:

```ili
CLASS Building =
  name TEXT*80;
END Building;
```

`validateIliModel` kann eine Compilerdiagnose inklusive `sourceExcerpt` liefern. Der Agent nutzt den Ausschnitt, korrigiert den konkreten Syntaxfehler und prüft den **neuen vollständigen Stand** wieder mit dem passenden High-Level-Tool.

Der Repair-Loop ist also nicht:

```text
immer wieder sämtliche Analysewerkzeuge ausführen
```

sondern:

```text
High-Level-Review
-> konkrete Compilerdiagnose
-> gezielte Reparatur
-> High-Level-Review des neuen Stands
```

## Vorbilder aus dem lokalen Modellkorpus verwenden

Wenn ein Modellierungsmuster nicht aus der Aufgabenstellung hervorgeht, kann ein Agent lokale Beispiele suchen.

```text
findSimilarModels
       |
       | Treffer auswählen
       v
readModelExample
       |
       | vollständiges Modell lesen
       v
Muster auf aktuelle Aufgabe übertragen
```

Ein Suchtreffer enthält nur Discovery-Metadaten und einen Ausschnitt. Erst das vollständige Modell zeigt beispielsweise Imports, Domains, Topic-Kontext, Dokumentation und weitere Abhängigkeiten.

### Beispiel

```json
{
  "query": "Publikationsmodell Gebäude Adresse"
}
```

Nach Auswahl eines Treffers:

```json
{
  "path": "/models/BuildingPublication.ili"
}
```

Der Agent übernimmt nur ein **technisches Muster**, wenn es passt. Fachliche Klassen, Attribute oder Constraints des Vorbilds werden nicht automatisch in das neue Modell kopiert.

## Geometrien modellieren

Bei einer Geometrieanforderung darf der Agent weder CRS noch Achsgrenzen erraten. `ensureGeometryDependencies` verlangt ohne CHBase eine explizit gewählte Koordinatendomain und liefert daraus den zusammenhängenden technischen Vorschlag.

```json
{
  "attributeName": "Perimeter",
  "geometryType": "SURFACE",
  "coordDomainFqn": "Demo.Coord2",
  "arcs": true
}
```

Anschliessend integriert der Agent `importLinesToAdd`, `domainsToAdd` und `attributeLine` in den Workspace und prüft den vollständigen neuen Modellstand.

Wenn die genaue Geometrieart unklar ist, kann vorher `listGeometryTypes` verwendet werden.

## Entscheidungstabellen

Wenn eine fachliche Regel bereits als explizite Entscheidungstabelle vorliegt, ist `generateIliConstraintFromDecisionTable` passender als das freie Formulieren einer booleschen Expression.

Beispielhafte Fachvorgabe:

| Status | Typ | erlaubt |
| --- | --- | --- |
| `#aktiv` | `#A` | ja |
| `#aktiv` | `#B` | ja |
| `#inaktiv` | beliebig | nein |

Der Agent überführt nur die **gegebenen** erlaubten Zeilen in den strukturierten Tool-Payload. Das Tool erzeugt daraus den Constraint und Validator-Proofs. Es erfindet keine fehlenden Fachkombinationen.

## Ergebnis eines agentischen Modellierungsauftrags

Eine gute Abschlussantwort eines Agenten enthält nicht nur ILI-Text. Sie trennt mindestens:

- **Modelländerung:** Was wurde konkret geändert?
- **Technische Prüfung:** Welches High-Level-Gate wurde ausgeführt und mit welchem Ergebnis?
- **Constraint-Proof:** Falls relevant `proofVerified`/`generationVerified`, Coverage und Safety-Grenzen.
- **Potenzielle Inkompatibilitäten:** `impact` und `potentiallyBreakingChanges`.
- **Fachliche offene Fragen:** Kardinalitäten, Namen, Schlüssel oder andere nicht technisch entscheidbare Punkte.

## MCP-Prompts und Resources

Agenten müssen diese Regeln nicht aus einer externen Promptdatei duplizieren. Der Server stellt sie selbst bereit:

### Prompts

- `interlis-modeling-agent`
- `review-interlis-model`
- `extend-interlis-model`
- `author-interlis-constraint`

### Resources

- `interlis://knowledge/agent-workflow`
- `interlis://knowledge/tool-guide`
- `interlis://knowledge/constraint-workflow`
- `interlis://knowledge/handbook-rules`
- `interlis://knowledge/model-corpus-index`

Diese Schnittstellen werden durch Tests abgesichert. Client-spezifische, kopierte Mega-Prompts sollen deshalb nicht als parallele zweite Wahrheit gepflegt werden.

## Weiterführende Dokumentation

- [Benutzerhandbuch](USER_GUIDE.md)
- [Tool-Referenz](TOOL_REFERENCE.md)
- [Constraints](CONSTRAINTS.md)
- [Architektur](ARCHITECTURE.md)
