package ch.so.agi.mcp.constraint;

import ch.interlis.ili2c.metamodel.TransferDescription;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Direct object-valued navigation, shared with Mandatory and Decision Table proofs. */
public final class NavigationGraphSynthesizer {
  public record Binding(ConstraintModelSynthesizer.ModelBinding modelBinding, String countKey,
      boolean collection, String routeTargetFqn) {}
  private NavigationGraphSynthesizer() {}

  public static Binding bind(TransferDescription td, String contextFqn, SemanticConstraint.NavigatedObjects objects) {
    var routes = bindAll(td,contextFqn,objects);
    if (routes.size() != 1) throw new IllegalArgumentException("POLYMORPHIC_ROUTES_REQUIRE_DISTINCT_COVERAGE");
    return routes.getFirst();
  }
  public static List<Binding> bindAll(TransferDescription td, String contextFqn, SemanticConstraint.NavigatedObjects objects) {
    var count = new ConstraintExpression.ObjectCount(objects.path().path());
    return ObjectPathRoutes.resolve(td,contextFqn,count).stream().map(route -> {
      var model = ConstraintModelSynthesizer.bind(td,contextFqn,count,route);
      var reference = model.reference(count.key());
      return new Binding(model,count.key(),reference.navigation().stream().anyMatch(ConstraintModelSynthesizer.NavigationBinding::multiValued),
          reference.navigation().getLast().targetClassFqn());
    }).toList();
  }
  public static ConstraintModelSynthesizer.ObjectGraph synthesize(Binding binding,int count,String prefix) {
    return ConstraintModelSynthesizer.synthesize(binding.modelBinding(),Map.of(binding.countKey(),BigDecimal.valueOf(count)),prefix);
  }
}
