Rekonstruiere genau einen INTERLIS-Constraint aus der Fachanforderung. Du erhältst einen neuen Kontext ohne andere Fälle.

Deine einzigen lesbaren Dateipfade sind {{INPUT}} (model.ili, requirement.de.md, hashes.json) und dein eigener Ausgabeordner {{OUTPUT}}. Lies keine anderen Verzeichnisse, AGENTS-/Skill-Dateien, Repositories, Git-Historie, Modell-Caches, vorherigen Runs, Sitzungsprotokolle oder Web. Führe keine Repositorysuche aus. Die Modelle können normale vorhandene Constraints enthalten; verwende ausschließlich den zugewiesenen Fall. Verlasse diese Pfade auch bei Fehlern nicht.

Lies beide Eingabedateien vollständig und prüfe SHA-256 gegen hashes.json. Wähle selbst Kontext, Constraint-Art und High-Level-Werkzeug anhand der nativen Werkzeugbeschreibungen. Verwende ausschließlich mcp__interlis_mcp_eval__authorIliMandatoryConstraint, authorIliPlausibilityConstraint, authorIliExistenceConstraint, authorIliSetConstraint, authorIliUniqueConstraint oder generateIliConstraintFromDecisionTable. listConstraintFunctions und resolveConstraintPath desselben Connectors sind für modellbezogene Klärung erlaubt. Keine handgeschriebene Constraint-Syntax, kein Textpatch, keine Low-Level-Reparatur, kein eigener MCP-Prozess oder Client.

Rufe zuerst über den unten beschriebenen Recorder den nativen listConstraintFunctions-Canary auf. Der Recorder speichert sein vollständiges Rohresultat in runtime-canary.json. Die Hauptaufgabe prüft dessen Identität. Berücksichtige die Payload-Verträge einschließlich der rekursiven spec/kind-Struktur, Funktionstypen und Entscheidungszeilen. Lasse nur einen Authoring-Versuch zu. Ein fachlich negatives Ergebnis wird nicht repariert. Bei einem eindeutig transienten Transportfehler ist genau ein Retry mit unverändertem Payload erlaubt; beide Versuche bleiben sichtbar.

Verwende für JEDEN nativen MCP-Aufruf den unveränderten Recorder in deinem Ausgabeordner. Er enthält keinerlei fachliche Lösung, ergänzt oder repariert keine Payloads und ruft ausschließlich die nativen Codex-Werkzeuge auf. Er speichert Requests vor dem Aufruf und vollständige Rohresultate, Zeitpunkte sowie gelieferte Kandidaten unmittelbar danach. Er verhindert einen zweiten Authoring-Versuch und wiederholt bei einem eindeutigen Transportfehler einmal exakt denselben Request. Programmiere keine eigene Protokollierung und rufe MCP-Werkzeuge nicht direkt am Recorder vorbei auf.

Lade den Recorder VOR DEM ERSTEN MCP-AUFRUF in einer functions.exec-Zelle:

```javascript
const r = await tools.exec_command({cmd: "cat '{{OUTPUT}}/native-recorder.js'", max_output_tokens: 6000});
if (r.exit_code !== 0) throw new Error(r.output);
store("nativeRecorder", r.output);
```

Rufe dann als erstes natives Werkzeug den Canary auf:

```javascript
const raw = await eval(load("nativeRecorder"))("mcp__interlis_mcp_eval__listConstraintFunctions", {});
text(raw);
```

Benutze denselben Aufrufmechanismus anschließend für deinen selbst erstellten vollständigen Authoring-Payload: `await eval(load("nativeRecorder"))(vollstaendigerWerkzeugname, payload)`. Das payload-Objekt muss dem nativen Vertrag entsprechen und den vollständigen unveränderten Eingabemodelltext enthalten. Speichere bei Bedarf lokale JS-Werte mit store/load zwischen functions.exec-Zellen; TextEncoder, btoa, Node.js-fs und Netzwerkzugriffe stehen in dieser Zelle nicht zur Verfügung. tools.exec_command darf ausschließlich deine erlaubten Dateien lesen und deinen Ausgabeordner beschreiben.

Genau ein Authoring-Versuch. Ein fachlich negatives Ergebnis wird nicht repariert. Der Recorder schreibt request.json, raw-result.json, candidate.ili falls geliefert, calls/NN/* und completion.json automatisch. Bei einem Recorder-/Transportfehler oder fehlenden Belegen melde EVIDENCE_INCOMPLETE; erfinde, rekonstruiere oder rückdatiere keine Belege. Bewerte den Fall nicht selbst. Gib an die Hauptaufgabe nur den tatsächlichen Status und die Artefaktpfade zurück. Die Hauptaufgabe archiviert den unveränderten vollständigen Rollenverlauf und prüft die Isolation unabhängig.
