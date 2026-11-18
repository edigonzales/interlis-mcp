package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.Table;
import ch.so.agi.mcp.constraint.*;
import java.util.*;

/** Real population probes for reached filter states, with frozen target assignments. */
final class ViewProofCoverage {
  private ViewProofCoverage() {}

  static Map<String,Object> verify(CompiledConstraintContext context,
      List<ConstraintTestTools.TestCase> original, ConstraintTestTools tools) {
    var scope=ViewProofScope.resolve(context.transferDescription(),context.constraint());
    if (scope==null) return tools.testCompiledConstraint(context,original);
    var cases=new ArrayList<>(ViewProofFixtures.prepare(context,original));
    var goals=new ArrayList<Map<String,Object>>();
    var gaps=new ArrayList<Map<String,Object>>();
    var excluded=new ArrayList<Map<String,Object>>();
    Set<String> targetRefs=targetReferences(context.semantics());
    for (int filter=0; filter<scope.filters().size();filter++) {
      var expression=scope.filters().get(filter);
      for (var goal: filterGoals(scope,expression,filter)) {
        var state=goal.kind();
        String name=goal.reason();
        var fullBinding=ViewProofScope.bind(context,scope.base().getScopedName(),expression);
        var prefix=new ViewProofScope(scope.view(),scope.base(),scope.filters().subList(0,filter));
        var binding=new ConstraintModelSynthesizer.ModelBinding(scope.base().getScopedName(),fullBinding.references(),prefix);
        boolean solved=false;
        for (var source: original) {
          if (source.objects.stream().noneMatch(o->o.classFqn.equals(scope.base().getScopedName()))) continue;
          var variant=ViewProofFixtures.prepare(context,List.of(source)).getFirst();
          int included=0, subjects=0;
          boolean available=true;
          for (var object:variant.objects) {
            if (!object.classFqn.equals(scope.base().getScopedName())) continue;
            subjects++;
            var fixed=new LinkedHashMap<String,Object>();
            for (var ref:binding.references().keySet()) {
              if (ref.contains("->")) throw ViewProofScope.failure("VIEW_FILTER_SEMANTICS_UNSUPPORTED","Filter fixture requires direct scalar attributes.");
              if (targetRefs.contains(ref)) fixed.put(ref, object.values.get(ref) == null
                  ? ConstraintExpressionEngine.Undefined.INSTANCE : object.values.get(ref));
            }
            var solution=ConstraintGoalSolver.solve(goal,binding,fixed);
            if (!solution.solved()) { available=false; break; }
            object.values=new LinkedHashMap<>(object.values);
            solution.assignment().forEach(object.values::put);
            if (scope.includes(solution.assignment())) included++;
          }
          if (!available || included>0 && included<subjects) continue;
          variant.name=name;
          if (included==0) {
            // Retain an exercised population. Excluded objects must not alter its outcome.
            var anchor=ViewProofFixtures.prepare(context,List.of(source)).getFirst();
            rename(variant);
            variant.objects.addAll(anchor.objects);
            variant.links.addAll(anchor.links);
            var counts = new LinkedHashMap<>(variant.plannedObjectCounts); counts.putAll(anchor.plannedObjectCounts); variant.plannedObjectCounts = counts;
            var shapes = new LinkedHashMap<>(variant.plannedObjectShapes); shapes.putAll(anchor.plannedObjectShapes); variant.plannedObjectShapes=shapes;
          }
          variant=ViewProofFixtures.prepare(context,List.of(variant),false).getFirst();
          cases.add(variant);
          goals.add(Map.of("name",name,"purpose","VIEW_FILTER_SCOPE","filterIndex",filter,
              "state",state.name(),"includedProbe",included>0,"expectedConstraintValid",variant.expectedConstraintValid));
          solved=true;
          break;
        }
        if (!solved) {
          var reachability=ConstraintGoalReachability.analyze(goal,binding);
          var item=Map.<String,Object>of("name",name,"reasonCode",
              reachability.status()==ConstraintGoalReachability.Status.PROVEN_UNREACHABLE ? "PROVEN_UNREACHABLE" : "VIEW_SCOPE_UNSOLVED",
              "reason",reachability.justification());
          if (reachability.status()==ConstraintGoalReachability.Status.PROVEN_UNREACHABLE) excluded.add(item); else gaps.add(item);
        }
      }
    }
    var result=new LinkedHashMap<>(tools.testCompiledConstraint(context,cases));
    var checked=(List<Map<String,Object>>)result.get("cases");
    for (int index=0;index<cases.size();index++) {
      int planned=0;
      for (var object:cases.get(index).objects) {
        var type=context.transferDescription().getElement(object.classFqn);
        if (type instanceof Table table && (table==scope.base() || table.isExtending(scope.base()))
            && scope.includes(object.values==null ? Map.of() : object.values)) planned++;
      }
      var item=checked.get(index);
      item.put("plannedSubjectCount",planned);
      if (!Integer.valueOf(planned).equals(item.get("subjectCount"))) {
        item.put("passed",false);
        item.put("fixturePreparationReasonCode","VIEW_SCOPE_VERIFICATION_FAILED");
        item.put("reason","Planned View membership differs from the materialized validator population.");
      }
    }
    result.put("passedCount", (int) checked.stream().filter(c -> Boolean.TRUE.equals(c.get("passed"))).count());
    result.put("allPassed",gaps.isEmpty() && checked.stream().allMatch(c->Boolean.TRUE.equals(c.get("passed"))));
    result.put("viewScopeGoals",goals);
    result.put("viewScopeGaps",gaps);
    result.put("viewScopeExcluded",excluded);
    return result;
  }

