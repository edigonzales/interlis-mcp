package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.IliAuthoringResult.CoverageExclusion;
import ch.so.agi.mcp.model.IliAuthoringResult.CoverageGap;
import ch.so.agi.mcp.model.IliAuthoringResult.CoverageGoal;
import ch.so.agi.mcp.model.IliAuthoringResult.ProofCase;
import ch.interlis.ili2c.metamodel.*;
import ch.so.agi.mcp.constraint.*;
import java.math.BigDecimal;
import java.util.*;

/** Graph obligations use the same assignments, search budget, evaluator and real fixture verifier. */
final class ObjectCountTopologyPlanner {
  record Plan(List<ConstraintTestTools.TestCase> cases, List<ProofCase> summaries,
      List<CoverageGap> gaps, List<CoverageExclusion> excluded) {}
  private ObjectCountTopologyPlanner() {}

  static Plan plan(TransferDescription td, ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding, List<Map<String, String>> routes) {
    var cases=new ArrayList<ConstraintTestTools.TestCase>();
    var summaries = new ArrayList<ProofCase>();
    var gaps = new ArrayList<CoverageGap>();
    var excluded = new ArrayList<CoverageExclusion>();
    for (var ref:binding.references().values()) {
      if(ref.reference().kind()!=ConstraintExpression.ReferenceKind.OBJECT_COUNT) continue;
      String path=ConstraintModelSynthesizer.countedPath(ref.reference());
      var steps=ref.navigation();
      for(int i=0;i<steps.size();i++) {
        var step=steps.get(i);
        if(step.minimum()==0) probe(expression,binding,ref,
            new ConstraintModelSynthesizer.CountShape(path,-1,i,false,Map.of()), "EMPTY_STEP_"+i, cases,summaries,gaps,excluded);
        if(!step.multiValued()) continue;
        probe(expression,binding,ref,new ConstraintModelSynthesizer.CountShape(path,i,-1,false,Map.of()),
            "DISTINCT_BRANCH_STEP_"+i,cases,summaries,gaps,excluded);
        int stepIndex=i;
        var alternatives=new TreeSet<String>();
        routes.forEach(route->{String type=route.get(path+"#"+stepIndex);if(type!=null && !type.equals(step.targetClassFqn()))alternatives.add(type);});
        for(String alternative:alternatives) probe(expression,binding,ref,
            new ConstraintModelSynthesizer.CountShape(path,i,-1,false,Map.of(i,alternative)),
            "MIXED_TYPES_STEP_"+i+"_"+alternative,cases,summaries,gaps,excluded);
        if(i<steps.size()-1) {
          var last=steps.getLast();
          String impossible=null;
          if(last.kind()==ConstraintModelSynthesizer.NavigationKind.ASSOCIATION) {
            var association=(AssociationDef)td.getElement(last.association().associationFqn());
            var role=(RoleDef)association.getElement(RoleDef.class,last.association().oppositeRoleName());
            if(role.getCardinality().getMaximum()<2)impossible="The opposite role permits at most one predecessor for a shared target.";
          }
          if(impossible!=null) excluded.add(excluded("SHARED_TARGET_STEP_" + i, path, impossible));
          else probe(expression,binding,ref,new ConstraintModelSynthesizer.CountShape(path,i,-1,true,Map.of()),
              "SHARED_TARGET_STEP_"+i,cases,summaries,gaps,excluded);
        }
      }
    }
    return new Plan(cases,summaries,gaps,excluded);
  }

