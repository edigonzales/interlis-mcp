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

## `authorIliModel`

Erzeugt ein vollständiges Modell aus einer typisierten `IliModelSpec`. Name, URI, Modellversion und INTERLIS-Version sind explizit. Units, Domains, Topics, Klassen, Strukturen, Assoziationen, Attribute, Geometrien und alle fünf Constraint-Arten werden in einem Compile geprüft; AST-Roundtrip, Constraint-Proofs und `afterReview` verwenden denselben kompilierten Kontext.

## `applyIliModelChanges`

Wendet einen atomaren Batch source-preserving an. Unterstützt `ADD_IMPORT`, `ADD_TOPIC`, `ADD_DOMAIN`, `ADD_UNIT`, `ADD_CLASS`, `ADD_STRUCTURE`, `ADD_ASSOCIATION`, `ADD_ATTRIBUTE`, `UPDATE_ATTRIBUTE`, `REMOVE_ATTRIBUTE` und `ADD_CONSTRAINT`. Der Batch nutzt genau einen Before- und After-Compile. Potenziell brechende Änderungen werden ohne `allowPotentiallyBreaking=true` nur als geprüfter Kandidat zurückgegeben.

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

# Modelltext

## `formatIliModel`

Regeneriert formatierten Modelltext mit ili2c. Formatierung ist kein fachliches Review.

# Geometrie

## `listGeometryTypes`

Listet die unterstützten INTERLIS- und CHBase-Geometrietypen für INTERLIS 2.3 oder 2.4.

Geometrien werden innerhalb von `authorIliModel` und `applyIliModelChanges` als `GeometryTypeSpec` angegeben. Die ungültigen früheren `BaseType`-Varianten `COORD`, `POLYLINE` und `SURFACE_SIMPLE` existieren nicht mehr. INTERLIS-Geometrien verlangen alle anwendbaren Angaben; CHBase lässt nur bekannte Typen der tatsächlichen INTERLIS-Version zu.

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

Erstellt einen Mandatory Constraint aus einer rekursiven typisierten `ExpressionSpec`, fügt ihn source-preserving ein und beweist ihn über die gemeinsame semantische Pipeline.

## `authorIliUniqueConstraint`

Erstellt UNIQUE mit `GLOBAL`, `BASKET` oder `LOCAL`, mehreren Schlüsselpfaden, optionalem typisiertem WHERE und LOCAL-Präfix. Source-Edit, AST-Roundtrip, Proof, semantischer Diff und `afterReview` sind enthalten.

## `authorIliExistenceConstraint`

Erstellt EXISTENCE aus einem expliziten eingeschränkten Pfad und vollständigen REQUIRED-IN-Zielen. Skalare, strukturierte, Referenz- und Geometriepfade werden typisiert; freigegeben werden nur vollständig validatorbestätigte Proofs. Zielattribute werden nie geraten.

## `authorIliPlausibilityConstraint`

Erstellt einen Plausibility Constraint aus Richtung, Prozentgrenze und typisiertem Ausdruck. Der Proof verwendet echte Objektpopulationen.

## `authorIliSetConstraint`

Erstellt SET mit `GLOBAL`/`BASKET`, optionalem WHERE und diskriminierter `OBJECT_COUNT`- oder `BOOLEAN_EXPRESSION`-Bedingung. Objektmengen sind `ALL` oder ein typisierter navigierter Objektpfad.

## `generateIliConstraintFromDecisionTable`

Übersetzt explizit erlaubte Entscheidungszeilen in einen Mandatory Constraint. Source-Edit, semantische Übersetzung, Fallgenerierung und Validator-Proof verwenden dieselbe Pipeline wie das übrige typed Authoring.

# XTF

## `generateExampleXtf`

Erzeugt deterministisches Minimal-XTF für sicher materialisierbare Klassen. `maxObjectsPerClass` liegt zwischen 1 und 20. Koordinaten werden aus den tatsächlichen Domain-Grenzen abgeleitet.

## `validateXtf`

Validiert bis zu 20 MiB XTF-Text gegen den vollständigen Modelltext mit ilivalidator und liefert strukturierte Fehler- und Warnungszählungen.
