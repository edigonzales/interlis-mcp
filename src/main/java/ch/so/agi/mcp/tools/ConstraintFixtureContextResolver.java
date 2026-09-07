package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.CompositionType;
import ch.interlis.ili2c.metamodel.Container;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.Topic;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Embeds a semantic structure root in a real transfer class without altering the model. */
final class ConstraintFixtureContextResolver {
  record Route(Table owner, List<AttributeDef> attributes) {
    String ownerFqn() { return owner.getScopedName(null); }
    String path() {
      return attributes.stream().map(AttributeDef::getName)
          .collect(java.util.stream.Collectors.joining("->"));
    }

    ConstraintTestTools.TestCase embed(ConstraintTestTools.TestCase source) {
      var root = source.objects.getFirst();
      Map<String, Object> values = new LinkedHashMap<>(root.values);
      if (root.references != null) for (var reference : root.references.entrySet()) {
        String name = reference.getKey();
        String oid = reference.getValue();
        if (values.putIfAbsent(name, oid) != null) {
          throw new ConstraintFixtureException("STRUCTURE_MATERIALIZATION_FAILED",
              "Conflicting structure reference assignment for " + name + ".");
        }
      }
      int instances = 0;
      int copies = 1;
      for (AttributeDef attribute : attributes) {
        CompositionType composition = composition(attribute);
        long minimum = composition.getCardinality().getMinimum();
        if (minimum > 5) throw budget("Composition minimum exceeds five occurrences: " + attribute.getScopedName(null));
        copies *= Math.max(1, (int) minimum);
        instances += copies;
        if (instances > 64) throw budget("Embedding requires more than 64 structure instances.");
      }
      for (int index = attributes.size() - 1; index >= 0; index--) {
        AttributeDef attribute = attributes.get(index);
        CompositionType composition = composition(attribute);
        int count = Math.max(1, (int) composition.getCardinality().getMinimum());
        Object nested = composition.getCardinality().getMaximum() > 1
            ? java.util.Collections.nCopies(count, values) : values;
        values = Map.of(attribute.getName(), nested);
      }
      var ownerObject = new ConstraintTestTools.TestObject();
      ownerObject.classFqn = ownerFqn();
      ownerObject.oid = root.oid;
      ownerObject.basketId = root.basketId;
      ownerObject.values = values;
      List<ConstraintTestTools.TestObject> objects = new ArrayList<>(source.objects);
      objects.set(0, ownerObject);
      var result = new ConstraintTestTools.TestCase();
      result.name = source.name;
      result.expectedConstraintValid = source.expectedConstraintValid;
      result.objects = List.copyOf(objects);
      result.links = source.links;
      return result;
    }
  }

  private record Node(Table owner, Table current, List<AttributeDef> path, Set<Table> visited) {}

  static List<Route> resolve(TransferDescription td, Table target) {
    if (target.isAbstract() || target.isIdentifiable()) {
      throw new ConstraintFixtureException("STRUCTURE_PATH_UNSUPPORTED",
          "Fixture embedding requires a concrete structure: " + target.getScopedName(null));
    }
    List<Table> tables = new ArrayList<>();
    collectTables(td, tables);
    var queue = new ArrayDeque<Node>();
    tables.stream().filter(table -> table.isIdentifiable() && !table.isAbstract()
            && !table.isImplicit() && table.getContainer(Topic.class) != null)
        .sorted(Comparator.comparing(table -> table.getScopedName(null)))
        .forEach(table -> queue.add(new Node(table, table, List.of(), Set.of(table))));
    List<Route> routes = new ArrayList<>();
    boolean unsupported = false;
    boolean depthLimit = false;
    while (!queue.isEmpty() && routes.size() < 8) {
      Node node = queue.removeFirst();
      if (node.current() == target) {
        routes.add(new Route(node.owner(), node.path()));
        continue;
      }
      List<AttributeDef> attributes = new ArrayList<>();
      var iterator = node.current().getAttributes();
      while (iterator.hasNext()) {
        if (iterator.next() instanceof AttributeDef attribute
            && Type.findReal(attribute.getDomainOrDerivedDomain()) instanceof CompositionType) {
          attributes.add(attribute);
        }
      }
      attributes.sort(Comparator.comparing(AttributeDef::getName));
      for (AttributeDef attribute : attributes) {
        CompositionType composition = composition(attribute);
        if (composition.getCardinality().getMaximum() == 0) continue;
        Table child = composition.getComponentType();
        if (child.isAbstract() || node.visited().contains(child)) {
          unsupported = true;
          continue;
        }
        if (node.path().size() >= 8) {
          depthLimit = true;
          continue;
        }
        List<AttributeDef> path = new ArrayList<>(node.path());
        path.add(attribute);
        Set<Table> visited = new java.util.HashSet<>(node.visited());
        visited.add(child);
        queue.addLast(new Node(node.owner(), child, List.copyOf(path), Set.copyOf(visited)));
      }
    }
    if (routes.isEmpty()) {
      String code = depthLimit ? "STRUCTURE_FIXTURE_BUDGET_EXCEEDED"
          : unsupported ? "STRUCTURE_PATH_UNSUPPORTED" : "STRUCTURE_OWNER_NOT_FOUND";
      throw new ConstraintFixtureException(code,
          "No supported composition route from a concrete transfer class to "
              + target.getScopedName(null) + " (maximum depth 8; cyclic and abstract paths are unsupported).");
    }
    return List.copyOf(routes);
  }

  private static CompositionType composition(AttributeDef attribute) {
    return (CompositionType) Type.findReal(attribute.getDomainOrDerivedDomain());
  }

  private static ConstraintFixtureException budget(String reason) {
    return new ConstraintFixtureException("STRUCTURE_FIXTURE_BUDGET_EXCEEDED", reason);
  }

  private static void collectTables(Container<?> container, List<Table> tables) {
    var iterator = container.iterator();
    while (iterator.hasNext()) {
      Element element = iterator.next();
      if (element instanceof Table table) tables.add(table);
      else if (element instanceof Container<?> child) collectTables(child, tables);
    }
  }
}