  private static void probe(ConstraintExpression expression,
      ConstraintModelSynthesizer.ModelBinding binding, ConstraintModelSynthesizer.ReferenceBinding ref,
      ConstraintModelSynthesizer.CountShape shape, String name,
      List<ConstraintTestTools.TestCase> cases,List<ProofCase> summaries,
      List<CoverageGap> gaps,List<CoverageExclusion> excluded) {
    String path=shape.path();
    int requiredStep=Math.max(shape.branchStep(),shape.emptyStep());
    for(int i=0;i<requiredStep;i++) if(!ref.navigation().get(i).unbounded() && ref.navigation().get(i).maximum()==0) {
      excluded.add(excluded(name, path, "A preceding compiled role has maximum zero."));return;
    }
    var comparison=new ConstraintExpression.Comparison(shape.emptyStep()>=0?ConstraintExpression.ComparisonOperator.EQ:ConstraintExpression.ComparisonOperator.GE,
        new ConstraintExpression.ObjectCount(path),new ConstraintExpression.NumericLiteral(BigDecimal.valueOf(shape.emptyStep()>=0?0:2)));
    var goal=new ConstraintExpressionEngine.TestGoal(ConstraintExpressionEngine.GoalKind.TRUE,
        new ConstraintExpression.And(List.of(new ConstraintExpression.Defined(expression),comparison)),name,
        List.of(new ConstraintExpressionEngine.StateCondition(ConstraintExpressionEngine.GoalKind.TRUE,comparison)));
    var solution=ConstraintGoalSolver.solve(goal,binding,Map.of(),shape);
    if(!solution.solved()) { gaps.add(gap(name, path, solution.reasonCode(), solution.reason()));return; }
    try {
      var graph=ConstraintModelSynthesizer.synthesize(binding,solution.assignment(),"topology_"+(cases.size()+1),shape);
      var typeRoute=ref.navigation().stream().map(ConstraintModelSynthesizer.NavigationBinding::targetClassFqn).toList();
      var c=toCountedCase(name+" "+path+" via "+String.join(" -> ",typeRoute),ConstraintExpressionEngine.evaluateConstraint(expression,ConstraintExpressionEngine.EvaluationContext.of(solution.assignment())),graph,binding,solution.assignment());
      c.plannedObjectShapes=Map.of(graph.objects().getFirst().oid(),Map.of(path,shape));
      cases.add(c);
      var summary = new ProofCase(c.name, "OBJECT_PATH_TOPOLOGY", c.expectedConstraintValid);
      summary.setSummaryValues(solution.assignment());
      summary.coveredGoals = List.of(new CoverageGoal("OBJECT_PATH_TOPOLOGY", name, "Internal graph obligation for " + path));
      summary.details.put("goal", name);
      summary.details.put("objectPath", path);
      summary.details.put("concreteTypeRoute", typeRoute);
      summary.details.put("countingPolicy", "PINNED_VALIDATOR_PATH_OCCURRENCES");
      summaries.add(summary);
    } catch (IllegalArgumentException ex) {
      gaps.add(gap(name, path, "OBJECT_PATH_MATERIALIZATION_FAILED", ex.getMessage()));
    }
  }

  private static CoverageGap gap(String goal, String path, String code, String reason) {
    var gap = new CoverageGap(code, reason, goal);
    gap.details.put("objectPath", path);
    return gap;
  }

  private static CoverageExclusion excluded(String goal, String path, String justification) {
    var exclusion = new CoverageExclusion(goal, null, "PROVEN_UNREACHABLE", justification);
    exclusion.details.put("objectPath", path);
    return exclusion;
  }

  static ConstraintTestTools.TestCase toCountedCase(
      String name, boolean expected, ConstraintModelSynthesizer.ObjectGraph graph,
      ConstraintModelSynthesizer.ModelBinding binding, Map<String,Object> assignment) {
    var testCase = ConstraintTestTools.TestCase.fromGraph(name, expected, graph);
    var counts = new LinkedHashMap<String, Integer>();
    for (var reference : binding.references().values()) {
      if (reference.reference().kind() == ConstraintExpression.ReferenceKind.OBJECT_COUNT) {
        var value = assignment.get(reference.reference().name());
        counts.put(ConstraintModelSynthesizer.countedPath(reference.reference()),
            new BigDecimal(value.toString()).intValueExact());
      }
    }
    testCase.plannedObjectCounts = Map.of(graph.objects().getFirst().oid(), counts);
    return testCase;
  }
}
