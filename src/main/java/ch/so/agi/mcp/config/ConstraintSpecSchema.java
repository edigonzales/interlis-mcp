package ch.so.agi.mcp.config;

import ch.so.agi.mcp.model.ConstraintAuthoringGuidance;
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
      if (kind instanceof Map<?, ?> discriminator
          && Set.of("MANDATORY", "UNIQUE", "EXISTENCE", "PLAUSIBILITY", "SET")
              .contains(String.valueOf(discriminator.get("const")))) {
        describe(properties.get("name"), ConstraintAuthoringGuidance.NAME);
      }
      describe(properties.get("constraintName"), ConstraintAuthoringGuidance.NAME);
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
        if (field.equals("name")) value.put("description", kind == ExpressionKind.FUNCTION
            ? ConstraintAuthoringGuidance.FUNCTION : "Attributname bzw. semantischer Pfad; Assoziationen verwenden ->.");
        if (field.equals("functionOrigin")) value.put("description", ConstraintAuthoringGuidance.FUNCTION);
        if (field.equals("operator")) value.put("enum", ConstraintSpecContract.COMPARISON_OPERATORS.stream().sorted().toList());
        if (kind == ExpressionKind.ENUM && field.equals("value")) {
          value.put("type", "string");
          value.put("pattern", "^\\s*#?" + EnumLiteralValue.PATTERN + "\\s*$");
          value.put("description", ConstraintAuthoringGuidance.ENUM);
          value.put("examples", List.of("Drainage", "#Drainage", "Gruppe.Drainage"));
        }
        if (kind == ExpressionKind.BOOLEAN && field.equals("value")) value.put("type", "boolean");
        if (field.equals("objects")) value.put("description", ConstraintAuthoringGuidance.COUNT);
        props.put(field, value);
      }
      @SuppressWarnings("unchecked") Map<String, Object> sourceChildren = (Map<String, Object>) original.get("children");
      Map<String, Object> children = new LinkedHashMap<>(sourceChildren);
      children.put("minItems", rule.minChildren());
      if (rule.maxChildren() != Integer.MAX_VALUE) children.put("maxItems", rule.maxChildren());
      children.put("description", "Geordnete Kinder; Blaetter erlauben nur eine fehlende oder leere Liste.");
      props.put("children", children);
      List<String> required = new ArrayList<>(List.of("kind"));
      required.addAll(rule.required());
      if (rule.minChildren() > 0) required.add("children");
      alternatives.add(Map.of("type", "object", "properties", props, "required", required,
          "additionalProperties", false));
    }
    node.clear();
    node.put("oneOf", alternatives);
    node.put("description", "Rekursiver Ausdruck. Erlaubte Felder haengen von kind ab; keine Rohsyntax. " + ConstraintAuthoringGuidance.COUNT);
  }

  private static void describe(Object node, String description) {
    if (node instanceof Map<?, ?> raw) {
      @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) raw;
      map.put("description", description);
      map.put("pattern", "^\\s*" + ConstraintSpecContract.IDENTIFIER + "\\s*$");
      map.put("examples", List.of("Regel42"));
    }
  }
}
