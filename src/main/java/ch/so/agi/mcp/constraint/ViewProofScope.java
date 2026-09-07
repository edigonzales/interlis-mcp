package ch.so.agi.mcp.constraint;

import ch.interlis.ili2c.metamodel.*;

import java.util.*;
import org.jspecify.annotations.Nullable;

/** Compiler-derived scope of direct identity projections supported by the pinned validator. */
public record ViewProofScope(Projection view, Table base, List<ConstraintExpression> filters) {
  public ViewProofScope { filters = List.copyOf(filters); }

  public static @Nullable ViewProofScope resolve(TransferDescription td, Constraint target) {
    if (!(target.getContainer() instanceof View)) return null;
    if (!(target.getContainer() instanceof Projection view)
        || !(view.getSelected().getAliasing() instanceof Table base)
        || base.isAbstract() || !base.isIdentifiable()
        || !(view.getContainer() instanceof Topic topic) || !topic.isViewTopic()) {
      throw failure("VIEW_PROOF_SHAPE_UNSUPPORTED", "View proof requires a direct projection of a concrete identifiable class in a VIEW TOPIC.");
    }
    if (target instanceof UniquenessConstraint u && (u.perBasket() || u.getLocal())
        || target instanceof SetConstraint s && s.perBasket()) {
      throw failure("VIEW_PROOF_SHAPE_UNSUPPORTED", "View UNIQUE/SET proof currently supports GLOBAL scope only.");
    }
    List<ConstraintExpression> filters = new ArrayList<>();
    var version = ConstraintAstTranslator.iliVersion(target);
    for (var iterator = view.iterator(); iterator.hasNext();) {
      var element = iterator.next();
      if (element instanceof LocalAttribute attribute && !attribute.isGeneratedByAllOf()
          && !(attribute.getName().equals(view.getSelected().getName())
              && Type.findReal(attribute.getDomain()) instanceof ObjectType objectType
              && objectType.getRef() == base)) {
        throw failure("VIEW_PROOF_SHAPE_UNSUPPORTED", "Only ALL OF identity attributes are supported: " + attribute.getScopedName());
      }
      if (element instanceof ExpressionSelection selection) {
        try {
          var expression = ConstraintAstTranslator.translateViewFilter(selection.getCondition(), version, view);
          if (containsImplication(expression)) throw failure("VALIDATOR_NATIVE_IMPLICATION_UNSUPPORTED", "Native implication in View selection is not proof-safe.");
          filters.add(expression);
        } catch (ConstraintAstTranslator.TranslationException ex) {
          throw failure("VIEW_FILTER_SEMANTICS_UNSUPPORTED", ex.getMessage());
        }
      } else if (element instanceof Selection) {
        throw failure("VIEW_FILTER_SEMANTICS_UNSUPPORTED", "Unsupported View selection.");
      }
    }
    return new ViewProofScope(view, base, filters);
  }

  private static boolean containsImplication(ConstraintExpression e) {
    return switch(e) {
      case ConstraintExpression.Implies ignored -> true;
      case ConstraintExpression.And a -> a.operands().stream().anyMatch(ViewProofScope::containsImplication);
      case ConstraintExpression.Or o -> o.operands().stream().anyMatch(ViewProofScope::containsImplication);
      case ConstraintExpression.Not n -> containsImplication(n.operand());
      case ConstraintExpression.Defined d -> containsImplication(d.operand());
      case ConstraintExpression.Comparison c -> containsImplication(c.left()) || containsImplication(c.right());
      case ConstraintExpression.FunctionCall f -> f.arguments().stream().anyMatch(ViewProofScope::containsImplication);
      default -> false;
    };
  }

