package ch.so.agi.mcp.constraint;

import java.util.*;

/** A description of compiler semantics, never a claim of business acceptance. */
public record ConstraintExplanation(Status status, String constraintFqn, String contextFqn,
    String kind, String description, List<String> referencedElements, List<String> notes,
    List<String> limitations) {
  public enum Status { COMPLETE, PARTIAL, UNAVAILABLE }

  public static ConstraintExplanation unavailable(String fqn) {
    return new ConstraintExplanation(Status.UNAVAILABLE, fqn, "", "UNKNOWN", "Keine kompilierte Regel verfügbar.",
        List.of(), List.of(), List.of("Keine fachliche Abnahme."));
  }

  public static ConstraintExplanation fromReview(Map<String, Object> review) {
    Map<?, ?> info = map(review.get("constraint")), context = map(review.get("context"));
    Map<?, ?> ast = map(review.get("ast"));
    var renderer = new Renderer();
    String kind = String.valueOf(ast.get("kind")).replace("_CONSTRAINT", "");
    String scope = Boolean.TRUE.equals(ast.get("local")) ? "lokal innerhalb " + renderer.render(ast.get("prefix"))
        : Boolean.TRUE.equals(ast.get("perBasket")) ? "pro Basket" : "global";
    String description = switch (kind) {
      case "MANDATORY" -> "Für jedes Objekt gilt: " + renderer.render(ast.get("condition")) + ".";
      case "UNIQUENESS", "UNIQUE" -> "Eindeutig " + scope + ": " + renderer.renderList(ast.get("uniqueElements"), ", ")
          + renderer.filter(ast) + ".";
      case "EXISTENCE" -> "Der Wert " + renderer.render(ast.get("restrictedAttribute"))
          + " muss in mindestens einem REQUIRED-IN-Ziel vorkommen: " + renderer.targets(ast.get("requiredIn")) + ".";
      case "PLAUSIBILITY" -> ("AT_LEAST".equals(ast.get("direction")) ? "Mindestens " : "Höchstens ")
          + ast.get("percentage") + " Prozent der Objekte erfüllen: " + renderer.render(ast.get("condition")) + ".";
      case "SET" -> "Mengenregel " + scope + renderer.filter(ast) + ": " + renderer.render(ast.get("condition")) + ".";
      default -> { renderer.gaps.add("Constraint-Art nicht vollständig erklärt: " + kind); yield "Constraint-Art: " + kind + "."; }
    };
    if (String.valueOf(context.get("kind")).contains("View")) renderer.gaps.add("View-Auswahl wird nicht vollständig erklärt; View-Definition berücksichtigen.");
    var notes = new ArrayList<String>();
    if ("MANDATORY".equals(kind)) notes.add("Mandatory: FALSE verletzt die Regel; TRUE und UNDEFINED gelten im verwendeten Validator als erfüllt. Fehlende Werte werden nicht automatisch verboten.");
    if (renderer.ordered) notes.add("AND/OR werden in der dargestellten Reihenfolge ausgewertet. Ein früher UNDEFINED-Wert kann die Auswertung beenden; spätere Operanden dürfen nicht ohne Semantikprüfung umgeordnet werden.");
    notes.add("COMPLETE bezeichnet den Erklärumfang, keine fachliche Abnahme oder vollständige Modellkonsistenz.");
    var refs = new TreeSet<String>();
    if (review.get("referencedElements") instanceof List<?> elements) for (Object element : elements) {
      Map<?, ?> ref = map(element);
      Object name = ref.containsKey("scopedName") ? ref.get("scopedName") : ref.get("name");
      if (name != null) refs.add(name.toString());
    }
    return new ConstraintExplanation(renderer.gaps.isEmpty() && Boolean.TRUE.equals(review.get("astComplete"))
        ? Status.COMPLETE : Status.PARTIAL, String.valueOf(info.get("scopedName")),
        String.valueOf(context.get("scopedName")), kind, description, List.copyOf(refs), notes, List.copyOf(renderer.gaps));
  }

  private static Map<?, ?> map(Object value) { return value instanceof Map<?, ?> m ? m : Map.of(); }
  private static final class Renderer {
    final Set<String> gaps = new LinkedHashSet<>();
    boolean ordered;
    String targets(Object raw) {
      if (!(raw instanceof List<?> targets)) { gaps.add("REQUIRED-IN-Ziele fehlen."); return "[unbekannt]"; }
      return String.join(" oder ", targets.stream().map(t -> String.valueOf(map(t).get("contextFqn")) + "." + render(t)).toList());
    }
    String filter(Map<?, ?> ast) { return ast.containsKey("preCondition") ? "; Auswahl: " + render(ast.get("preCondition")) : ""; }
    String renderList(Object raw, String separator) {
      if (!(raw instanceof List<?> list)) { gaps.add("Ausdrucksliste fehlt."); return "[unbekannt]"; }
      return String.join(separator, list.stream().map(this::render).toList());
    }
    String render(Object raw) {
      Map<?, ?> n = map(raw); String k = String.valueOf(n.get("kind"));
      return switch (k) {
        case "AND", "OR" -> { ordered = true; yield "(" + renderList(n.get("children"), k.equals("AND") ? " UND danach " : " ODER danach ") + ")"; }
        case "NOT" -> "NICHT (" + render(n.get("operand")) + ")";
        case "GROUP" -> "(" + render(n.get("expression")) + ")";
        case "DEFINED" -> render(n.get("argument")) + " ist definiert";
        case "==", "!=", ">", ">=", "<", "<=" -> render(n.get("left")) + " " + switch (k) {
          case "==" -> "ist gleich"; case "!=" -> "ist ungleich"; case ">" -> "ist grösser als";
          case ">=" -> "ist mindestens"; case "<" -> "ist kleiner als"; default -> "ist höchstens";
        } + " " + render(n.get("right"));
        case "OBJECT_PATH" -> {
          if (!Set.of("NUMERIC", "BOOLEAN", "ENUM", "TEXT", "MTEXT").contains(String.valueOf(map(n.get("type")).get("kind"))))
            gaps.add("Nichtskalarer Pfad: nur strukturelle Referenz erklärt.");
          if (Boolean.TRUE.equals(n.get("collection")) || (n.get("steps") instanceof List<?> steps && steps.size() > 1))
            gaps.add("Navigierter Pfad: keine vollständige Erklärung der Navigationssemantik.");
          yield String.valueOf(n.get("path"));
        }
        case "TEXT_LITERAL" -> "„" + n.get("value") + "“";
        case "NUMERIC_LITERAL", "ENUM_LITERAL", "BOOLEAN", "UNDEFINED" -> ("UNDEFINED".equals(k) ? "UNDEFINED" : String.valueOf(n.get("value")));
        default -> { gaps.add("Ausdruck nicht vollständig erklärt: " + k);
          yield "[" + k + (n.containsKey("function") ? ": " + n.get("function") : "") + "]"; }
      };
    }
  }
}
