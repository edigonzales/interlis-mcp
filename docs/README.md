# Dokumentation

Diese Dokumentation beschreibt den **aktuellen** Stand von `interlis-mcp`. Sie ist nicht als Entwicklungsprotokoll oder Roadmap aufgebaut, sondern nach den Fragen gegliedert, die Benutzer, Agenten und Entwickler tatsächlich beantworten müssen.

## Wo finde ich was?

| Dokument | Inhalt | Zielgruppe |
| --- | --- | --- |
| [Benutzerhandbuch](USER_GUIDE.md) | Installation, Client-Konfiguration, Modellkorpus, typische Aufgaben und konkrete Beispiele | Benutzer und Integratoren |
| [Tool-Referenz](TOOL_REFERENCE.md) | MCP-Resources, Prompts und Werkzeuge nach Aufgabe gruppiert | Benutzer, Agent-Entwickler |
| [Agentische Arbeitsabläufe](AGENT_WORKFLOWS.md) | Tool-Hierarchie, Modellierungsabläufe, Sicherheitsgrenzen und Beispiele für agentisches Arbeiten | Agent- und Prompt-Entwickler |
| [Constraints](CONSTRAINTS.md) | Constraint-Semantik, typisiertes Authoring, automatische Proofs, Grenzen und Beispiele | INTERLIS-Modellierer, Entwickler |
| [Architektur](ARCHITECTURE.md) | Systemgrenzen, ili2c-/ilivalidator-Integration, Source-Preservation, Review- und Proof-Pipelines | Entwickler und Architekten |
| [Entwicklerhandbuch](DEVELOPER_GUIDE.md) | Build, Tests, Projektstruktur, Erweiterungsregeln und Dokumentationspflege | Entwickler |

Die Projektwurzel enthält zusätzlich eine kompakte [README](../README.md) als Einstieg.

## Dokumentationsprinzipien

### Aktueller Stand statt Umsetzungsgeschichte

Die Dokumentation beschreibt, **wie das System heute funktioniert**. Sie verweist nicht auf frühere Epics, Implementierungsphasen oder nummerierte Ausbauetappen. Solche Informationen sind für die Benutzung des aktuellen Systems nicht erforderlich und veralten schnell.

Historische Informationen bleiben über Git-Commits und Pull Requests verfügbar. Sie werden nicht zusätzlich als dauerhaftes Entwicklungsprotokoll in `docs/` gepflegt.

### Eine Information hat eine Hauptquelle

- Benutzbare Abläufe gehören ins Benutzerhandbuch oder in die agentischen Arbeitsabläufe.
- Die vollständige Constraint-Semantik gehört in `CONSTRAINTS.md`.
- Interne technische Zusammenhänge gehören in `ARCHITECTURE.md`.
- Exakte MCP-Schemas werden durch Code-Annotationen und Contract-Tests definiert. Markdown-Beispiele erklären diese Schemas, ersetzen sie aber nicht.
- Agentenwirksame Regeln stehen in den MCP-Prompts und MCP-Resources. Die Markdown-Dokumentation erklärt dieselben Regeln für Menschen.

Damit soll vermieden werden, dass dieselbe Tool-Hierarchie in mehreren unabhängigen Promptdateien unterschiedlich weiterentwickelt wird.

### Beispiele sind Teil der Dokumentation

Wann immer ein Verhalten sinnvoll durch ein kleines Beispiel erklärt werden kann, enthält die Dokumentation:

1. die fachliche Aufgabe,
2. das passende Tool,
3. einen realistischen Payload oder INTERLIS-Ausschnitt,
4. die relevanten Ergebnisfelder,
5. den nächsten sinnvollen Schritt.

Beispiele sind bewusst klein. Sie sollen das API-Verhalten erklären und keine fachliche Semantik vortäuschen, die in einem realen Projekt zuerst geklärt werden müsste.

## Umgang mit Spezifikationen und Entwürfen

Temporäre Implementierungspläne, offene Entwürfe und Arbeits-Spezifikationen sollen **nicht dauerhaft auf `main` liegen**.

Empfohlener Umgang:

- Noch nicht umgesetzte Arbeit: GitHub Issue, Pull-Request-Beschreibung oder ein Dokument auf einem Arbeitsbranch.
- Während der Umsetzung: Spezifikation darf auf dem Feature-Branch mitgeführt werden, wenn sie dem Coding-Agenten oder Review dient.
- Nach dem Merge: Dauerhafte fachliche oder technische Aussagen werden in die thematische Referenzdokumentation übernommen; die temporäre Spezifikation wird entfernt.
- Historische Nachvollziehbarkeit: Git-Historie und Pull Request dienen als Archiv.

Wenn eine Architekturentscheidung langfristig mitsamt ihrer Begründung erhalten werden muss, kann dafür künftig ein ADR (`docs/adr/`) angelegt werden. Ein ADR ist eine dauerhafte Architekturentscheidung, kein Arbeitsplan und keine Aufgabenliste. Ein ADR-Verzeichnis soll erst angelegt werden, wenn tatsächlich eine solche Entscheidung dokumentiert werden muss.

## Pflege bei Codeänderungen

Eine Änderung an einem öffentlichen Tool oder Agentenvertrag ist erst vollständig, wenn geprüft wurde, ob mindestens eine dieser Stellen angepasst werden muss:

- Tool-Beschreibung bzw. MCP-Schema,
- `ToolRegistrationContractTest`,
- MCP-Resource oder MCP-Prompt,
- Golden-Scenario- oder E2E-Test,
- Benutzerhandbuch bzw. Tool-Referenz,
- Constraint- oder Architekturreferenz.

Weitere Hinweise dazu stehen im [Entwicklerhandbuch](DEVELOPER_GUIDE.md).
