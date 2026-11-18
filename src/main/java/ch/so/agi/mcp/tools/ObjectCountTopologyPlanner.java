package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.*;
import ch.so.agi.mcp.constraint.*;
import java.math.BigDecimal;
import java.util.*;

/** Graph obligations use the same assignments, search budget, evaluator and real fixture verifier. */
final class ObjectCountTopologyPlanner {
  record Plan(List<ConstraintTestTools.TestCase> cases, List<Map<String,Object>> summaries,
      List<Map<String,Object>> gaps, List<Map<String,Object>> excluded) {}
  private ObjectCountTopologyPlanner() {}

  static Plan plan(CompiledConstraintContext context, ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding) {
    var cases=new ArrayList<ConstraintTestTools.TestCase>();
    var summaries=new ArrayList<Map<String,Object>>();
    var gaps=new ArrayList<Map<String,Object>>();
    var excluded=new ArrayList<Map<String,Object>>();
    var routes=ObjectPathRoutes.resolve(context.transferDescription(), binding.contextFqn(), expression);
    for (var ref:binding.references().values()) {
      if(ref.reference().kind()!=ConstraintExpression.ReferenceKind.OBJECT_COUNT) continue;
      String path=ConstraintModelSynthesizer.countedPath(ref.reference());
      var steps=ref.navigation();
      for(int i=0;i<steps.size();i++) {
        var step=steps.get(i);
        if(step.minimum()==0) probe(context,expression,binding,ref,
            new ConstraintModelSynthesizer.CountShape(path,-1,i,false,Map.of()), "EMPTY_STEP_"+i, cases,summaries,gaps,excluded);
        if(!step.multiValued()) continue;
        probe(context,expression,binding,ref,new ConstraintModelSynthesizer.CountShape(path,i,-1,false,Map.of()),
            "DISTINCT_BRANCH_STEP_"+i,cases,summaries,gaps,excluded);
        int stepIndex=i;
        var alternatives=new TreeSet<String>();
        routes.forEach(route->{String type=route.get(path+"#"+stepIndex);if(type!=null && !type.equals(step.targetClassFqn()))alternatives.add(type);});
        for(String alternative:alternatives) probe(context,expression,binding,ref,
            new ConstraintModelSynthesizer.CountShape(path,i,-1,false,Map.of(i,alternative)),
            "MIXED_TYPES_STEP_"+i+"_"+alternative,cases,summaries,gaps,excluded);
        if(i<steps.size()-1) {
          var last=steps.getLast();
          String impossible=null;
          if(last.kind()==ConstraintModelSynthesizer.NavigationKind.ASSOCIATION) {
            var association=(AssociationDef)context.transferDescription().getElement(last.association().associationFqn());
            var role=(RoleDef)association.getElement(RoleDef.class,last.association().oppositeRoleName());
            if(role.getCardinality().getMaximum()<2)impossible="The opposite role permits at most one predecessor for a shared target.";
          }
          if(impossible!=null) excluded.add(Map.of("goal","SHARED_TARGET_STEP_"+i,"objectPath",path,"reasonCode","PROVEN_UNREACHABLE","justification",impossible));
          else probe(context,expression,binding,ref,new ConstraintModelSynthesizer.CountShape(path,i,-1,true,Map.of()),
              "SHARED_TARGET_STEP_"+i,cases,summaries,gaps,excluded);
        }
      }
    }
    return new Plan(cases,summaries,gaps,excluded);
  }

