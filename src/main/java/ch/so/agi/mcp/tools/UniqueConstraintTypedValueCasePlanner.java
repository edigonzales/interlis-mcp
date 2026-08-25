package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.AbstractClassDef;
import ch.interlis.ili2c.metamodel.AreaType;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.AttributeRef;
import ch.interlis.ili2c.metamodel.PathEl;
import ch.interlis.ili2c.metamodel.PathElRefAttr;
import ch.interlis.ili2c.metamodel.MultiAreaType;
import ch.interlis.ili2c.metamodel.ReferenceType;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.Type;
import ch.interlis.ili2c.metamodel.UniquenessConstraint;
import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.ConstraintExpression;
import ch.so.agi.mcp.constraint.SemanticConstraint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Direct typed-value UNIQUE fixtures for references, structures and geometry values. */
final class UniqueConstraintTypedValueCasePlanner {

  private record Key(
      AttributeDef attribute,
      TypedValueFixtureFactory.ValuePair pair,
      @Nullable Table referenceTarget,
      boolean externalReference) {
  }

  private UniqueConstraintTypedValueCasePlanner() {
  }

  static UniqueConstraintCasePlanner.@Nullable Plan planIfTyped(
      CompiledConstraintContext context,
      SemanticConstraint.Unique semantics) {
    if (!(context.constraint() instanceof UniquenessConstraint raw)) return null;
    PathEl[][] pathElements = java.util.Arrays.stream(raw.getElements().getAttributes())
        .map(path -> path.getPathElements())
        .toArray(PathEl[][]::new);
    boolean needsTypedPlanner = semantics.elements().stream().anyMatch(path ->
        path.endpointType().scalarKind() == ConstraintExpression.ScalarKind.GEOMETRY
            || path.endpointType().scalarKind() == ConstraintExpression.ScalarKind.UNKNOWN);
    if (!needsTypedPlanner) return null;
    if (semantics.local()) {
      return gap("LOCAL_UNIQUE_TYPED_VALUE_UNSUPPORTED",
          "LOCAL UNIQUE with reference, structure or geometry keys requires a collection-member graph proof that is not approximated.");
    }
    if (semantics.preCondition() != null) {
      return gap("UNIQUE_TYPED_VALUE_WHERE_UNSUPPORTED",
          "WHERE plus reference, structure or geometry UNIQUE keys requires a joint predicate/value proof that is not approximated.");
    }
    if (pathElements.length != semantics.elements().size()) {
      return gap("UNIQUE_TYPED_KEY_AST_MISMATCH", "The UNIQUE key count differs between AST and semantic IR.");
    }
    if (!(context.transferDescription().getElement(semantics.contextFqn()) instanceof Table root)
        || !root.isIdentifiable()) {
      return gap("UNIQUE_TYPED_CONTEXT_NOT_IDENTIFIABLE",
          "Typed UNIQUE fixtures require an identifiable class context.");
    }

    List<Key> keys = new ArrayList<>();
    for (int index = 0; index < pathElements.length; index++) {
      PathEl[] elements = pathElements[index];
      if (elements.length != 1) {
        return gap("UNIQUE_TYPED_NAVIGATION_UNSUPPORTED",
            "Navigated reference, structure and geometry UNIQUE keys require NavigationGraphSynthesizer coverage per route.");
      }
      AttributeDef attribute = attribute(elements[0]);
      if (attribute == null) {
        return gap("UNIQUE_TYPED_KEY_NOT_ATTRIBUTE",
            "Typed UNIQUE key does not resolve to an attribute: " + semantics.elements().get(index).path());
      }
      TypedValueFixtureFactory.ValuePair pair = TypedValueFixtureFactory.pair(
          attribute.getDomainOrDerivedDomain());
      if (pair == null || !pair.distinguishable()) {
        return gap("UNIQUE_SECOND_VALID_VALUE_UNAVAILABLE",
            "No two distinct valid values can be derived for UNIQUE key " + attribute.getScopedName(null) + ".");
      }
      Type real = Type.findReal(attribute.getDomainOrDerivedDomain());
      if (real instanceof AreaType || real instanceof MultiAreaType) {
        return gap("UNIQUE_AREA_DUPLICATE_NOT_MODEL_VALID",
            "AREA topology forbids the overlapping duplicate geometries needed to isolate an in-basket UNIQUE violation.");
      }
      Table target = null;
      boolean external = false;
      if (real instanceof ReferenceType reference) {
        target = concreteTarget(reference.getReferred());
        if (target == null) {
          return gap("UNIQUE_REFERENCE_TARGET_ROUTE_UNAVAILABLE",
              "A UNIQUE reference key requires exactly one concrete target route.");
        }
        if (target == root || target.isExtending(root) || root.isExtending(target)) {
          return gap("UNIQUE_SELF_REFERENCE_KEY_UNSUPPORTED",
              "Reference target objects would also be subjects of this UNIQUE constraint.");
        }
        external = reference.isExternal();
        pair = new TypedValueFixtureFactory.ValuePair(
            new TypedValueFixtureFactory.ReferenceValue("unique_" + attribute.getName() + "_same"),
            new TypedValueFixtureFactory.ReferenceValue("unique_" + attribute.getName() + "_different"),
            "REFERENCE");
      }
      keys.add(new Key(attribute, pair, target, external));
    }

    Map<String, Object> same = new LinkedHashMap<>();
    for (Key key : keys) same.put(key.attribute().getName(), key.pair().same());
    List<ConstraintTestTools.TestObject> dependencies = dependencies(keys, null);
    List<ConstraintTestTools.TestCase> cases = new ArrayList<>();
    List<Map<String, Object>> summaries = new ArrayList<>();
    add(cases, summaries, "single typed UNIQUE key", true, "WITNESS",
        dependencies, List.of(object(root, "unique_typed_single", same, null)), same);
    add(cases, summaries, "duplicate typed UNIQUE key in one basket", false, "COUNTEREXAMPLE",
        dependencies, List.of(
            object(root, "unique_typed_duplicate_a", same, null),
            object(root, "unique_typed_duplicate_b", same, null)), same);

    for (int index = 0; index < keys.size(); index++) {
      Key changedKey = keys.get(index);
      Map<String, Object> changed = new LinkedHashMap<>(same);
      changed.put(changedKey.attribute().getName(), changedKey.pair().different());
      add(cases, summaries,
          "exactly key " + (index + 1) + " differs", true, "WITNESS",
          dependencies,
          List.of(object(root, "unique_typed_key_a_" + index, same, null),
              object(root, "unique_typed_key_b_" + index, changed, null)),
          Map.of("same", same, "different", changed));

      if (!changedKey.attribute().getDomainOrDerivedDomain().isMandatoryConsideringAliases()) {
        Map<String, Object> undefined = new LinkedHashMap<>(same);
        undefined.remove(changedKey.attribute().getName());
        add(cases, summaries,
            "undefined typed UNIQUE key component " + changedKey.attribute().getName(),
            true, "WITNESS", dependencies,
            List.of(object(root, "unique_typed_undefined_a_" + index, undefined, null),
                object(root, "unique_typed_undefined_b_" + index, undefined, null)),
            undefined);
      }
    }

    List<Map<String, Object>> gaps = new ArrayList<>();
    boolean crossBasketReferenceAllowed = keys.stream()
        .filter(key -> key.referenceTarget() != null)
        .allMatch(Key::externalReference);
    if (crossBasketReferenceAllowed) {
      List<ConstraintTestTools.TestObject> crossDependencies = dependencies(keys, "basketA");
      add(cases, summaries,
          semantics.perBasket()
              ? "same typed UNIQUE key in different baskets"
              : "global typed UNIQUE duplicate across baskets",
          semantics.perBasket(), semantics.perBasket() ? "WITNESS" : "COUNTEREXAMPLE",
          crossDependencies,
          List.of(object(root, "unique_typed_cross_a", same, "basketA"),
              object(root, "unique_typed_cross_b", same, "basketB")), same);
    } else {
      gaps.add(Map.of(
          "reasonCode", "UNIQUE_REFERENCE_CROSS_BASKET_NOT_TRANSFERABLE",
          "reason", "Cross-basket equality for a non-EXTERNAL reference cannot be represented as valid transfer data.",
          "goal", "GLOBAL/BASKET boundary for reference key"));
    }
    return new UniqueConstraintCasePlanner.Plan(cases, summaries, gaps);
  }