  private static List<ConstraintExpressionEngine.TestGoal> filterGoals(ViewProofScope scope, ConstraintExpression expression, int filter) {
    var goals=new ArrayList<ConstraintExpressionEngine.TestGoal>();
    for(var state:List.of(ConstraintExpressionEngine.GoalKind.TRUE,ConstraintExpressionEngine.GoalKind.FALSE,ConstraintExpressionEngine.GoalKind.UNDEFINED))
      goals.add(new ConstraintExpressionEngine.TestGoal(state,scope.footprint(expression),"View filter "+(filter+1)+" reached "+state,
          List.of(new ConstraintExpressionEngine.StateCondition(state,expression))));
    if(expression instanceof ConstraintExpression.Or alternatives) {
      var conditions=new ArrayList<ConstraintExpressionEngine.StateCondition>();
      for(int index=0;index<alternatives.operands().size();index++) {
        var branch=alternatives.operands().get(index);
        var reached=new ArrayList<>(conditions);reached.add(new ConstraintExpressionEngine.StateCondition(ConstraintExpressionEngine.GoalKind.TRUE,branch));
        goals.add(new ConstraintExpressionEngine.TestGoal(ConstraintExpressionEngine.GoalKind.TRUE,scope.footprint(expression),
            "View filter "+(filter+1)+" alternative "+(index+1),reached));
        conditions.add(new ConstraintExpressionEngine.StateCondition(ConstraintExpressionEngine.GoalKind.FALSE,branch));
      }
    }
    return goals;
  }

  static Set<String> targetReferences(SemanticConstraint semantics) {
    return switch(semantics) {
      case SemanticConstraint.Mandatory m -> names(m.condition());
      case SemanticConstraint.Plausibility p -> names(p.condition());
      case SemanticConstraint.Unique u -> u.elements().stream().map(SemanticConstraint.ConstraintPath::path).collect(java.util.stream.Collectors.toSet());
      default -> Set.of();
    };
  }
  private static Set<String> names(ConstraintExpression expression) {
    return expression.references().stream().map(ConstraintExpression.Reference::name).collect(java.util.stream.Collectors.toSet());
  }
  private static void rename(ConstraintTestTools.TestCase test) {
    var ids=new HashMap<String,String>();
    for (var o:test.objects) ids.put(o.oid,"scp"+o.oid.substring(3));
    for (var o:test.objects) {
      o.oid=ids.get(o.oid);
      if(o.references!=null)o.references.replaceAll((key,id)->ids.getOrDefault(id,id));
      o.values=renameValues(o.values,ids);
    }
    var counts = new LinkedHashMap<String,Map<String,Integer>>();
    test.plannedObjectCounts.forEach((oid,values) -> counts.put(ids.getOrDefault(oid,oid),values)); test.plannedObjectCounts=counts;
    var shapes=new LinkedHashMap<String,Map<String,ConstraintModelSynthesizer.CountShape>>();
    test.plannedObjectShapes.forEach((oid,values) -> shapes.put(ids.getOrDefault(oid,oid),values)); test.plannedObjectShapes=shapes;
    for(var link:test.links)link.roles.replaceAll((key,id)->ids.getOrDefault(id,id));
  }
  private static Map<String,Object> renameValues(Map<String,Object> values,Map<String,String> ids) {
    var result=new LinkedHashMap<String,Object>();
    values.forEach((key,value)->result.put(key,value instanceof TypedValueFixtureFactory.ReferenceValue r
        ? new TypedValueFixtureFactory.ReferenceValue(ids.getOrDefault(r.targetOid(),r.targetOid())) : value));
    return result;
  }
}
