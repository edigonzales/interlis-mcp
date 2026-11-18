package ch.so.agi.mcp.model;

import ch.so.agi.mcp.constraint.ConstraintExpression.IliVersion;
import ch.so.agi.mcp.constraint.StandardFunctionRegistry;
import ch.so.agi.mcp.model.IliConstraintSpec.*;
import ch.so.agi.mcp.util.NameValidator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Shared node-shape rules for native schemas and every constraint renderer entry point. */
public final class ConstraintSpecContract {
  public static final String IDENTIFIER = "[A-Za-z][A-Za-z0-9_]*";
  public static final String SEMANTIC_PATH = IDENTIFIER + "(?:(?:\\.|->)" + IDENTIFIER + ")*";
  public static final Set<String> COMPARISON_OPERATORS = Set.of("==", "!=", "<", "<=", ">", ">=");
  public record Shape(List<String> required, int minChildren, int maxChildren) {}
  private ConstraintSpecContract() {}

  public static Shape shape(ExpressionKind kind) {
    return switch (kind) {
      case ATTRIBUTE, PATH -> new Shape(List.of("name"), 0, 0);
      case NUMERIC, BOOLEAN, ENUM, TEXT, MTEXT -> new Shape(List.of("value"), 0, 0);
      case FUNCTION -> new Shape(List.of("name", "functionOrigin"), 0, Integer.MAX_VALUE);
      case DEFINED, NOT -> new Shape(List.of(), 1, 1);
      case AND, OR -> new Shape(List.of(), 1, Integer.MAX_VALUE);
      case IMPLIES -> new Shape(List.of(), 2, 2);
      case COMPARE -> new Shape(List.of("operator"), 2, 2);
      case OBJECT_COUNT -> new Shape(List.of("objects"), 0, 0);
    };
  }

  public static String technicalName(@Nullable String raw, String path) {
    String name = text(raw, path);
    try {
      NameValidator.ascii().validateIdent(name, "Constraint name");
    } catch (IllegalArgumentException error) {
      String hint = "A technical constraint name must start with a letter; a business rule number is not a technical identifier.";
      if (name.matches("[0-9][A-Za-z0-9_]*") && name.length() <= 250) {
        hint += " Suggested name: Regel" + name + ". No automatic rename is performed.";
      }
      throw error("INVALID_IDENTIFIER", path, error.getMessage(), hint);
    }
    return name;
  }

  public static void validate(IliConstraintSpec spec, String version, String path) {
    require(spec, path);
    technicalName(spec.name, path + "/name");
    switch (spec) {
      case Mandatory m -> expression(m.condition, version, path + "/condition");
      case Plausibility p -> expression(p.condition, version, path + "/condition");
      case Unique u -> { if (u.where != null) expression(u.where, version, path + "/where"); }
      case Existence ignored -> { }
      case IliConstraintSpec.Set s -> {
        if (s.where != null) expression(s.where, version, path + "/where");
        require(s.condition, path + "/condition");
        switch (s.condition) {
          case BooleanSetConditionSpec b -> expression(b.expression, version, path + "/condition/expression");
          case ObjectCountSetConditionSpec c -> {
            objectSet(c.objects, path + "/condition/objects");
            operator(c.operator, path + "/condition/operator");
            require(c.threshold, path + "/condition/threshold");
          }
        }
      }
    }
  }

  public static void expression(ExpressionSpec spec, String version, String path) {
    require(spec, path);
    require(spec.kind, path + "/kind");
    Shape rule = shape(spec.kind);
    // Stable field order, followed by children in their original order.
    for (String field : List.of("name", "operator", "value", "functionOrigin", "objects")) {
      Object value = field(spec, field);
      if (rule.required().contains(field)) {
        if (value == null || !field.equals("value") && empty(value)) throw error("MISSING_FIELD", path + "/" + field,
            spec.kind + "." + field + " is required.", null);
      } else if (!empty(value)) {
        String hint = spec.kind == ExpressionKind.FUNCTION
            ? "FUNCTION arguments belong in the ordered children list."
            : spec.kind == ExpressionKind.OBJECT_COUNT
            ? "An OBJECT_COUNT expression returns a number. Put the comparison in a surrounding COMPARE node; only the separate SET OBJECT_COUNT condition has operator and threshold."
            : "Allowed data fields: " + rule.required() + ".";
        throw error("INVALID_FIELD", path + "/" + field,
            field + " is not allowed for " + spec.kind + ".", hint);
      }
    }
    List<ExpressionSpec> children = spec.children == null ? List.of() : spec.children;
    arity(children.size(), rule.minChildren(), rule.maxChildren(), path, spec.kind.name());
    switch (spec.kind) {
      case ATTRIBUTE -> identifier(spec.name, path + "/name");
      case PATH -> semanticPath(spec.name, path + "/name");
      case NUMERIC -> {
        try { new BigDecimal(String.valueOf(spec.value)); }
        catch (NumberFormatException ex) { throw error("INVALID_LITERAL", path + "/value", "NUMERIC.value must be a number.", null); }
      }
      case BOOLEAN -> {
        if (!(spec.value instanceof Boolean)) throw error("INVALID_LITERAL", path + "/value", "BOOLEAN.value must be true or false.", null);
      }
      case ENUM -> EnumLiteralValue.normalize(spec.value, path + "/value");
      case COMPARE -> operator(spec.operator, path + "/operator");
      case OBJECT_COUNT -> objectSet(spec.objects, path + "/objects");
      case FUNCTION -> {
        String name = text(spec.name, path + "/name");
        if (spec.functionOrigin == FunctionOrigin.STANDARD) {
          var function = standardFunction(name, version, path + "/name");
          arity(children.size(), function.parameters().size(), function.parameters().size(), path, "FUNCTION " + name);
        } else {
          try {
            NameValidator.ascii().validateFqn(name, "FUNCTION.name");
          } catch (IllegalArgumentException error) {
            throw error("INVALID_IDENTIFIER", path + "/name", error.getMessage(), null);
          }
        }
      }
      default -> { }
    }
    for (int i = 0; i < children.size(); i++) expression(children.get(i), version, path + "/children/" + i);
  }

