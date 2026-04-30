# Agentic INTERLIS Modeling Workflow

Dieses Dokument beschreibt den praktischen Ablauf, um in VS Code mit OpenCode und `interlis-mcp` INTERLIS-Modelle agentisch zu erstellen, zu erweitern und zu reviewen.

Der aktuelle Fokus ist nur INTERLIS-Modellierung. Schema-Jobs, Datenbankschema-Erzeugung und GRETL-Datenumbau sind bewusst nicht Teil dieses Workflows.

## Rollen Der Komponenten

- VS Code ist die Arbeitsumgebung fuer Dateien, Git-Status, Terminal und OpenCode.
- OpenCode ist der Agent-Orchestrator im Projekt.
- `opencode.json` konfiguriert Modell, Provider, API-Key-Referenz, Berechtigungen und MCP-Server.
- `AGENTS.md` ist die kanonische projektweite Regelbasis fuer INTERLIS-Modellierung.
- `@interlis-modeler` ist der spezialisierte Agent fuer Modellierung, Review und Validierung.
- `/interlis-review` ist ein wiederverwendbarer Command fuer strukturierte Modellreviews.
- `interlis-mcp` liefert MCP-Tools, Resources und Prompts fuer Snippets, Analyse, Regelchecks, Validierung und Korpus-Suche.

## Workspace Starten

1. Oeffne `/Users/stefan/sources/sogis-interlis-repository` als VS-Code-Workspace-Root.
2. Stelle sicher, dass der API-Key in der Shell gesetzt ist:

```bash
export INFOMANIAK_API_KEY="..."
```

3. Starte OpenCode im integrierten VS-Code-Terminal aus dem Repo-Root:

```bash
cd /Users/stefan/sources/sogis-interlis-repository
opencode
```

4. OpenCode liest `opencode.json`, laedt `AGENTS.md` und startet den lokalen `interlis-mcp`-Server ueber Java 21.

## Wann `@` Und Wann `/`

- Verwende `@interlis-modeler`, wenn du mit dem INTERLIS-Spezialisten frei arbeiten willst.
- Verwende `/interlis-review`, wenn du ein vorhandenes Modell gezielt analysieren, regelpruefen und validieren lassen willst.
- Verwende `@interlis-vision-extractor`, wenn die Hauptquelle ein Whiteboard-Foto oder UML-Diagramm-Bild ist.
- Verwende `/whiteboard-to-ili`, wenn du den kompletten 2-Stufen-Flow (Bildextraktion -> bestaetigte INTERLIS-Generierung) ablaufen lassen willst.

Beispiel:

```text
@interlis-modeler Erstelle einen ersten Vorschlag fuer ein CAPTURE-Modell ...
```

Beispiel:

```text
/interlis-review Reviewe models/ARP/SO_ARP_SolarBewilligung_20260428.ili als CAPTURE-Modell.
```

## Standardzyklus Beim Modellieren

Der Agent soll nicht einfach nur Code ausgeben. Er soll iterativ arbeiten:

1. Versionscheck vor jeder Bearbeitung:
   - neue Modelle immer `INTERLIS 2.4;`
   - bestehende `INTERLIS 2.3;` zuerst Softupdate-only (`2.3` -> `2.4`) ohne weitere Aenderung.
2. Nach Softupdate sofort `validateIliModel` ausfuehren.
3. Kontext sammeln: Modellzweck, Fachziel, Zielpfad, vorhandene Modelle, offene Punkte.
4. Lokale Vorbilder suchen, zum Beispiel mit `findSimilarModels`.
5. Fachliche Rueckfragen stellen, wenn Semantik fehlt.
6. Modell erstellen oder bestehende Datei erweitern.
7. `analyzeIliModel` ausfuehren.
8. `checkModelingRules` mit passendem `modelPurpose` ausfuehren.
9. `validateIliModel` ausfuehren.
10. Technisch eindeutige Fehler beheben.
11. Analyse, Regelcheck und Validierung nach Fixes wiederholen.
12. Resultat mit automatisierten Findings, manuellen Checks und offenen Fragen zusammenfassen.

