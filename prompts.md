# Prompting Fuer Agentic INTERLIS Modeling

Dieses Dokument sammelt Prompting-Muster und konkrete Beispiele fuer agentisches INTERLIS-Modellieren mit OpenCode, `@interlis-modeler` und `interlis-mcp`.

## Warum Modeling Anders Ist Als Coding

Agentic Coding funktioniert oft gut, weil Tests, APIs oder UI-Verhalten relativ klare Zielkriterien liefern. Agentic Modeling ist schwieriger, weil das Ziel teilweise fachlich ist:

- Was ist eine Klasse und was nur ein Attribut?
- Welche Kardinalitaet gilt?
- Welche Constraints sind fachlich/rechtlich korrekt?
- Was wird erfasst, was wird abgeleitet, was wird publiziert?
- Welche Information stammt aus diesem Modell und welche aus anderen Quellen?

Der Agent kann technische Konsistenz sehr gut pruefen. Er kann INTERLIS-Code erzeugen, lokale Vorbilder suchen, Modellstruktur analysieren, Regeln pruefen und ili2c-Fehler beheben.

Der Agent darf fachliche Semantik nicht erfinden. Wenn Bedeutung, Kardinalitaet, Rolle, Constraint oder Ableitungslogik unklar sind, muss er Rueckfragen stellen.

## Minimalstruktur Guter Prompts

Ein guter Prompt fuer Modellierung enthaelt moeglichst viele dieser Punkte:

```text
@interlis-modeler
Aufgabe: [neu erstellen | erweitern | reviewen | beraten]
Modellzweck: [CAPTURE | PUBLICATION | VALIDATION | UNKNOWN]
Fachlicher Zweck: [...]
Zielpfad/Datei: [...]
Bekannte Vorbilder: [...]
Bekannte Klassen/Attribute: [...]
Explizite Regeln/Constraints: [...]
Offene Punkte: [...]
Arbeitsmodus: [erst fragen | Vorschlag erstellen | Datei editieren | nur reviewen]
Pruefung: analyzeIliModel, checkModelingRules, validateIliModel
```

## Faustregeln

- Sage explizit, ob du ein `CAPTURE`-, `PUBLICATION`- oder `VALIDATION`-Modell willst.
- Beschreibe die fachliche Frage, die das Modell beantworten soll.
- Liste bekannte Fakten hart auf.
- Liste offene Punkte ebenfalls hart auf.
- Erlaube Rueckfragen, bevor Code erzeugt wird.
- Verlange die Toolkette explizit.
- Verbiete Annahmen, die fachlich kritisch sind.

## Beispiel: Neues CAPTURE-Modell

```text
@interlis-modeler
Ich moechte ein neues INTERLIS-Erfassungsmodell erstellen.

Zweck:
Wir verwalten kommunale Solarpotential-Abklaerungen fuer Gebaeude. Das Modell ist ein CAPTURE-Modell.

Rahmenbedingungen:
- INTERLIS 2.4
- Modellname: SO_ARP_SolarBewilligung_20260428
- Namespace/URI analog zu bestehenden SO_ARP-Modellen in diesem Repository
- Bitte suche zuerst aehnliche Modelle im lokalen Korpus.
- Erstelle noch keine fachlichen Constraints, ausser ich nenne sie explizit.

Bekannte Klassen:
- GebaeudeSolarStatus

Bekannte Attribute:
- egid: Ganzzahl, obligatorisch
- bewilligungsverfahren: Enumeration mit Werten meldeverfahren, baubewilligung, nicht_beurteilt
- bemerkung: Text, optional
- geometrie: Flaeche oder Punkt ist noch offen, bitte Rueckfrage stellen

Vorgehen:
1. Suche passende Vorbilder.
2. Stelle maximal 5 fachliche Rueckfragen.
3. Erstelle danach einen ersten Modellvorschlag.
4. Fuehre analyzeIliModel, checkModelingRules und validateIliModel aus.
```

## Beispiel: Neues PUBLICATION-Modell

```text
@interlis-modeler
Erstelle einen ersten Vorschlag fuer ein flaches PUBLICATION-Modell.

Zweck:
Publikation von Gebaeuden mit Solaranlagen-Bewilligungsverfahren fuer WebGIS und Datendownload.

Vorgaben:
- Keine ASSOCIATION im Publikationsmodell.
- Eine flache Klasse GebaeudeSolarPublikation.
- Attribute:
  - egid: Ganzzahl
  - bewilligungsverfahren: Enumeration
  - gemeinde: Text
  - adresse: Text
  - geometrie: Flaeche, LV95
- Die Werte muessen spaeter aus einem Erfassungsmodell und amtlicher Vermessung ableitbar sein.
- Bitte markiere alle Ableitungsfragen als manuelle Checks.

Bitte suche zuerst aehnliche Publikationsmodelle, erstelle dann das Modell und pruefe es mit analyzeIliModel, checkModelingRules(modelPurpose=PUBLICATION) und validateIliModel.
```