  public static StandardFunctionRegistry.StandardFunction standardFunction(String name, String version, String path) {
    var found = StandardFunctionRegistry.findBySemanticId(name);
    if (found.isPresent()) return found.get();
    IliVersion iliVersion = "2.4".equals(version) ? IliVersion.ILI_24 : IliVersion.ILI_23;
    var matches = StandardFunctionRegistry.all().stream()
        .filter(f -> f.qualifiedName(iliVersion).equals(name)).toList();
    String hint = matches.size() == 1
        ? "Use FUNCTION.name=" + matches.getFirst().semanticId() + "; keep functionOrigin=STANDARD. No automatic substitution is performed."
        : "Use listConstraintFunctions and copy the exact semanticId; do not change functionOrigin to MODEL to bypass this check.";
    throw error("UNKNOWN_STANDARD_FUNCTION", path, "Unknown standard function semanticId: " + name, hint);
  }

  public static void modelConstraints(IliModelSpec spec) {
    if (spec == null || spec.topics == null) return;
    for (int i = 0; i < spec.topics.size(); i++) {
      var t = spec.topics.get(i);
      if (t == null) continue;
      topicConstraints(t, spec.iliVersion, "/spec/topics/" + i);
    }
  }

  public static void topicConstraints(IliModelSpec.TopicSpec topic, String version, String path) {
    if (topic == null) return;
    viewables(topic.classes, version, path + "/classes");
    viewables(topic.structures, version, path + "/structures");
    viewables(topic.associations, version, path + "/associations");
  }

  private static void viewables(List<? extends IliModelSpec.ViewableSpec> specs, String version, String path) {
    if (specs == null) return;
    for (int i = 0; i < specs.size(); i++) {
      if (specs.get(i) != null) constraints(specs.get(i).constraints, version, path + "/" + i + "/constraints");
    }
  }

  public static void constraints(List<IliConstraintSpec> specs, String version, String path) {
    if (specs != null) for (int i = 0; i < specs.size(); i++) validate(specs.get(i), version, path + "/" + i);
  }

  private static Object field(ExpressionSpec s, String field) {
    return switch (field) {
      case "name" -> s.name; case "operator" -> s.operator; case "value" -> s.value;
      case "functionOrigin" -> s.functionOrigin; case "objects" -> s.objects;
      default -> throw new IllegalArgumentException(field);
    };
  }
  private static boolean empty(Object value) { return value == null || value instanceof String s && s.isBlank(); }
  private static void require(Object value, String path) {
    if (value == null) throw error("MISSING_FIELD", path, "Required field or node is missing.", null);
  }
  private static String text(String value, String path) {
    if (value == null || value.isBlank()) throw error("MISSING_FIELD", path, "Required text is missing.", null);
    return value.trim();
  }
  private static void identifier(String value, String path) {
    try {
      NameValidator.ascii().validateIdent(text(value, path), "ATTRIBUTE.name");
    } catch (SpecValidationException error) {
      throw error;
    } catch (IllegalArgumentException error) {
      throw error("INVALID_IDENTIFIER", path, error.getMessage(), null);
    }
  }
  private static void semanticPath(String value, String path) {
    if (!text(value, path).matches(SEMANTIC_PATH)) throw error("INVALID_IDENTIFIER", path, "Expected a dot/association-arrow separated path.", "PATH uses name, not objects.");
  }
  private static void operator(String value, String path) {
    if (!COMPARISON_OPERATORS.contains(text(value, path))) throw error("INVALID_LITERAL", path, "Unsupported comparison operator.", "Use ==, !=, <, <=, > or >=.");
  }
  private static void objectSet(ObjectSetSpec objects, String path) {
    require(objects, path);
    if (objects instanceof PathObjectsSpec p) semanticPath(p.path, path + "/path");
  }
  private static void arity(int actual, int min, int max, String path, String kind) {
    if (actual < min || actual > max) throw error("INVALID_ARITY", path + "/children",
        kind + " expects " + (min == max ? min : "at least " + min) + " children, got " + actual + ".", "Preserve argument order in children.");
  }
  private static SpecValidationException error(String code, String path, String message, String hint) {
    return new SpecValidationException(code, path, message, hint);
  }
}
