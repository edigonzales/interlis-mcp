# Fachliche Freigabe der Suite v2

Status: **APPROVED durch den Benutzer am 2026-09-08**. v1 und suite.json bleiben bis zu drei vollständigen nativen Abnahmerunden unverändert aktiv.

## Änderungen zur Prüfung

| Fall | Bisher | Vorgeschlagene Klarstellung |
|---|---|---|
| P04 | Jeder abweichende definierte Folgewert erzeugt eine Verletzung, unabhängig von der Position. | Prüfung in der Reihenfolge FunktionHierarchisch, FunktionHydraulisch, Nutzungsart_Ist, Profiltyp. Der erste undefinierte Vergleich beendet die Auswertung ohne Verletzung, auch wenn ein späterer Wert falsch wäre. |
| P05 | Mehr als eine Verknüpfung erscheint als normaler negativer Constraint-Fall. | Die Rolle erlaubt maximal eine Verknüpfung. Null/eins sind die zulässigen Constraint-Fälle; zwei Verknüpfungen sind ein struktureller Datenfehler. |

Die Originalconstraints und Eingabemodelle bleiben in beiden Fällen unverändert. P04 beschreibt das geordnete Verhalten des festgelegten ilivalidator/iox-ili-Stacks. P05 trennt den Constraint von der schon vorher geltenden Rollenkardinalität. Die übrigen zehn Fachanforderungen bleiben bytegleich zu v1.

Die fachliche Besprechung bestätigte für P04 ausdrücklich: keine Änderung des vorhandenen Constraints. Die geordnete Auswertung entspricht INTERLIS. Alle vier betroffenen Attribute sind im Basismodell optional. In den separaten AFU-IPW-S1-/S2-Validierungsmodellen fordert Regel 12009 DEFINED(FunktionHydraulisch), allerdings nur in der Sicht für Leitungen in Betrieb. Diese Prüfmodelle gehören nicht zu den Imports von P04. Der isolierte Benchmark behauptet daher weder, diese zusätzliche Prüfung auszuführen, noch fehlende Angaben generell auszuschließen. Der Benutzer akzeptiert die bestehende Aufteilung der fachlichen Prüfungen. Die v2-Anforderung beschreibt die unveränderte Goldlösung korrekt, ohne ihr zusätzliche Pflichten zu geben.

## Technische Änderungen ohne neue Fachanforderung

- Ein frischer Reconstructor pro Fall verhindert Zugriff auf Goldlösungen anderer Fälle.
- Feste, separat bewertete MCP-Requests; keine Übernahme eines fehlerhaften historischen Requests als Goldstandard.
- Unabhängige vollständige Compiler-ASTs einschließlich Objektmengen und Existenz-Zielklassen.
- AND/OR bleiben geordnet; keine pauschale Kommutativität. P01 erlaubt alternativ einen vollständigen Nachweis über 13 Körnungsklassen und alle 101 zulässigen ganzzahligen Tongehalte.
- Revision 2.0.2 ergänzt einen expliziten lokalen Äquivalenznachweis: Eine reine Permutation unveränderter AND-/OR-Operanden ist zulässig, wenn jeder betroffene Operand nachweislich total boolesch ist. Numerische Vergleiche verlangen direkte Pflichtattribute aus dem unabhängigen Compiler-Modell; SUM-Werte, optionale Attribute und Referenzpfade werden nie als total vorausgesetzt. Der Nachweis enthält die betroffenen AST-Pfade und Compiler-Fakten. Fehlende Belege bleiben `null`/`NOT_PROVEN` und sperren den Gesamtscore.
- Lokale, gehashte Importmodelle und eine nachweisbare Laufzeitidentität.
- Ein Compilerfehler erhält niemals einen Punkt für korrekt erkannte externe Semantik.

Die ausdrückliche Zustimmung ist in approval.json dokumentiert. Manifest und Freigabe werden gemeinsam versioniert. Erst nach den drei vollständigen Abnahmerunden wird suite.json auf v2 umgestellt und die bestehende Automation aktualisiert. Historische Berichte bleiben unverändert.
