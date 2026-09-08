package ch.so.agi.mcp.benchmark;

import ch.ehi.basics.logging.*;
import ch.interlis.ili2c.*;
import ch.interlis.ili2c.config.*;
import ch.interlis.ili2c.generator.Interlis2Generator;
import ch.interlis.ili2c.metamodel.*;
import java.nio.file.*;
import java.util.*;
import java.math.BigDecimal;
import tools.jackson.databind.ObjectMapper;

/** Offline benchmark oracle. Deliberately independent of authoring, solver and MCP review code. */
public final class CompilerEvidence {
  public record Compiled(TransferDescription td, List<String> errors) {}

  public static Compiled compile(Path path, String repositories) {
    var errors = new ArrayList<String>();
    ch.ehi.basics.logging.LogListener listener = event -> {
      if (event.getEventKind() == LogEvent.ERROR) errors.add(event.getEventMsg());
    };
    var logger = EhiLogger.getInstance();
    synchronized (logger) {
      logger.addListener(listener);
      try {
        var settings = new Ili2cSettings();
        Main.setDefaultIli2cPathMap(settings);
        settings.setIlidirs(repositories);
        var config = new Configuration();
        config.addFileEntry(new FileEntry(path.toAbsolutePath().toString(), FileEntryKind.ILIMODELFILE));
        config.setAutoCompleteModelList(true);
        var td = Main.runCompiler(config, settings, null);
        return new Compiled(errors.isEmpty() ? td : null, errors);
      } catch (Exception e) {
        errors.add(e.toString());
        return new Compiled(null, errors);
      } finally {
        logger.removeListener(listener);
      }
    }
  }

  public static Map<String, Constraint> constraints(TransferDescription td) {
    var result = new TreeMap<String, Constraint>();
    for (Model model : td.getModelsFromLastFile()) collect(model, result);
    return result;
  }

  private static void collect(Container<?> container, Map<String, Constraint> result) {
    for (var iterator = container.iterator(); iterator.hasNext();) {
      Object element = iterator.next();
      if (element instanceof Constraint c) result.put(c.getScopedName(), c);
      else if (element instanceof Container<?> nested) collect(nested, result);
    }
  }

  private static String fqn(Element element) { return element == null ? "" : element.getScopedName(); }
  private static List<Object> expressions(Evaluable[] values) {
    return Arrays.stream(values).map(CompilerEvidence::expression).toList();
  }

  private static String pathTarget(PathEl e) {
    if (e instanceof AttributeRef a) {
      Type type = a.getAttr().getDomainResolvingAliases();
      return type instanceof CompositionType c ? fqn(c.getComponentType()) : "";
    }
    return fqn(e.getViewable());
  }

