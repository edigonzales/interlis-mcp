package ch.so.agi.mcp.constraint;

import ch.interlis.ili2c.metamodel.*;
import ch.interlis.ili2c.parser.Ili23Parser;
import java.util.*;

/** Deterministic concrete type choices keyed by path position, never by class alone. */
public final class ObjectPathRoutes {
  private ObjectPathRoutes() {}

  public static List<Map<String,String>> resolve(TransferDescription td, String context,
      ConstraintExpression expression) {
    var routes = new ArrayList<Map<String,String>>(); routes.add(Map.of());
    for (var reference : expression.references().stream().filter(r -> r.kind() == ConstraintExpression.ReferenceKind.OBJECT_COUNT)
        .sorted(Comparator.comparing(ConstraintExpression.Reference::name)).toList()) {
      String path = ConstraintModelSynthesizer.countedPath(reference);
      ObjectPath parsed;
      try { parsed = Ili23Parser.parseObjectOrAttributePath(td, (Viewable<?>)td.getElement(context), path); }
      catch (Exception ex) { throw new IllegalArgumentException("OBJECT_PATH_UNSUPPORTED: " + path, ex); }
      if (parsed == null || parsed.isAttributePath()) throw new IllegalArgumentException("OBJECT_PATH_UNSUPPORTED: " + path);
      var steps = parsed.getPathElements();
      if (steps.length > 8) throw new IllegalArgumentException("OBJECT_PATH_STEP_BUDGET_EXCEEDED: " + path);
      for (int i = 0; i < steps.length; i++) {
        if (!(steps[i].getViewable() instanceof Table table)) throw new IllegalArgumentException("OBJECT_PATH_TARGET_UNSUPPORTED: " + path);
        if (!table.isIdentifiable()) {
          if (table.isAbstract()) throw new IllegalArgumentException("OBJECT_PATH_STRUCTURE_TYPE_UNSUPPORTED: " + table.getScopedName());
          continue;
        }
        var targets = new TreeSet<String>();
        if (!table.isAbstract()) targets.add(table.getScopedName());
        for (Object extension : table.getExtensions()) if (extension instanceof Table child && !child.isAbstract()) targets.add(child.getScopedName());
        if (targets.isEmpty()) throw new IllegalArgumentException("OBJECT_PATH_CONCRETE_ROUTE_UNAVAILABLE: " + table.getScopedName());
        var next = new ArrayList<Map<String,String>>();
        for (var route : routes) for (var target : targets) {
          String key = path + "#" + i;
          if (route.entrySet().stream().anyMatch(entry -> prefix(entry.getKey()).equals(prefix(key)) && !entry.getValue().equals(target))) continue;
          var copy = new LinkedHashMap<>(route); copy.put(key, target); next.add(Map.copyOf(copy));
          if (next.size() > 8) throw new IllegalArgumentException("OBJECT_PATH_ROUTE_BUDGET_EXCEEDED: maximum eight concrete route combinations.");
        }
        routes = next;
      }
    }
    return List.copyOf(routes);
  }

  private static String prefix(String key) {
    int separator=key.lastIndexOf('#');
    int length=Integer.parseInt(key.substring(separator+1))+1;
    return String.join("->", Arrays.copyOf(key.substring(0,separator).split("->"),length));
  }
}
