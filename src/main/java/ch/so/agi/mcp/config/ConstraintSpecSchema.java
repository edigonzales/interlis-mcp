package ch.so.agi.mcp.config;

import ch.so.agi.mcp.model.ConstraintSpecContract;
import ch.so.agi.mcp.model.EnumLiteralValue;
import ch.so.agi.mcp.model.IliConstraintSpec.ExpressionKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compact input schemas using the same shapes as server-side validation. */
final class ConstraintSpecSchema {
  private ConstraintSpecSchema() {}

  static void enrich(Object node) {
    if (node instanceof List<?> list) { list.forEach(ConstraintSpecSchema::enrich); return; }
    if (!(node instanceof Map<?, ?> raw)) return;
    @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) raw;
    if (map.get("properties") instanceof Map<?, ?> properties) {
      Object kind = properties.get("kind");
      if (kind instanceof Map<?, ?> discriminator
          && discriminator.get("enum") instanceof List<?> values
          && values.size() == ExpressionKind.values().length
          && values.containsAll(List.of("ATTRIBUTE", "FUNCTION", "OBJECT_COUNT", "MTEXT"))) {
        @SuppressWarnings("unchecked") Map<String, Object> props = (Map<String, Object>) properties;
        expression(map, props);
        return;
      }
      if (kind instanceof Map<?, ?> discriminator && discriminator.get("const") instanceof String value) {
        @SuppressWarnings("unchecked") Map<String, Object> props = (Map<String, Object>) properties;
        if (Set.of("MANDATORY", "UNIQUE", "EXISTENCE", "PLAUSIBILITY", "SET").contains(value)) {
          map.put("additionalProperties", false);
          property(props, "name").putAll(Map.of(
              "description", "Technischer Name, z.B. Regel42. Fachliche Regelnummern können in iliDoc stehen.",
              "pattern", "^\\s*" + ConstraintSpecContract.IDENTIFIER + "\\s*$"));
        }
        switch (value) {
          case "UNIQUE" -> {
            property(props, "keyPaths").put("minItems", 1);
            map.put("if", Map.of("properties", Map.of("scope", Map.of("const", "LOCAL")), "required", List.of("scope")));
            map.put("then", Map.of("required", List.of("localPrefix")));
          }
          case "EXISTENCE" -> property(props, "requiredIn").put("minItems", 1);
          case "PLAUSIBILITY" -> property(props, "percentage").putAll(Map.of("minimum", 0, "maximum", 100));
          case "OBJECT_COUNT" -> {
            map.put("additionalProperties", false);
            property(props, "operator").put("enum", operators());
          }
          case "BOOLEAN_EXPRESSION", "ALL", "PATH" -> map.put("additionalProperties", false);
          default -> { }
        }
      }
    }
    new ArrayList<>(map.values()).forEach(ConstraintSpecSchema::enrich);
  }

  private static void expression(Map<String, Object> node, Map<String, Object> original) {
    List<Object> alternatives = new ArrayList<>();
    for (ExpressionKind kind : ExpressionKind.values()) {
      var rule = ConstraintSpecContract.shape(kind);
      Map<String, Object> props = new LinkedHashMap<>();
      props.put("kind", Map.of("const", kind.name()));
      for (String field : rule.required()) {
        @SuppressWarnings("unchecked") Map<String, Object> source = (Map<String, Object>) original.get(field);
        Map<String, Object> value = new LinkedHashMap<>(source);
        if (field.equals("name")) value.put("description", switch (kind) {
          case FUNCTION -> "STANDARD nutzt die semanticId aus listConstraintFunctions (z.B. COLLECTION_SUM, NUMERIC_ADD). "
              + "COLLECTION_SUM erhält einen PATH-Knoten als Kind. MODEL und VALIDATOR_EXTENSION erwarten einen qualifizierten Funktionsnamen.";
          case PATH -> "Pfad zum Wert, z.B. Rolle->Attribut.";
          default -> "Attributname.";
        });
        if (field.equals("operator")) value.put("enum", operators());
        if (kind == ExpressionKind.ENUM && field.equals("value")) {
          value.put("type", "string");
          value.put("pattern", "^\\s*#?" + EnumLiteralValue.PATTERN + "\\s*$");
          value.put("examples", List.of("Drainage", "#Drainage", "Gruppe.Drainage"));
        }
        if (field.equals("value")) {
          switch (kind) {
            case BOOLEAN -> value.put("type", "boolean");
            case TEXT, MTEXT -> value.put("type", "string");
            case NUMERIC -> value.put("type", "number");
            default -> { }
          }
        }
        if (field.equals("objects")) {
          value.put("description", "Objektmenge ALL oder PATH. Vergleiche die Anzahl in einem umgebenden COMPARE.");
          value.put("examples", List.of(Map.of("kind", "ALL"), Map.of("kind", "PATH", "path", "role->target")));
        }
        props.put(field, value);
      }
      @SuppressWarnings("unchecked") Map<String, Object> sourceChildren = (Map<String, Object>) original.get("children");
      Map<String, Object> children = new LinkedHashMap<>(sourceChildren);
      children.put("minItems", rule.minChildren());
      if (rule.maxChildren() != Integer.MAX_VALUE) children.put("maxItems", rule.maxChildren());
      if (rule.maxChildren() > 0) children.put("description", "Geordnete Operanden oder Funktionsargumente.");
      props.put("children", children);
      List<String> required = new ArrayList<>(List.of("kind"));
      required.addAll(rule.required());
      if (rule.minChildren() > 0) required.add("children");
      // Select before descending into children: unrelated branches otherwise multiply
      // recursive validation diagnostics for a single malformed leaf.
      alternatives.add(Map.of(
          "if", Map.of("properties", Map.of("kind", Map.of("const", kind.name())),
              "required", List.of("kind")),
          "then", Map.of("type", "object", "properties", props, "required", required,
              "additionalProperties", false)));
    }
    node.clear();
    node.put("type", "object");
    node.put("required", List.of("kind"));
    node.put("properties", Map.of("kind", Map.of("enum",
        java.util.Arrays.stream(ExpressionKind.values()).map(Enum::name).toList())));
    node.put("allOf", alternatives);
    node.put("description", "Rekursiver typisierter Ausdruck.");
  }

  private static List<String> operators() {
    return ConstraintSpecContract.COMPARISON_OPERATORS.stream().sorted().toList();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> property(Map<String, Object> properties, String name) {
    return (Map<String, Object>) properties.get(name);
  }
}
