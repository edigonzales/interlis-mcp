package ch.so.agi.mcp.model;

/** Small shared guidance, visible without loading an optional MCP prompt/resource. */
public final class ConstraintAuthoringGuidance {
  private ConstraintAuthoringGuidance() {}
  public static final String MANDATORY_SPEC_EXAMPLE = "{\"kind\":\"MANDATORY\",\"name\":\"ValueRequired\",\"condition\":{\"kind\":\"DEFINED\",\"children\":[{\"kind\":\"ATTRIBUTE\",\"name\":\"value\"}]}}";
  public static final String MANDATORY_INPUT = "MANDATORY: Der Ausdruck steht in spec.condition; Operanden stehen geordnet in children. Beispiel fuer spec bei vorhandenem Attribut value: " + MANDATORY_SPEC_EXAMPLE;
  public static final String NAME = "Technischer Constraint-Name: [A-Za-z][A-Za-z0-9_]*, z.B. Regel42. Eine fachliche Regelnummer ist kein technischer Name; keine automatische Umbenennung.";
  public static final String ENUM = "ENUM.value ist ein String, z.B. Drainage oder #Drainage, auch Gruppe.Drainage. Genau ein optionales fuehrendes # wird normalisiert; TEXT/MTEXT bleiben unveraendert.";
  public static final String FUNCTION = "FUNCTION: name ist bei functionOrigin=STANDARD die semanticId aus listConstraintFunctions, z.B. COLLECTION_SUM statt Math.sum und NUMERIC_ADD statt Math.add. Bei MODEL/VALIDATOR_EXTENSION ist name der qualifizierte Funktionsname. Argumente stehen ausschliesslich geordnet in children, niemals in FUNCTION.objects.";
  public static final String COUNT = "OBJECT_COUNT als Ausdruck liefert eine Zahl: {\"kind\":\"COMPARE\",\"operator\":\"==\",\"children\":[{\"kind\":\"OBJECT_COUNT\",\"objects\":{\"kind\":\"ALL\"}},{\"kind\":\"NUMERIC\",\"value\":1}]}. Nur die separate SET-Condition OBJECT_COUNT hat objects, operator und threshold; sie hat kein value.";
  public static final String INPUT = ch.so.agi.mcp.knowledge.ConstraintWorkflowGuidance.TOOL + " " + NAME + " " + ENUM + " " + FUNCTION + " " + COUNT
      + " Ergebnis evidence trennt ausgeführte Prüfungen von fachlicher Akzeptanz; neue Befunde ändern keine Freigaben.";
  public static final String WORKFLOW = """

        Eingabevertrag fuer typisiertes Constraint-Authoring:
        - %s
        - %s
        - %s
        - %s
        - %s
        - DEFINED/NOT haben ein Kind, COMPARE/IMPLIES zwei; AND/OR behalten die Reihenfolge ihrer children.
        - Entscheidungstabellen: defined=true/false bezeichnet nur SUM-Praesenz und benoetigt aggregate=SUM, ohne operator/value/addAttribute. Fuer direkte Attribute DEFINED im typisierten Authoring verwenden.
        - specDiagnostics enthält code, JSON-Pointer path, message und hint; Behandlung gemäss gemeinsamem Prosa-Workflow.
        """.formatted(MANDATORY_INPUT, NAME, ENUM, FUNCTION, COUNT);
}