## Beispiel: Bestehende Datei Gezielt Erweitern

```text
@interlis-modeler
Erweitere die Datei models/ARP/SO_ARP_SolarBewilligung_20260428.ili.

Aenderung:
Fuege in der bestehenden Klasse GebaeudeSolarStatus das Attribut gueltig_ab hinzu.

Vorgaben:
- Typ: INTERLIS.XMLDate
- obligatorisch: ja
- Keine weiteren Attribute erfinden.
- Keine neuen Topics erstellen.
- Falls ein Import fehlt, fuege ihn passend ein.

Nach der Aenderung bitte analyzeIliModel, checkModelingRules und validateIliModel ausfuehren und Findings zusammenfassen.
```

## Beispiel: Erst Beraten, Noch Kein Modellcode

```text
@interlis-modeler
Ich weiss noch nicht, wie das Modell geschnitten werden soll.

Fachlicher Zweck:
Wir muessen Fruchtfolgeflaechen erfassen, bewerten und publizieren. Es gibt Flaechen, Qualitaetsangaben, Bewirtschaftungshinweise und einen Status der fachlichen Pruefung.

Bitte erstelle noch kein Modell. Hilf mir zuerst beim Modellierungsentscheid:
- Welche Klassen/Strukturen koennten fachlich sinnvoll sein?
- Welche Informationen gehoeren eher ins Erfassungsmodell und welche ins Publikationsmodell?
- Welche Fragen muss ich fachlich beantworten, bevor ein ILI-Modell geschrieben werden kann?
- Suche lokale Vorbilder und begruende, welche Muster passen.
```

## Beispiel: Vorhandenes Modell Reviewen

```text
/interlis-review
Reviewe models/ARP/SO_ARP_SolarBewilligung_20260428.ili als CAPTURE-Modell.

Pruefe:
- syntaktische Validitaet
- Modellstruktur
- kuratierte Modellierungsregeln
- fehlende Metaattribute
- offene fachliche Fragen

Bitte keine Aenderungen machen, nur Findings und konkrete Verbesserungsvorschlaege liefern.
```

## Beispiel: Sehr Knapp, Aber Noch Brauchbar

```text
@interlis-modeler
Erstelle ein erstes CAPTURE-Modell fuer die Erfassung von Fruchtfolgeflaechen.

Bekannt:
- Es gibt Flaechen mit Geometrie in LV95.
- Jede Flaeche hat eine eindeutige Nummer.
- Es gibt einen Status: in_pruefung, bestaetigt, aufgehoben.

Unklar:
- Welche Qualitaetsattribute fachlich noetig sind.
- Ob Gemeinden als Text oder Referenz modelliert werden sollen.

Bitte zuerst lokale Vorbilder suchen, dann Rueckfragen stellen. Noch keinen finalen Modellcode schreiben.
```

## Was Man Vermeiden Sollte

Schlechter Prompt:

```text
@interlis-modeler
Erstelle mir ein Modell fuer Solaranlagen.
```

Warum schlecht:

- Modellzweck fehlt.
- Fachlicher Zweck ist unklar.
- Keine Zielklasse, Attribute oder Quellen.
- Agent muesste fachliche Semantik erfinden.

Besser:

```text
@interlis-modeler
Hilf mir zuerst beim Zuschnitt eines CAPTURE-Modells fuer Solaranlagen-Bewilligungsverfahren bei bestehenden Gebaeuden.
Bitte suche Vorbilder, schlage moegliche Klassen vor und liste die fachlichen Fragen, die ich beantworten muss. Noch keinen Modellcode schreiben.
```

## Wann Der Agent Fragen Soll

Der Agent soll Rueckfragen stellen, wenn:

- der Modellzweck unklar ist,
- eine Klasse oder ein Attribut fachlich mehrdeutig ist,
- Kardinalitaeten oder Rollen fehlen,
- Constraints gewuenscht sind, aber die fachliche Regel nicht praezise ist,
- eine Geometrieart unklar ist,
- ein Publikationsattribut nicht klar ableitbar ist,
- Kommentare oder Metaattribute nur geraten waeren.

## Wann Der User Fakten Liefern Sollte

Der User sollte Fakten liefern, wenn:

- eine Rechts- oder Fachregel modelliert werden soll,
- Wertebereiche oder Enumerationen fachlich verbindlich sind,
- Rollen und Kardinalitaeten Bedeutung haben,
- ein Attribut aus anderen Datenquellen abgeleitet wird,
- ein Modell als Erfassungs- oder Publikationsmodell dienen soll,
- bestehende lokale Modelle als Vorbild gelten.

## Abschluss Einer Guten Session

Eine gute Session endet mit:

- einem Modellvorschlag oder einer gezielten Datei-Aenderung,
- Resultat von `analyzeIliModel`,
- Resultat von `checkModelingRules`,
- Resultat von `validateIliModel`,
- offenen fachlichen Fragen,
- klaren naechsten Schritten.