## Foto/UML-Workflow (2 Stufen)

Wenn du mit einem Whiteboard-Foto oder UML-Bild arbeitest, gilt ein gesonderter Ablauf:

1. Stage A mit `@interlis-vision-extractor` oder `/whiteboard-to-ili` starten.
2. Agent liefert einen strukturierten UML-Extraktionsreport.
3. Report muss Unsicherheiten explizit markieren:
   - `Faktisch erkannt`
   - `Annahme`
   - `Offene Frage`
4. Du bestaetigst oder korrigierst den Report.
5. Erst danach Stage B:
   - INTERLIS-Modell erzeugen/aktualisieren via `@interlis-modeler`
   - `analyzeIliModel`
   - `checkModelingRules`
   - `validateIliModel`

Warum: Bildinterpretation ist unsicherer als Textinput. Durch die explizite Zwischenbestaetigung vermeidest du versteckte fachliche Fehlannahmen.

## Modellzweck Festlegen

Setze den Modellzweck im Prompt moeglichst explizit:

- `CAPTURE`: Erfassungs- oder Bearbeitungsmodell, Normalisierung ist wichtig.
- `PUBLICATION`: flaches Publikationsmodell, keine `ASSOCIATION`.
- `VALIDATION`: Hilfsmodell fuer Validierung.
- `UNKNOWN`: nur verwenden, wenn der Zweck wirklich unklar ist.

Der Modellzweck beeinflusst die Regelchecks. Fuer Publikationsmodelle ist zum Beispiel wichtig, dass sie flach bleiben und keine Associations enthalten.

## Erwartete Outputs

Bei Reviews:

1. Blockierende Compiler- oder Validierungsfehler.
2. Automatisierte Regel-Findings.
3. Manuelle Checks und fachliche Rueckfragen.
4. Minimaler naechster Aenderungsvorschlag.

Bei Modell-Aenderungen:

- Geaendertes Modell oder Patch.
- Kurze Zusammenfassung von `analyzeIliModel`.
- Resultat von `checkModelingRules`.
- Resultat von `validateIliModel`.
- Offene fachliche Rueckfragen getrennt von technischen Problemen.

## Erfolgskriterien

Eine gute agentische Modelliersession endet nicht nur mit ILI-Text. Sie endet mit:

- einem nachvollziehbaren Modellstand,
- klarer Modellversion (`INTERLIS 2.4`),
- bei Altmodellen: sauber getrenntem Softupdate-Schritt vor Fachaenderungen,
- validiertem oder klar fehlerhaftem Modell,
- struktureller Analyse,
- Regelcheck-Findings,
- einer Liste offener fachlicher Entscheidungen,
- keiner versteckten fachlichen Annahme.

## Grenzen Des Aktuellen Setups

- Der Agent kann INTERLIS technisch gut unterstuetzen, aber fachliche Modellierungsentscheidungen nicht sicher erfinden.
- Schema-Jobs werden noch nicht automatisch erzeugt.
- GRETL-Datenumbau ist noch nicht Teil dieses Workflows.
- SQL-QA ist als spaeterer Schritt vorgesehen, aber hier nicht aktiv.
- Vision-Auswertung haengt stark von Bildqualitaet, Perspektive und Lesbarkeit ab; Unsicherheiten muessen immer sichtbar gemacht werden.

## Praktische Arbeitsweise

Fuer neue Modelle zuerst mit einem Beratungs- oder Kontextprompt starten. Danach erst Code erzeugen lassen.

Fuer bestehende Modelle zuerst reviewen lassen. Danach gezielte kleine Aenderungen beauftragen.

Fuer Publikationsmodelle immer `modelPurpose=PUBLICATION` verlangen.

Fuer Erfassungsmodelle bewusst fragen, welche Normalisierung fachlich sinnvoll ist.

Wenn der Agent eine fachliche Annahme macht, den Prompt schaerfen und die Annahme explizit bestaetigen oder verbieten.
