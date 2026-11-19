# Tool-Referenz

Diese Referenz beschreibt die öffentliche MCP-Oberfläche. Die registrierten JSON-Schemas sind über `tools/list` abrufbar. Modell-Repositories werden serverseitig mit `interlis.mcp.model-repositories` konfiguriert und sind bewusst kein Parameter jedes einzelnen Tools.

## Auswahlregel

Verwende das höchste Tool, das die Aufgabe vollständig abdeckt. Ein High-Level-Review soll nicht routinemässig durch dieselben Low-Level-Prüfungen wiederholt werden. Der Server trifft keine fachlichen Annahmen zu Kardinalitäten, Constraints, Koordinatensystemen oder Metadaten.

## Resources und Prompts

`interlis://knowledge/so-data-rules` liefert den Regelkatalog für SO-DATA.

Die Resources `interlis://knowledge/agent-workflow`, `interlis://knowledge/handbook-rules` und `interlis://knowledge/model-corpus-index` stellen Regeln und Abläufe bereit. Die Prompts `interlis-modeling-agent`, `review-interlis-model`, `extend-interlis-model` und `author-interlis-constraint` übersetzen sie in konkrete Agentenaufträge.

# Ergebnisvertrag für Authoring und Verifikation

`status` gibt den Workflow-Ausgang an. Geprüfte Modelltexte erscheinen je nach Ausgang
als `updatedModelText` oder `candidateModelText`. `verifyIliModel` identifiziert den
eingereichten Text über dessen Hash. `beforeCompilation` und `afterCompilation`
enthalten die tatsächlich ausgeführten Compilerphasen. Der Diff steht im Authoring
unter `semanticDiff`. `reviewIliChange` liefert seine Diff-Felder direkt.