  public static Object expression(Evaluable value) {
    if (value == null) return Map.of("kind", "ABSENT");
    if (value instanceof Expression.Subexpression e) return expression(e.getSubexpression());
    if (value instanceof Expression.Conjunction e) return Map.of("kind", "AND", "children", expressions(e.getConjoined()));
    if (value instanceof Expression.Disjunction e) return Map.of("kind", "OR", "children", expressions(e.getDisjoined()));
    if (value instanceof Expression.Negation e) return Map.of("kind", "NOT", "operand", expression(e.getNegated()));
    if (value instanceof Expression.DefinedCheck e) return Map.of("kind", "DEFINED", "operand", expression(e.getArgument()));
    if (value instanceof ObjectPath p) return Map.of("kind", "PATH", "root", fqn(p.getRoot()),
        "steps", Arrays.stream(p.getPathElements()).map(e -> Map.of("kind", e.getClass().getSimpleName(),
            "name", e.getName(), "target", pathTarget(e))).toList());
    if (value instanceof ch.interlis.ili2c.metamodel.Objects o) {
      var restrictions = new ArrayList<String>();
      o.iteratorRestrictedTo().forEachRemaining(e -> restrictions.add(fqn((Element) e)));
      return Map.of("kind", "ALL", "context", fqn(o.getContext()), "base", fqn(o.getBase()), "restrictions", restrictions);
    }
    if (value instanceof FunctionCall f) return Map.of("kind", "FUNCTION", "name", fqn(f.getFunction()), "arguments", expressions(f.getArguments()));
    if (value instanceof Constant.Text t) return Map.of("kind", "TEXT", "value", t.getValue());
    if (value instanceof Constant.Enumeration e) return Map.of("kind", "ENUM", "value", List.of(e.getValue()));
    if (value instanceof Constant.Numeric n) return Map.of("kind", "NUMERIC", "value",
        new BigDecimal(n.getValue().toString()).stripTrailingZeros().toPlainString(), "unit", fqn(n.getUnit()));
    if (value instanceof Constant.Undefined) return Map.of("kind", "UNDEFINED");
    // All binary nodes below preserve operand order, including equality and implication.
    var binary = Set.of("Equality", "Inequality", "GreaterThan", "GreaterThanOrEqual", "LessThan", "LessThanOrEqual",
        "Implication", "Addition", "Subtraction", "Multiplication", "Division");
    if (value instanceof Expression && binary.contains(value.getClass().getSimpleName())) {
      try {
        return Map.of("kind", value.getClass().getSimpleName(),
            "left", expression((Evaluable) value.getClass().getMethod("getLeft").invoke(value)),
            "right", expression((Evaluable) value.getClass().getMethod("getRight").invoke(value)));
      } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    throw new IllegalArgumentException("UNSUPPORTED_ORACLE_AST: " + value.getClass().getName());
  }

  public static Map<String, Object> ast(Constraint c) {
    var out = new LinkedHashMap<String, Object>();
    out.put("kind", c.getClass().getSimpleName());
    out.put("contextFqn", fqn(c.getContainer()));
    out.put("condition", expression(c.getCondition()));
    if (c instanceof PlausibilityConstraint p) {
      out.put("direction", p.getDirection()); out.put("percentage", p.getPercentage());
    }
    if (c instanceof SetConstraint s) { out.put("perBasket", s.perBasket()); out.put("where", expression(s.getPreCondition())); }
    if (c instanceof UniquenessConstraint u) {
      out.put("local", u.getLocal()); out.put("perBasket", u.perBasket());
      out.put("where", expression(u.getPreCondition())); out.put("prefix", expression(u.getPrefix()));
      out.put("keys", Arrays.stream(u.getElements().getAttributes()).map(CompilerEvidence::expression).toList());
    }
    if (c instanceof ExistenceConstraint e) {
      out.put("restricted", expression(e.getRestrictedAttribute()));
      var targets = new ArrayList<Object>();
      e.iteratorRequiredIn().forEachRemaining(p -> targets.add(expression(p)));
      out.put("requiredIn", targets);
    }
    return out;
  }

  public static Map<String, Object> inspect(Path path, String repositories, String selector) {
    var c = compile(path, repositories);
    var out = new LinkedHashMap<String, Object>();
    out.put("valid", c.td() != null); out.put("errors", c.errors());
    if (c.td() == null) return out;
    var dependencies = new TreeSet<String>();
    for (var iterator = c.td().iterator(); iterator.hasNext();) {
      Model m = iterator.next();
      if (!(m instanceof PredefinedModel) && m.getFileName() != null
          && !Path.of(m.getFileName()).toAbsolutePath().equals(path.toAbsolutePath())) dependencies.add(m.getFileName());
    }
    out.put("dependencies", dependencies);
    var all = constraints(c.td()); out.put("constraintNames", all.keySet());
    if (selector != null && !selector.isBlank()) {
      Constraint selected = all.get(selector);
      if (selected == null) throw new IllegalArgumentException("Oracle constraint not found: " + selector);
      out.put("ast", ast(selected)); out.put("astComplete", true);
      out.put("pathFacts", pathFacts(selected.getCondition()));
    }
    return out;
  }

  public static Map<String, Object> compare(Path beforePath, Path afterPath, String repositories) {
    var before = compile(beforePath, repositories); var after = compile(afterPath, repositories);
    var out = new LinkedHashMap<String, Object>();
    out.put("beforeValid", before.td() != null); out.put("candidateCompiles", after.td() != null);
    out.put("errors", after.errors());
    if (before.td() == null || after.td() == null) return out;
    var a = constraints(before.td()); var b = constraints(after.td());
    // Anonymous ConstraintN names are positions, not stable identities: restoring an
    // earlier removed constraint renumbers later anonymous siblings (notably N11).
    var added = b.values().stream().filter(c -> explicitName(c) && !a.containsKey(c.getScopedName())).toList();
    var missingNamed = a.values().stream().anyMatch(c -> explicitName(c) && !b.containsKey(c.getScopedName()));
    if (b.size() != a.size() + 1 || missingNamed || added.size() > 1) {
      out.put("noCollateralChanges", false); return out;
    }
    Constraint target;
    if (added.size() == 1) target = added.getFirst();
    else {
      var oldAnonymous = new ArrayList<String>();
      a.values().stream().filter(c -> !explicitName(c)).forEach(c -> oldAnonymous.add(anonymousSignature(before.td(), c)));
      var newAnonymous = new ArrayList<Constraint>();
      for (var c : b.values()) if (!explicitName(c) && !oldAnonymous.remove(anonymousSignature(after.td(), c))) newAnonymous.add(c);
      if (!oldAnonymous.isEmpty() || newAnonymous.size() != 1) { out.put("noCollateralChanges", false); return out; }
      target = newAnonymous.getFirst();
    }
    out.put("addedConstraints", List.of(target.getScopedName()));
    try {
      out.put("ast", ast(target)); out.put("astComplete", true);
      out.put("pathFacts", pathFacts(target.getCondition()));
    }
    catch (IllegalArgumentException e) { out.put("astComplete", false); out.put("astError", e.getMessage()); }
    target.getContainer().remove(target);
    for (Model model : before.td().getModelsFromLastFile()) normalizeAnonymousIndices(model);
    for (Model model : after.td().getModelsFromLastFile()) normalizeAnonymousIndices(model);
    // Compare the complete compiler-generated submitted models after removing exactly the new constraint.
    var oldModels = new TreeMap<String, String>(); var newModels = new TreeMap<String, String>();
    for (Model model : before.td().getModelsFromLastFile()) oldModels.put(model.getName(), Interlis2Generator.debugToString(before.td(), model));
    for (Model model : after.td().getModelsFromLastFile()) newModels.put(model.getName(), Interlis2Generator.debugToString(after.td(), model));
    out.put("noCollateralChanges", oldModels.equals(newModels));
    return out;
  }

  private static boolean explicitName(Constraint c) {
    return c.hasCustomName() || c.getMetaValue("name") != null;
  }

  /** Compiler-derived facts only for direct scalar numeric attributes; no role traversal assumptions. */
  private static List<Map<String, Object>> pathFacts(Evaluable value) {
    var facts = new ArrayList<Map<String, Object>>();
    collectPathFacts(value, facts);
    return facts.stream().distinct().toList();
  }

  private static void collectPathFacts(Evaluable value, List<Map<String, Object>> facts) {
    if (value == null) return;
    if (value instanceof ObjectPath p) {
      var steps = p.getPathElements();
      if (steps.length == 1 && steps[0] instanceof AttributeRef a
          && a.getAttr().getDomainResolvingAliases() instanceof NumericType) {
        Type declared = a.getAttr().getDomain();
        facts.add(Map.of("path", expression(p), "numeric", true, "mandatory",
            declared != null && (declared.isMandatory() || declared.isMandatoryConsideringAliases())));
      }
    } else if (value instanceof Expression.Subexpression e) collectPathFacts(e.getSubexpression(), facts);
    else if (value instanceof Expression.Conjunction e) {
      for (var child : e.getConjoined()) collectPathFacts(child, facts);
    } else if (value instanceof Expression.Disjunction e) {
      for (var child : e.getDisjoined()) collectPathFacts(child, facts);
    } else if (value instanceof Expression.Negation e) collectPathFacts(e.getNegated(), facts);
    else if (value instanceof Expression.DefinedCheck e) collectPathFacts(e.getArgument(), facts);
    else if (value instanceof FunctionCall f) {
      for (var child : f.getArguments()) collectPathFacts(child, facts);
    } else if (value instanceof Expression) {
      try {
        collectPathFacts((Evaluable) value.getClass().getMethod("getLeft").invoke(value), facts);
        collectPathFacts((Evaluable) value.getClass().getMethod("getRight").invoke(value), facts);
      } catch (ReflectiveOperationException ignored) {
        // No fact is safer than inferring totality for unsupported expression nodes.
      }
    }
  }

  private static String anonymousSignature(TransferDescription td, Constraint c) {
    var writer = new java.io.StringWriter();
    var generator = Interlis2Generator.generateElements(writer, td);
    generator.printConstraint(c);
    return fqn(c.getContainer()) + "\n" + writer.toString();
  }

  private static void normalizeAnonymousIndices(Container<?> container) {
    int index = 0;
    for (var iterator = container.iterator(); iterator.hasNext();) {
      Object child = iterator.next();
      if (child instanceof Constraint c) { ++index; if (!explicitName(c)) c.setNameIdx(index); }
      else if (child instanceof Container<?> nested) normalizeAnonymousIndices(nested);
    }
  }

  public static void main(String[] args) throws Exception {
    Object output = switch (args[0]) {
      case "inspect" -> inspect(Path.of(args[1]), args[2], args.length > 3 ? args[3] : null);
      case "compare" -> compare(Path.of(args[1]), Path.of(args[2]), args[3]);
      default -> throw new IllegalArgumentException("inspect MODEL REPOS [CONSTRAINT] | compare BEFORE AFTER REPOS");
    };
    System.out.println(new ObjectMapper().writeValueAsString(output));
  }
}
