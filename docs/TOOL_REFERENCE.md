# Tool-Referenz

Diese Referenz beschreibt die öffentliche MCP-Oberfläche. Die Java-Annotationen und `ToolRegistrationContractTest` sind der exakte Schema-Vertrag. Modell-Repositories werden serverseitig mit `interlis.mcp.model-repositories` konfiguriert und sind bewusst kein Parameter jedes einzelnen Tools.

## Auswahlregel

Verwende das höchste Tool, das die Aufgabe vollständig abdeckt. Ein High-Level-Review soll nicht routinemässig durch dieselben Low-Level-Prüfungen wiederholt werden. Der Server trifft keine fachlichen Annahmen zu Kardinalitäten, Constraints, Koordinatensystemen oder Metadaten.

## Resources und Prompts

Die Resources `interlis://knowledge/agent-workflow`, `interlis://knowledge/tool-guide`, `interlis://knowledge/constraint-workflow`, `interlis://knowledge/handbook-rules` und `interlis://knowledge/model-corpus-index` stellen Regeln und Abläufe bereit. Die Prompts `interlis-modeling-agent`, `review-interlis-model`, `extend-interlis-model` und `author-interlis-constraint` übersetzen sie in konkrete Agentenaufträge.

# Reviews und Änderungen

## `reviewIliModel`

High-Level-Gate für einen vollständigen aktuellen Modellstand. Eingaben sind `modelText` sowie optional `modelPurpose` und `ruleProfile`. Die Antwort kombiniert Compilerdiagnostik, Struktur, automatisierte Regeln, manuelle Checks und offene Fragen.

## `reviewIliChange`

Vergleicht `beforeModelText` und `afterModelText` semantisch. Views sowie die deklarierten Typen von Domains und Attributen gehören zum Diff. Optional steuern `modelPurpose` und `ruleProfile` den Abschlussreview des Nachher-Stands.

## `applyIliModelChange`

Führt unterstützte typisierte Änderungen source-preserving aus. Aktuell ist `ADD_ATTRIBUTE` für lokale Klassen und Strukturen verfügbar. Ein erfolgreiches Resultat enthält den neuen Modelltext, den semantischen Diff und das Abschlussreview.

## `renameModelElement`

Benennt ein Modellelement über das ili2c-Metamodell um und regeneriert den Modelltext. Optional begrenzt `expectedKind` die zulässige Elementart. Die Operation ist semantisch robust, aber nicht layout-erhaltend.

# Analyse und Regeln

## `validateIliModel`

Fokussierte ili2c-Compilerdiagnostik für `modelText`. Diagnosen des eingereichten Modells verwenden den stabilen Dateinamen `<submitted-model>` und können einen `sourceExcerpt` enthalten.

## `analyzeIliModel`

Liefert die kompilierte Modellstruktur, darunter Modelle, Topics, Klassen, Strukturen, Views, Beziehungen, Domains, Units, Attribute, Vererbung und Abhängigkeiten. `modelPurpose` ist optional.

## `listModelingRules`

Listet den Regelkatalog für `CORE` oder `SO`. Das Tool prüft selbst kein Modell.

## `checkModelingRules`

Prüft gezielt Regeln gegen ein Modell. Optional können Modellzweck, Regel-IDs und Profil eingeschränkt werden. Für das normale Gesamtgate ist der High-Level-Review kompakter.

# Modellkorpus

## `indexConfiguredModels`

Inventarisiert die über `interlis.knowledge.model-paths` konfigurierten lokalen `.ili`-Dateien.

## `findSimilarModels`

Sucht lexikalisch nach lokalen Modellbeispielen. Die Treffer sind Discovery-Metadaten, kein Ersatz für das vollständige Vorbild.

## `readModelExample`

Liest eine ausgewählte Datei innerhalb des konfigurierten Modellkorpus vollständig. Pfade ausserhalb dieser Grenzen werden abgewiesen.

# Modellbausteine

## `createModelSnippet`

Erzeugt ein Modellgerüst. `name` ist erforderlich; Sprache, URI, Version, INTERLIS-Version, Imports, IliDoc und Metaattribute sind optional. Das Tool erzeugt keine kantonsspezifischen Titel, Kontakte oder Tags.

## `createTopicSnippet`

Erzeugt einen Topic-Block mit optionaler OID-Definition, Abstraktheit, IliDoc und Metaattributen.

## `createClassSnippet`

Erzeugt eine Klasse mit optionaler Abstraktheit, Basisklasse, OID-Definition und Attributzeilen.

## `createStructureSnippet`

Erzeugt eine Struktur mit optionaler Abstraktheit, Basistyp und Attributzeilen.

## `createAssociationSnippet`

Erzeugt eine Beziehung aus mindestens zwei Rollen. Fehlende Rollennamen werden nur als technische Platzhalter markiert; fehlende Kardinalitäten werden nicht fachlich geraten.

## `createAttributeLine`

Erzeugt eine streng typisierte Attributzeile aus `req`. `typeSpec` wählt genau eine Familie, darunter `domainFqn`, `structureFqn`, `baseType`, Referenz-, Blackbox-, Basket-, Object- oder Metaobject-Typ. Typfremde Felder werden abgewiesen statt ignoriert.