`constraintProofs` nennt den Ausgang, die Coverage und die Validatorfälle je erforderlichem
Constraint. Ein leeres Array weist einen ermittelten leeren Prüfumfang aus. Fehlt das Feld,
wurde die Proof-Phase noch nicht erreicht. `constraintInteractions` zeigt beratende
Befunde zu unterstützten skalaren Mandatory-Regeln in Reviews und `afterReview`.
Erfolgreiche, ausgeübte und warnungsfreie Test-XTFs mit gültiger Fixture werden
standardmässig ausgelassen. `includeSuccessfulTestXtf=true` liefert auch diese Texte.
Weitere Details stehen im [Benutzerhandbuch](USER_GUIDE.md#prüfnachweise-und-ihre-grenzen).

# Reviews und Änderungen

## `reviewIliModel`

High-Level-Gate für einen vollständigen aktuellen Modellstand. Eingaben sind `modelText` sowie optional `modelPurpose` und `ruleProfile`. Die Antwort kombiniert Compilerdiagnostik, Struktur, automatisierte Regeln, manuelle Checks und offene Fragen.

## `reviewIliChange`

Vergleicht `beforeModelText` und `afterModelText` semantisch. Views sowie die deklarierten Typen von Domains und Attributen gehören zum Diff. Optional steuern `modelPurpose` und `ruleProfile` den Abschlussreview des Nachher-Stands.

## `authorIliModel`

Erzeugt ein vollständiges Modell aus einer typisierten `IliModelSpec`. Name, URI, Modellversion und INTERLIS-Version sind explizit. Units, Domains, Topics, Klassen, Strukturen, Assoziationen, Attribute, Geometrien und alle fünf Constraint-Arten werden in einem Compile geprüft; AST-Generierung, Constraint-Proofs und `afterReview` verwenden denselben kompilierten Kontext.
Die boolesche Angabe `semanticDiff.astGenerated` bestätigt die Regenerierung aus dem Modell-AST.
Einzelconstraint-Authoring meldet den strukturellen Abgleich mit `semanticDiff.astRoundTripVerified`.

## `applyIliModelChanges`

Wendet einen atomaren Batch source-preserving an. Unterstützt `ADD_IMPORT`, `ADD_TOPIC`, `ADD_DOMAIN`, `ADD_UNIT`, `ADD_CLASS`, `ADD_STRUCTURE`, `ADD_ASSOCIATION`, `ADD_ATTRIBUTE`, `UPDATE_ATTRIBUTE`, `REMOVE_ATTRIBUTE`, `ADD_CONSTRAINT` und `REMOVE_COMMENTS`. Der Batch nutzt je eine Before- und After-Kompilierung und prüft alle in diesem Batch neu deklarierten Constraints. Potenziell brechende Änderungen werden ohne `allowPotentiallyBreaking=true` nur als geprüfter Kandidat zurückgegeben.

## `renameModelElement`

Benennt ein Modellelement über das ili2c-Metamodell um und regeneriert den Modelltext. Optional begrenzt `expectedKind` die zulässige Elementart. Die Operation ist semantisch robust, aber nicht layout-erhaltend.

# Analyse und Regeln

## `validateIliModel`

Fokussierte ili2c-Compilerdiagnostik für `modelText`. Diagnosen des eingereichten Modells verwenden den stabilen Dateinamen `<submitted-model>` und können einen `sourceExcerpt` enthalten.

## `analyzeIliModel`

Liefert die kompilierte Modellstruktur, darunter Modelle, Topics, Klassen, Strukturen, Views, Beziehungen, Domains, Units, Attribute, Vererbung und Abhängigkeiten. `modelPurpose` ist optional.

# Modellkorpus

Die Resource `interlis://knowledge/model-corpus-index` inventarisiert die Dateien aus
`interlis.knowledge.model-paths`.

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

Löst einen bekannten Objekt-/Attributpfad im kompilierten Kontext auf und liefert Schritte,
Kardinalitäten, Optionalität, Zieltyp und technische Verwendungshinweise. Dieselbe Auswertung
wird von `findConstraintPaths` verwendet. Bestehende Eingaben und Ergebnisfelder bleiben erhalten.

## `findConstraintPaths`

Sucht Pfade vom `context` zum `targetFqn` im übergebenen `modelText`. Das Ziel ist der genaue
FQN eines Attributes oder deklarierten Objekttyps (Klasse, Struktur, Association oder View).
Geerbte Attribute können über ihre Deklaration oder einen erbenden Kontext adressiert werden.
Ein geerbter FQN wie `Child.value` bleibt auf diesen Kontext und dessen Unterklassen begrenzt;
überschriebene Attribute besitzen ihre eigene effektive Deklaration.

`maxDepth` zählt alle Schritte einschliesslich Endattribut (Standard 3, zulässig 1–8).
`limit` begrenzt Treffer (Standard 10, zulässig 1–50); ungültige Werte werden abgelehnt.
Pro Aufruf werden höchstens 2’000 Pfadpräfixe geprüft. Das Modell wird einmal kompiliert;
alle angebotenen Pfade sind mit dem ili2c-Pfadparser geprüft und nach Länge, dann Pfadtext sortiert.
`modelHashes.model` bindet die Antwort an den übergebenen Modelltext.

`paths` enthält je Pfad die Felder von `resolveConstraintPath`, insbesondere:

- `steps`: Element-FQN, Ziel/Typ, lokale Kardinalität und `optional` je Schritt.
- `collection`: mindestens ein Schritt erlaubt mehrere Werte/Objekte.
- `mayBeUndefined`: mindestens ein optionaler Schritt; berücksichtigt nur deklarierte
  Kardinalitäten/Optionalität. Zusätzliche Constraints oder berechnete Ausdrücke werden nicht ausgewertet.
- `usageHints`: `SINGLE_VALUE`, `SUM` mit `COLLECTION_SUM` oder `OBJECT_COUNT`, jeweils mit
  einem wiederverwendbaren `expression`-Baustein. Leere Hinweise bedeuten keine Unterstützungsaussage.
- `proofStatus=NOT_RUN`: Pfadauflösung ist kein Nachweis für einen vollständigen Constraint.

`search` nennt Grenzen, untersuchte Präfixe, `truncated`, `reasonCodes` und
`completeWithinBounds`. Letzteres bezeichnet nur die unterstützte Suchform innerhalb von
`maxDepth`; ein Tiefenabbruch kann daher gleichzeitig `truncated=true` melden.
Treffer-/Arbeitsbudget, Parserablehnungen und nicht unterstützte Navigationen setzen
`completeWithinBounds=false`. Höchstens 20 Beispiele nicht unterstützter Präfixe werden
mitgeliefert, daneben deren Gesamtzahl. Fehlerhafte Modelle, Kontexte oder Ziele liefern
`status=UNAVAILABLE`; eine reguläre Suche liefert `AVAILABLE`, auch ohne Treffer.

Die Suche folgt deklarierten Rollen, Referenzen und Strukturen mit einfachen `->`-Schritten.
Sie erfindet keine Downcasts, Unterklassenpfade, View-Basisaliase oder Strukturindizes.
Mehrere deklarierte Ziele derselben Rolle werden als `MULTIPLE_ROLE_TARGETS_UNSUPPORTED`
ausgewiesen. Zyklen dürfen als endliche Pfade vorkommen. Keine Treffer beweisen keine
allgemeine Unerreichbarkeit; der Agent entscheidet anhand der Fachregel zwischen Alternativen.

Verwendung: **Pfad suchen → Ausdruck erstellen → bestehendes Authoring und Validatorprüfung**.
Numerische Sammelpfade können Summen bilden; leere Mengen und fehlende Werte benötigen eigene
Erwartungen. Objektzählungen betreffen Klassenobjekte und Pfadvorkommen, keine implizite
DISTINCT-Zählung. Strukturen erhalten keinen `OBJECT_COUNT`-Hinweis.

## `reviewIliConstraint`

Erklärt einen vorhandenen Constraint aus dem ili2c-AST. Das Resultat enthält Kontext, Pfade, Funktionen, Typen und strukturelle Randfälle, aber keine automatisch erzeugten Testdaten.

## `generateIliConstraintCases`

Erzeugt und verifiziert modellbewusste Witnesses, Counterexamples, Grenz- und Scope-Fälle für die unterstützte MANDATORY-, UNIQUE-, EXISTENCE-, PLAUSIBILITY- und SET-Semantik. `coverageUnsolved` und Safety-Codes sind bewusste Grenzen.

## `testIliConstraint`

Validiert höchstens 100 explizit vorgegebene Testfälle. Das Tool ist für fachliche oder gezielte Regressionstestdaten gedacht, nicht als redundante Wiederholung eines erfolgreichen automatischen Proofs.

## `authorIliConstraint`

Ergänzt einen Constraint quelltexttreu und liefert Compilerphasen, automatischen
Proof, semantischen Diff und `afterReview`. Erforderlich sind `modelText`,
`contextFqn` und `spec` mit `kind` und technischem `name`.
`modelPurpose`, `ruleProfile` und `includeSuccessfulTestXtf` sind optional.

| `spec.kind` | Pflichtfelder der Variante |
| --- | --- |
| `MANDATORY` | `condition` als rekursiver Ausdruck |
| `UNIQUE` | `scope` und `keyPaths`, bei LOCAL auch `localPrefix`. Optional `where` |
| `EXISTENCE` | `restrictedPath` und `requiredIn` mit `viewableFqn` und `attributePath` je Ziel |
| `PLAUSIBILITY` | `direction`, `percentage` und `condition`. Der Proof verwendet echte Populationen |
| `SET` | `scope` und `condition` vom Typ `OBJECT_COUNT` oder `BOOLEAN_EXPRESSION`. Optional `where` |

Fehlende Proofs und nicht ausführbare externe Funktionssemantik halten den
Kandidaten zurück. [Eingabebeispiele und Proof-Grenzen](CONSTRAINTS.md).

# XTF

## `generateExampleXtf`

Jeder erzeugte Transfer wird einmal validiert. Das zusätzliche `validation` enthält `status`
(`VALID`, `INVALID`, `ERROR`, `NOT_RUN`), nullable `valid`, Fehler-/Warnungsanzahl, Diagnosen,
`scope` und `limitation`. `generated` bezeichnet weiterhin die Erzeugung; auch ungültiges XTF
bleibt verfügbar. Keine zusätzliche routinemässige Validierung desselben Transfers nötig.

Erzeugt deterministisches Minimal-XTF für sicher materialisierbare Klassen. `maxObjectsPerClass` liegt zwischen 1 und 20. Koordinaten werden aus den tatsächlichen Domain-Grenzen abgeleitet.

## `validateXtf`

Validiert bis zu 20 MiB XTF-Text gegen den vollständigen Modelltext mit ilivalidator und liefert strukturierte Fehler- und Warnungszählungen.

## SO-DATA: Profil und Kommentarentfernung

Alle Profilparameter akzeptieren `CORE`, `SO-GIS` und `SO-DATA`; Default bleibt CORE.
Das frühere `SO` ist kein zulässiger Eingabewert. Parser und Werkzeugschemas
akzeptieren ausschliesslich `CORE`, `SO-GIS` und `SO-DATA`.
`interlis://knowledge/so-data-rules` liefert den vollständigen Regelkatalog.
`REMOVE_COMMENTS` benötigt keine zusätzliche Payload:

```json
{"changes": [{"operation": "REMOVE_COMMENTS"}]}
```

Dieser Batch wird nur auf ausdrücklichen Änderungsauftrag über
`applyIliModelChanges` angewendet; Reviews sind read-only. `sourceEdits` zeigt die
Entfernung und gegebenenfalls native Constraint-Namen. Überlappende Änderungen
werden zurückgewiesen und benötigen getrennte geprüfte Batches. Die bisherigen
Gates für potenziell brechende Änderungen bleiben bestehen.
[Profilregeln und Beispiele](USER_GUIDE.md#so-data-csv-validierungsmodelle).

## `verifyIliModel`

Prüft unveränderten `modelText` mit optionalem `modelPurpose` und `ruleProfile`.
Der Compiler, die Modellierungsregeln und alle im eingereichten Modell deklarierten
Constraints werden geprüft. `VERIFIED` bedeutet vollständige automatische Nachweise;
es ist keine fachliche Freigabe. Die zurückgegebenen Modellbytes bleiben unverändert,
`modelHashes` bindet sie an das Ergebnis. Unvollständige Nachweise behalten ihre
Constraint-spezifischen Ursachen. Gleichnamige Constraints werden zurückgewiesen.

Nach einer Erweiterung der Proof-Engine dieses Tool verwenden, statt einen bereits
vorhandenen Constraint erneut mit `ADD_CONSTRAINT` einzufügen. XMLDate-Schlüssel
werden anhand ihrer Kalenderstruktur und Grenzen geprüft; beliebige formatierte
Typen, XMLTime und XMLDateTime erhalten dadurch keine pauschale Unterstützung.
