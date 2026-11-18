package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.*;
import ch.so.agi.mcp.constraint.*;
import java.util.*;

/** Preparation of transfer objects for a compiled identity projection. Never creates View objects. */
final class ViewProofFixtures {
  private ViewProofFixtures() {}

  static List<ConstraintTestTools.TestCase> prepare(CompiledConstraintContext context,
      List<ConstraintTestTools.TestCase> cases) {
    return prepare(context, cases, true);
  }

  static List<ConstraintTestTools.TestCase> prepare(CompiledConstraintContext context,
      List<ConstraintTestTools.TestCase> cases, boolean selectMembers) {
    var scope = ViewProofScope.resolve(context.transferDescription(), context.constraint());
    if (scope == null) return cases;
    var result = new ArrayList<ConstraintTestTools.TestCase>();
    for (var original : cases) {
      var test = new ConstraintTestTools.TestCase();
      test.name = original.name;
      test.expectedConstraintValid = original.expectedConstraintValid;
      test.objects = new ArrayList<>();
      test.links = new ArrayList<>();
      for (var source : original.objects) {
        var object = new ConstraintTestTools.TestObject();
        object.classFqn=source.classFqn; object.oid=source.oid; object.basketId=source.basketId == null ? null : basketId(source.classFqn, source.basketId);
        object.values=new LinkedHashMap<>(source.values == null ? Map.of() : source.values);
        object.references=new LinkedHashMap<>(source.references == null ? Map.of() : source.references);
        if (selectMembers && source.classFqn.equals(scope.base().getScopedName()) && !scope.filters().isEmpty()) {
          var binding = ViewProofScope.bind(context, scope.base().getScopedName(),new ConstraintExpression.BooleanLiteral(true));
          var fixed = new LinkedHashMap<String,Object>();
          for (var name : binding.references().keySet()) {
            if (name.contains("->")) throw ViewProofScope.failure("VIEW_FILTER_SEMANTICS_UNSUPPORTED", "Automatic filter fixtures currently require direct scalar attributes.");
            if (object.values.containsKey(name) || ViewProofCoverage.targetReferences(context.semantics()).contains(name)) fixed.put(name, object.values.get(name) == null
                ? ConstraintExpressionEngine.Undefined.INSTANCE : object.values.get(name));
          }
          var solution=ConstraintGoalSolver.solve(new ConstraintExpressionEngine.TestGoal(
              ConstraintExpressionEngine.GoalKind.TRUE,new ConstraintExpression.BooleanLiteral(true),"View included member"),binding,fixed);
          if (!solution.solved()) throw ViewProofScope.failure("VIEW_SCOPE_UNSOLVED",solution.reason());
          solution.assignment().forEach((name,value) -> { if (!object.values.containsKey(name)) object.values.put(name,value); });
        }
        test.objects.add(object);
      }
      if (original.links != null) for (var source : original.links) {
        var link = new ConstraintTestTools.TestLink(); link.associationFqn=source.associationFqn;
        link.basketId=source.basketId == null ? null : basketId(source.associationFqn, source.basketId); link.roles=new LinkedHashMap<>(source.roles); test.links.add(link);
      }
      // Choose explicit concrete fixtures for mandatory abstract relationship targets. These
      // remain ordinary data objects and are included in subsequent membership verification.
      for (int i=0; i<test.objects.size();i++) {
        var object=test.objects.get(i);
        var table=(Table)context.transferDescription().getElement(object.classFqn);
        var roles=table.getOpposideRoles();
        while (roles.hasNext()) {
          var role=roles.next();
          if (role.getCardinality().getMinimum()==0 || !(role.getDestination() instanceof Table target)
              || !target.isAbstract()) continue;
          var concrete = new ArrayList<Table>();
          for (Object extension : target.getExtensions()) if (extension instanceof Table t && !t.isAbstract() && t.isIdentifiable()) concrete.add(t);
          concrete.sort(Comparator.comparing(Table::getScopedName));
          if (concrete.isEmpty()) throw ViewProofScope.failure("VIEW_FIXTURE_MATERIALIZATION_FAILED","No concrete mandatory relationship target.");
          if (test.objects.stream().anyMatch(o -> o.classFqn.equals(concrete.getFirst().getScopedName()) && java.util.Objects.equals(o.basketId, object.basketId))) continue;
          if (test.objects.size()>=64) throw ViewProofScope.failure("VIEW_FIXTURE_BUDGET_EXCEEDED","View fixture exceeds 64 explicit objects.");
          var dependency=new ConstraintTestTools.TestObject(); dependency.classFqn=concrete.getFirst().getScopedName();
          dependency.oid="view_dependency_"+test.objects.size(); dependency.values=Map.of(); dependency.basketId=object.basketId;
          test.objects.add(dependency);
        }
      }
      var oids=new LinkedHashMap<String,String>();
      int index=0;
      for (var object:test.objects) {
        var table=(Table)context.transferDescription().getElement(object.classFqn);
        String oid=table.getOid()==PredefinedModel.getInstance().UUIDOID
            ? UUID.nameUUIDFromBytes((object.classFqn+":"+object.oid).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()
            : String.format("mcp%013d",++index);
        oids.put(object.oid,oid);
      }
      for (var object:test.objects) {
        object.oid=oids.get(object.oid);
        object.values=(Map<String,Object>)remap(object.values,oids);
        if (object.references != null) object.references.replaceAll((name,oid)->oids.getOrDefault(oid,oid));
      }
      for (var link:test.links) link.roles.replaceAll((name,oid)->oids.getOrDefault(oid,oid));
      var planned = new LinkedHashMap<String,Map<String,Integer>>();
      original.plannedObjectCounts.forEach((oid,counts) -> planned.put(oids.getOrDefault(oid,oid), counts));
      test.plannedObjectCounts = planned;
      var shapes = new LinkedHashMap<String,Map<String,ConstraintModelSynthesizer.CountShape>>();
      original.plannedObjectShapes.forEach((oid,values) -> shapes.put(oids.getOrDefault(oid,oid),values)); test.plannedObjectShapes=shapes;
      result.add(test);
    }
    return result;
  }

  private static String basketId(String elementFqn, String basketId) {
    String prefix = elementFqn.substring(0, elementFqn.lastIndexOf('.')).replace('.', '_') + "_";
    return basketId.startsWith(prefix) ? basketId : prefix + basketId;
  }

  private static Object remap(Object value,Map<String,String> oids) {
    if (value == ConstraintExpressionEngine.Undefined.INSTANCE) return null;
    if (value instanceof TypedValueFixtureFactory.ReferenceValue ref) return new TypedValueFixtureFactory.ReferenceValue(oids.getOrDefault(ref.targetOid(),ref.targetOid()));
    if (value instanceof Map<?,?> map) {
      var result=new LinkedHashMap<String,Object>(); map.forEach((key,item)->result.put(key.toString(),remap(item,oids))); return result;
    }
    if (value instanceof List<?> list) return list.stream().map(item->remap(item,oids)).toList();
    return value;
  }
}