## `createEnumDomainSnippet`

Erzeugt eine flache Aufzählungsdomain. Entweder `items` oder annotierte `itemSpecs` müssen angegeben werden.

## `createEnumTreeDomainSnippet`

Erzeugt eine rekursive Aufzählungsdomain aus typisierten Baumknoten.

## `createNumericDomainSnippet`

Erzeugt eine numerische Domain aus Name, Minimum und Maximum sowie optionaler Einheit und Annotationen.

## `createUnitSnippet`

Erzeugt eine linear abgeleitete Einheit aus positivem Faktor und expliziter Basiseinheit.

## `createCoordDomainSnippet`

Erzeugt eine zwei- oder dreidimensionale COORD-Domain ohne CRS-Annahmen. Jede Achse verlangt `min`, `max` und `unitFqn`; eine Rotation muss als gültiges Paar angegeben werden.

```json
{
  "name": "LocalCoord",
  "axes": [
    { "min": 0.00, "max": 100.00, "unitFqn": "INTERLIS.m" },
    { "min": 10.00, "max": 200.00, "unitFqn": "INTERLIS.m" }
  ],
  "rotationFrom": 2,
  "rotationTo": 1
}
```

## `createUniqueConstraint`

Erzeugt nur einen einfachen globalen UNIQUE-Snippet aus `attrs`. WHERE-, BASKET- und LOCAL-Semantik werden nicht aus diesem engen Schema abgeleitet. Nach Integration folgen semantischer Proof und Modellreview.

## `formatIliModel`

Regeneriert formatierten Modelltext mit ili2c. Formatierung ist kein fachliches Review.

# Geometrie

## `listGeometryTypes`

Listet die unterstützten INTERLIS- und CHBase-Geometrietypen für INTERLIS 2.3 oder 2.4.

## `ensureGeometryDependencies`

Erzeugt Importhinweise und eine Geometrie-Attributzeile. Ohne CHBase ist `coordDomainFqn` erforderlich; das Tool erfindet keine Koordinatendomain. Mit CHBase werden nur bekannte Typen des zur INTERLIS-Version passenden Modells akzeptiert.

```json
{
  "attributeName": "Perimeter",
  "geometryType": "SURFACE",
  "coordDomainFqn": "Demo.Coord2",
  "arcs": true
}
```

# Constraint-Wissen und Proofs

## `listConstraintFunctions`

Liefert den kanonischen Funktionskatalog mit stabilen semantischen IDs und typisierten Parametern für INTERLIS 2.3 oder 2.4.

## `resolveConstraintPath`

Löst einen Objekt-/Attributpfad im kompilierten Kontext auf und liefert Schritte, Kardinalitäten und Zieltyp.

## `reviewIliConstraint`

Erklärt einen vorhandenen Constraint aus dem ili2c-AST. Das Resultat enthält Kontext, Pfade, Funktionen, Typen und strukturelle Randfälle, aber keine automatisch erzeugten Testdaten.

## `generateIliConstraintCases`

Erzeugt und verifiziert modellbewusste Witnesses, Counterexamples, Grenz- und Scope-Fälle für die unterstützte MANDATORY-, UNIQUE-, EXISTENCE-, PLAUSIBILITY- und SET-Semantik. `coverageUnsolved` und Safety-Codes sind bewusste Grenzen.

## `testIliConstraint`

Validiert höchstens 100 explizit vorgegebene Testfälle. Das Tool ist für fachliche oder gezielte Regressionstestdaten gedacht, nicht als redundante Wiederholung eines erfolgreichen automatischen Proofs.

## `authorIliMandatoryConstraint`

Erstellt einen Mandatory Constraint aus einer typisierten flachen Ausdrucks-Node-Liste, fügt ihn source-preserving ein und beweist ihn über die gemeinsame semantische Pipeline.

## `authorIliExistenceConstraint`

Erstellt skalare EXISTENCE-Constraints aus einem expliziten eingeschränkten Pfad und vollständigen REQUIRED-IN-Zielen. Zielattribute werden nie geraten.

## `authorIliPlausibilityConstraint`

Erstellt einen Plausibility Constraint aus Richtung, Prozentgrenze und typisiertem Ausdruck. Der Proof verwendet echte Objektpopulationen.

## `authorIliSetConstraint`

Erstellt den unterstützten `INTERLIS.objectCount(ALL)`-Umfang mit Operator, Schwellwert, optionalem direktem WHERE und optionalem Basket-Scope.

## `generateIliConstraintFromDecisionTable`

Übersetzt explizit erlaubte Entscheidungszeilen in einen Mandatory Constraint. Source-Edit, semantische Übersetzung, Fallgenerierung und Validator-Proof verwenden dieselbe Pipeline wie das übrige typed Authoring.

# XTF

## `generateExampleXtf`

Erzeugt deterministisches Minimal-XTF für sicher materialisierbare Klassen. `maxObjectsPerClass` liegt zwischen 1 und 20. Koordinaten werden aus den tatsächlichen Domain-Grenzen abgeleitet.

## `validateXtf`

Validiert bis zu 20 MiB XTF-Text gegen den vollständigen Modelltext mit ilivalidator und liefert strukturierte Fehler- und Warnungszählungen.