  public boolean includes(Map<String,Object> assignment) {
    var normalized = new LinkedHashMap<String,Object>();
    assignment.forEach((name,value) -> normalized.put(name,value == null ? ConstraintExpressionEngine.Undefined.INSTANCE : value));
    var context = ConstraintExpressionEngine.EvaluationContext.of(normalized);
    for (var filter : filters) {
      Object value = ConstraintExpressionEngine.evaluate(filter, context);
      if (value == ConstraintExpressionEngine.NotComputable.INSTANCE) continue;
      if (!Boolean.TRUE.equals(value)) return false;
    }
    return true;
  }

  public ConstraintExpression footprint(ConstraintExpression expression) {
    var terms = new ArrayList<ConstraintExpression>();
    terms.add(expression.type().collection() ? new ConstraintExpression.BooleanLiteral(true) : new ConstraintExpression.Defined(expression));
    filters.forEach(f -> terms.add(new ConstraintExpression.Defined(f)));
    return new ConstraintExpression.And(terms);
  }

  public static ConstraintModelSynthesizer.ModelBinding bind(CompiledConstraintContext context,
      String contextFqn, ConstraintExpression expression) {
    return bind(context, contextFqn, expression, Map.of());
  }

  public static ConstraintModelSynthesizer.ModelBinding bind(CompiledConstraintContext context,
      String contextFqn, ConstraintExpression expression, Map<String,String> routes) {
    var scope = resolve(context.transferDescription(), context.constraint());
    if (scope == null) return ConstraintModelSynthesizer.bind(context.transferDescription(), contextFqn, expression, routes);
    var refs = new LinkedHashMap<>(ConstraintModelSynthesizer.bind(context.transferDescription(), scope.base().getScopedName(), expression, routes).references());
    for (var filter : scope.filters()) refs.putAll(ConstraintModelSynthesizer.bind(context.transferDescription(), scope.base().getScopedName(), filter).references());
    return new ConstraintModelSynthesizer.ModelBinding(scope.base().getScopedName(), refs, scope);
  }

  public CompiledConstraintContext planningContext(CompiledConstraintContext original) {
    String name = base.getScopedName();
    var s = original.semantics();
    SemanticConstraint mapped = switch(s) {
      case SemanticConstraint.Mandatory m -> new SemanticConstraint.Mandatory(m.constraintName(),m.constraintScopedName(),name,m.version(),m.condition());
      case SemanticConstraint.Plausibility p -> new SemanticConstraint.Plausibility(p.constraintName(),p.constraintScopedName(),name,p.version(),p.direction(),p.percentage(),p.condition());
      case SemanticConstraint.Unique u -> new SemanticConstraint.Unique(u.constraintName(),u.constraintScopedName(),name,u.version(),u.local(),u.perBasket(),u.preCondition(),u.prefix(),u.elements());
      case SemanticConstraint.Set t -> new SemanticConstraint.Set(t.constraintName(),t.constraintScopedName(),name,t.version(),t.perBasket(),t.preCondition(),
          t.condition() instanceof SemanticConstraint.ObjectCountSetCondition count && count.objects() instanceof SemanticConstraint.AllObjects all
              ? new SemanticConstraint.ObjectCountSetCondition(new SemanticConstraint.AllObjects(name,all.baseFqn(),all.restrictedToFqns()),count.operator(),count.threshold()) : t.condition());
      default -> throw failure("VIEW_PROOF_SHAPE_UNSUPPORTED", "Unsupported View constraint kind.");
    };
    return new CompiledConstraintContext(original.modelText(),original.modelRepositories(),original.compilation(),original.transferDescription(),original.constraint(),mapped);
  }

  public Map<String,Object> diagnostics() {
    return Map.of("viewFqn",view.getScopedName(),"baseClassFqn",base.getScopedName(),"filterCount",filters.size(),
        "filterPolicy","PINNED_VALIDATOR_SKIP_INCLUDES");
  }

  public static ScopeException failure(String code,String reason) { return new ScopeException(code,reason); }
  public static final class ScopeException extends IllegalArgumentException {
    private final String reasonCode;
    ScopeException(String code,String reason) { super(reason); reasonCode=code; }
    public String reasonCode() { return reasonCode; }
  }
}