  private static @Nullable AttributeDef attribute(PathEl element) {
    if (element instanceof AttributeRef ref) return ref.getAttr();
    if (element instanceof PathElRefAttr ref) return ref.getAttr();
    return null;
  }

  private static @Nullable Table concreteTarget(AbstractClassDef<?> target) {
    if (!(target instanceof Table table)) return null;
    if (!table.isAbstract()) return table;
    List<Table> concrete = new ArrayList<>();
    collectConcrete(table, concrete);
    return concrete.size() == 1 ? concrete.getFirst() : null;
  }

  private static void collectConcrete(Table parent, List<Table> concrete) {
    for (Object extension : parent.getExtensions()) {
      if (!(extension instanceof Table child)) continue;
      if (!child.isAbstract()) concrete.add(child);
      collectConcrete(child, concrete);
    }
  }

  private static List<ConstraintTestTools.TestObject> dependencies(
      List<Key> keys, @Nullable String basketId) {
    List<ConstraintTestTools.TestObject> result = new ArrayList<>();
    for (Key key : keys) {
      if (key.referenceTarget() == null) continue;
      result.add(object(key.referenceTarget(),
          ((TypedValueFixtureFactory.ReferenceValue) key.pair().same()).targetOid(), Map.of(), basketId));
      result.add(object(key.referenceTarget(),
          ((TypedValueFixtureFactory.ReferenceValue) key.pair().different()).targetOid(), Map.of(), basketId));
    }
    return result;
  }

  private static ConstraintTestTools.TestObject object(
      Table table, String oid, Map<String, Object> values, @Nullable String basketId) {
    ConstraintTestTools.TestObject object = new ConstraintTestTools.TestObject();
    object.classFqn = table.getScopedName(null);
    object.oid = oid;
    object.values = Map.copyOf(values);
    object.references = Map.of();
    object.basketId = basketId;
    return object;
  }

  private static void add(
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries,
      String name,
      boolean expected,
      String purpose,
      List<ConstraintTestTools.TestObject> dependencies,
      List<ConstraintTestTools.TestObject> subjects,
      Object values) {
    ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
    testCase.name = name;
    testCase.expectedConstraintValid = expected;
    List<ConstraintTestTools.TestObject> objects = new ArrayList<>(dependencies);
    objects.addAll(subjects);
    testCase.objects = List.copyOf(objects);
    testCase.links = List.of();
    cases.add(testCase);
    summaries.add(Map.of(
        "name", name,
        "purpose", purpose,
        "expectedConstraintValid", expected,
        "objectCount", objects.size(),
        "values", values));
  }

  private static UniqueConstraintCasePlanner.Plan gap(String code, String reason) {
    return new UniqueConstraintCasePlanner.Plan(
        List.of(), List.of(), List.of(Map.of(
            "reasonCode", code, "reason", reason, "goal", "typed UNIQUE proof")));
  }
}
