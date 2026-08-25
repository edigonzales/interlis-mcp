package ch.so.agi.mcp.constraint;

import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.Extendable;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Materializes validator fixtures for a compiled navigated object-set path. */
public final class NavigationGraphSynthesizer {

  private static final int MAX_POLYMORPHIC_ROUTES = 8;

  public record Binding(
      ConstraintModelSynthesizer.ModelBinding modelBinding,
      String syntheticValuePath,
      boolean collection,
      String routeTargetFqn) {
  }

  private NavigationGraphSynthesizer() {
  }

  public static Binding bind(
      TransferDescription td,
      String contextFqn,
      SemanticConstraint.NavigatedObjects objects) {
    List<Binding> bindings = bindAll(td, contextFqn, objects);
    if (bindings.size() > 1) {
      throw new IllegalArgumentException(
          "POLYMORPHIC_ROUTES_REQUIRE_DISTINCT_COVERAGE: path '"
              + objects.path().path() + "' has concrete targets "
              + bindings.stream().map(Binding::routeTargetFqn).toList());
    }
    return bindings.getFirst();
  }

  /** Creates one independently provable binding for every concrete final target route. */
  public static List<Binding> bindAll(
      TransferDescription td,
      String contextFqn,
      SemanticConstraint.NavigatedObjects objects) {
    if (objects.path().targetViewableFqn() == null) {
      throw new IllegalArgumentException(
          "Navigated object path has no compiled target viewable: " + objects.path().path());
    }
    Object target = td.getElement(objects.path().targetViewableFqn());
    if (!(target instanceof Table table)) {
      throw new IllegalArgumentException(
          "Navigated object path target is not a class or structure: "
              + objects.path().targetViewableFqn());
    }
    List<String> routeTargets = ConstraintModelSynthesizer.concreteTargetFqns(
        td, table.getScopedName(null));
    if (routeTargets.isEmpty()) {
      throw new IllegalArgumentException(
          "Navigation target is abstract and has no concrete subtype: "
              + table.getScopedName(null));
    }
    if (routeTargets.size() > MAX_POLYMORPHIC_ROUTES) {
      throw new IllegalArgumentException(
          "POLYMORPHIC_ROUTE_BUDGET_EXCEEDED: path '" + objects.path().path()
              + "' has " + routeTargets.size() + " concrete targets; maximum is "
              + MAX_POLYMORPHIC_ROUTES + ".");
    }
    AttributeDef endpoint = firstScalarAttribute(table);
    if (endpoint == null) {
      throw new IllegalArgumentException(
          "Navigated object path target has no scalar attribute usable as a fixture probe: "
              + table.getScopedName(null));
    }
    String path = objects.path().path() + "->" + endpoint.getName();
    ConstraintExpression.ScalarKind kind = scalarKind(endpoint.getDomainOrDerivedDomain());
    List<Binding> result = new ArrayList<>();
    for (String routeTarget : routeTargets) {
      Map<String, String> override = table.isAbstract()
          ? Map.of(table.getScopedName(null), routeTarget)
          : Map.of();
      try {
        ConstraintExpression.Path reference = new ConstraintExpression.Path(
            path, ConstraintExpression.Type.collection(kind));
        result.add(new Binding(
            ConstraintModelSynthesizer.bind(td, contextFqn, reference, override),
            path,
            true,
            routeTarget));
      } catch (IllegalArgumentException collectionFailure) {
        ConstraintExpression.Path reference = new ConstraintExpression.Path(
            path, ConstraintExpression.Type.scalar(kind));
        try {
          result.add(new Binding(
              ConstraintModelSynthesizer.bind(td, contextFqn, reference, override),
              path,
              false,
              routeTarget));
        } catch (IllegalArgumentException scalarFailure) {
          throw new IllegalArgumentException(
              "Unable to bind navigated object set through probe path '" + path
                  + "' for concrete target '" + routeTarget + "': "
                  + collectionFailure.getMessage() + " / " + scalarFailure.getMessage());
        }
      }
    }
    return List.copyOf(result);
  }

  public static ConstraintModelSynthesizer.ObjectGraph synthesize(
      Binding binding, int count, String oidPrefix) {
    if (count < 0) throw new IllegalArgumentException("Object-set count must be non-negative.");
    ConstraintModelSynthesizer.ReferenceBinding reference =
        binding.modelBinding().reference(binding.syntheticValuePath());
    Object value = fixtureValue(reference.domain());
    Object assignment;
    if (binding.collection()) {
      List<Object> values = new ArrayList<>();
      for (int i = 0; i < count; i++) values.add(value);
      assignment = List.copyOf(values);
    } else if (count == 0) {
      assignment = ConstraintExpressionEngine.Undefined.INSTANCE;
    } else if (count == 1) {
      assignment = value;
    } else {
      throw new IllegalArgumentException(
          "Single-valued navigated object set cannot materialize count " + count + ".");
    }
    return ConstraintModelSynthesizer.synthesize(
        binding.modelBinding(), Map.of(binding.syntheticValuePath(), assignment), oidPrefix);
  }

  private static AttributeDef firstScalarAttribute(Table table) {
    Iterator<Extendable> attributes = table.getAttributes();
    while (attributes.hasNext()) {
      Extendable element = attributes.next();
      if (!(element instanceof AttributeDef attribute)) continue;
      try {
        scalarKind(attribute.getDomainOrDerivedDomain());
        return attribute;
      } catch (IllegalArgumentException ignore) {
      }
    }
    return null;
  }

  private static ConstraintExpression.ScalarKind scalarKind(Type declared) {
    Type real = Type.findReal(declared);
    if (real instanceof ch.interlis.ili2c.metamodel.NumericType) {
      return ConstraintExpression.ScalarKind.NUMERIC;
    }
    if (real.isBoolean()) return ConstraintExpression.ScalarKind.BOOLEAN;
    if (real instanceof ch.interlis.ili2c.metamodel.EnumerationType) {
      return ConstraintExpression.ScalarKind.ENUM;
    }
    if (real instanceof ch.interlis.ili2c.metamodel.TextType text) {
      return text.isNormalized()
          ? ConstraintExpression.ScalarKind.TEXT : ConstraintExpression.ScalarKind.MTEXT;
    }
    throw new IllegalArgumentException("No scalar fixture domain: " + real.getClass().getSimpleName());
  }

  private static Object fixtureValue(ConstraintModelSynthesizer.ValueDomain domain) {
    return switch (domain.kind()) {
      case NUMERIC -> domain.numeric().minimum() != null
          ? domain.numeric().minimum()
          : domain.numeric().maximum() != null ? domain.numeric().maximum() : BigDecimal.ZERO;
      case BOOLEAN -> Boolean.TRUE;
      case ENUM -> {
        if (domain.values().isEmpty()) throw new IllegalArgumentException(
            "Enum object-set probe has no value.");
        yield domain.values().getFirst();
      }
      case TEXT, MTEXT -> "a";
      default -> throw new IllegalArgumentException(
          "Unsupported object-set probe kind: " + domain.kind());
    };
  }
}