  private static void probe(CompiledConstraintContext context, ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding, ConstraintModelSynthesizer.ReferenceBinding ref,
      ConstraintModelSynthesizer.CountShape shape, String name,
      List<ConstraintTestTools.TestCase> cases,List<Map<String,Object>> summaries,
      List<Map<String,Object>> gaps,List<Map<String,Object>> excluded) {
    String path=shape.path();
    int requiredStep=Math.max(shape.branchStep(),shape.emptyStep());
    for(int i=0;i<requiredStep;i++) if(!ref.navigation().get(i).unbounded() && ref.navigation().get(i).maximum()==0) {
      excluded.add(Map.of("goal",name,"objectPath",path,"reasonCode","PROVEN_UNREACHABLE","justification","A preceding compiled role has maximum zero."));return;
    }
    var comparison=new ConstraintExpression.Comparison(shape.emptyStep()>=0?ConstraintExpression.ComparisonOperator.EQ:ConstraintExpression.ComparisonOperator.GE,
        new ConstraintExpression.ObjectCount(path),new ConstraintExpression.NumericLiteral(BigDecimal.valueOf(shape.emptyStep()>=0?0:2)));
    var goal=new ConstraintExpressionEngine.TestGoal(ConstraintExpressionEngine.GoalKind.TRUE,
        new ConstraintExpression.And(List.of(new ConstraintExpression.Defined(expression),comparison)),name,
        List.of(new ConstraintExpressionEngine.StateCondition(ConstraintExpressionEngine.GoalKind.TRUE,comparison)));
    var solution=ConstraintGoalSolver.solve(goal,binding,Map.of(),shape);
    if(!solution.solved()) { gaps.add(Map.of("goal",name,"objectPath",path,"reasonCode",solution.reasonCode(),"reason",solution.reason()));return; }
    try {
      var graph=ConstraintModelSynthesizer.synthesize(binding,solution.assignment(),"topology_"+(cases.size()+1),shape);
      var typeRoute=ref.navigation().stream().map(ConstraintModelSynthesizer.NavigationBinding::targetClassFqn).toList();
      var c=toCase(name+" "+path+" via "+String.join(" -> ",typeRoute),ConstraintExpressionEngine.evaluateConstraint(expression,ConstraintExpressionEngine.EvaluationContext.of(solution.assignment())),graph,binding,solution.assignment());
      c.plannedObjectShapes=Map.of(graph.objects().getFirst().oid(),Map.of(path,shape));
      cases.add(c);
      summaries.add(Map.of("name",c.name,"purpose","OBJECT_PATH_TOPOLOGY","goal",name,"objectPath",path,
          "expectedConstraintValid",c.expectedConstraintValid,"values",solution.assignment(),
          "concreteTypeRoute",typeRoute,
          "coveredGoals",List.of(Map.of("goal","OBJECT_PATH_TOPOLOGY","reason",name,"expression","Internal graph obligation for "+path)),
          "countingPolicy","PINNED_VALIDATOR_PATH_OCCURRENCES"));
    } catch(IllegalArgumentException ex) {gaps.add(Map.of("goal",name,"objectPath",path,"reasonCode","OBJECT_PATH_MATERIALIZATION_FAILED","reason",ex.getMessage()));}
  }

  static ConstraintTestTools.TestCase toCase(String name, boolean expected,ConstraintModelSynthesizer.ObjectGraph graph,
      ConstraintModelSynthesizer.ModelBinding binding,Map<String,Object> assignment) {
    var c=new ConstraintTestTools.TestCase();c.name=name;c.expectedConstraintValid=expected;
    c.objects=graph.objects().stream().map(o->{var value=new ConstraintTestTools.TestObject();value.classFqn=o.classFqn();value.oid=o.oid();value.values=o.values();value.references=o.references();return value;}).toList();
    c.links=graph.links().stream().map(l->{var value=new ConstraintTestTools.TestLink();value.associationFqn=l.associationFqn();value.roles=l.roles();return value;}).toList();
    var counts=new LinkedHashMap<String,Integer>();
    for(var reference:binding.references().values())if(reference.reference().kind()==ConstraintExpression.ReferenceKind.OBJECT_COUNT)
      counts.put(ConstraintModelSynthesizer.countedPath(reference.reference()),new BigDecimal(assignment.get(reference.reference().name()).toString()).intValueExact());
    c.plannedObjectCounts=Map.of(graph.objects().getFirst().oid(),counts);return c;
  }
}
