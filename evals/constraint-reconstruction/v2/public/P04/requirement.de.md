# Fachanforderung P04

Für jede Leitung in der Validierungssicht gilt: Ist die über RohrprofilRef erreichte Bezeichnung definiert und nicht Kiesschlitzdrainage, ist die Regel erfüllt. Fehlt der Bezug oder seine Bezeichnung, wird die Prüfung ohne Verletzung abgebrochen.

Bei Kiesschlitzdrainage werden diese vier Angaben in genau dieser Reihenfolge geprüft: FunktionHierarchisch muss SAA.andere sein, FunktionHydraulisch muss Sickerleitung sein, Nutzungsart_Ist muss Reinabwasser sein und Profiltyp muss Spezialprofil sein.

Ein passender definierter Wert lässt die Prüfung zur nächsten Angabe weitergehen. Ein abweichender definierter Wert verletzt die Regel und beendet die Prüfung. Ein undefinierter Wert beendet die Prüfung ohne Verletzung; spätere Angaben werden dann nicht mehr ausgewertet, auch wenn sie abweichen. Sind alle vier Angaben passend, ist die Regel erfüllt. Damit ist die Reihenfolge ausdrücklich Teil der Anforderung und entspricht dem im Benchmark festgelegten Validatorverhalten.
