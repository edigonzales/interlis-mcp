package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.*;
import ch.interlis.iom.IomObject;
import ch.interlis.iox_j.validator.Validator;
import java.util.*;
import java.util.function.Consumer;

/** Reads the original compiler expressions against the completed validator object pool. */
final class ObjectCountVerification {
  private ObjectCountVerification() {}

  static List<Map<String,Object>> verify(TransferDescription td, Constraint target, Validator validator,
      List<? extends IomObject> objects, Map<String,Map<String,Integer>> planned,
      Map<String,Map<String,ch.so.agi.mcp.constraint.ConstraintModelSynthesizer.CountShape>> shapes, Consumer<String> error) {
    var calls = new ArrayList<FunctionCall>();
    collect(target.getCondition(), calls, Collections.newSetFromMap(new IdentityHashMap<>()));
    var results = new ArrayList<Map<String,Object>>();
    if (calls.isEmpty()) return results;
    Viewable<?> context = (Viewable<?>)target.getContainer();
    Viewable<?> base = context instanceof Projection view ? view.getSelected().getAliasing() : context;
    for (var object : objects) {
      if (!(td.getElement(object.getobjecttag()) instanceof Table type) || !(type == base || type.isExtending(base))) continue;
      for (var call : calls) {
        var path = (ObjectPath)call.getArguments()[0];
        String name = path.toString();
        try {
          var count = validator.evaluateExpression(null, null, target.getScopedName(), object, call, null);
          var population = validator.evaluateExpression(null, null, target.getScopedName(), object, path, null);
          if (count.skipEvaluation() || count.isUndefined() || count.isNotYetImplemented()) throw new IllegalArgumentException("Object count did not evaluate.");
          int actual = Math.toIntExact((long)count.getNumeric());
          var identities = new LinkedHashSet<String>();
          var routes = new LinkedHashSet<String>();
          if (population.getComplexObjects() != null) for (var member : population.getComplexObjects()) {
            identities.add(member.getobjecttag() + ":" + member.getobjectoid()); routes.add(member.getobjecttag());
          }
          else if (population.getOid() != null) { identities.add(population.getOid()); if (population.getViewable()!=null) routes.add(population.getViewable().getScopedName()); }
          var result = new LinkedHashMap<String,Object>();
          result.put("contextOid", object.getobjectoid()); result.put("objectPath", name);
          result.put("actualCount", actual); result.put("distinctTargetCount", identities.size());
          result.put("routeTargetFqns", List.copyOf(routes)); result.put("countingPolicy", "PINNED_VALIDATOR_PATH_OCCURRENCES");
          var typeRoute = new ArrayList<Map<String,Object>>();
          var shape=shapes.getOrDefault(object.getobjectoid(),Map.of()).get(name);
          boolean shapeValid=true;
          for(int step=0;step<path.getPathElements().length;step++) {
            var prefix=new ObjectPath(path.getRoot(),Arrays.copyOf(path.getPathElements(),step+1));
            var reached=validator.evaluateExpression(null,null,target.getScopedName(),object,prefix,null);
            var types=new TreeSet<String>();
            if(reached.getComplexObjects()!=null) for(var member:reached.getComplexObjects())types.add(member.getobjecttag());
            else if(reached.getOid()!=null && reached.getViewable()!=null)types.add(reached.getViewable().getScopedName());
            int size=reached.getComplexObjects()!=null?reached.getComplexObjects().size():reached.getOid()!=null?1:0;
            typeRoute.add(Map.of("step",step,"navigation",path.getPathElements()[step].getName(),"concreteTypes",List.copyOf(types),"occurrenceCount",size));
            if(shape!=null) {
              if(step==shape.emptyStep()) shapeValid &= size==0;
              if(step<shape.emptyStep()) shapeValid &= size>0;
              if(step==shape.branchStep()) shapeValid &= size>=2;
              if(shape.mixedTypes().containsKey(step)) shapeValid &= types.size()>=2 && types.contains(shape.mixedTypes().get(step));
            }
          }
          result.put("concreteTypeRoute",typeRoute);
          if(shape!=null) {
            if(shape.sharedTargets()) shapeValid &= actual>identities.size();
            else if(shape.branchStep()>=0) shapeValid &= actual==identities.size();
            result.put("topologyVerified",shapeValid);
            if(!shapeValid)error.accept("OBJECT_PATH_TOPOLOGY_MISMATCH: "+name);
          }
          Integer expected = planned.getOrDefault(object.getobjectoid(), Map.of()).get(name);
          if (expected != null) {
            result.put("plannedCount", expected);
            if (expected != actual) error.accept("OBJECT_PATH_COUNT_MISMATCH: " + name + " planned=" + expected + " actual=" + actual);
          }
          results.add(result);
        } catch (Exception ex) { error.accept("OBJECT_PATH_VERIFICATION_FAILED: " + name + ": " + ex.getMessage()); }
      }
    }
    return results;
  }

  private static void collect(Evaluable value, List<FunctionCall> calls, Set<Evaluable> seen) {
    if (value == null || !seen.add(value)) return;
    if (value instanceof FunctionCall call && "INTERLIS.objectCount".equals(call.getFunction().getScopedName())
        && call.getArguments().length == 1 && call.getArguments()[0] instanceof ObjectPath) calls.add(call);
    // All traversed compiler methods are zero-argument Evaluable getters. No source-text parsing.
    for (var method : value.getClass().getMethods()) {
      if (method.getParameterCount() != 0 || !method.getName().startsWith("get")) continue;
      var type = method.getReturnType();
      if (!Evaluable.class.isAssignableFrom(type) && !(type.isArray() && Evaluable.class.isAssignableFrom(type.componentType()))) continue;
      try {
        Object child = method.invoke(value);
        if (child instanceof Evaluable nested) collect(nested, calls, seen);
        else if (child instanceof Evaluable[] nested) for (var item : nested) collect(item, calls, seen);
      } catch (ReflectiveOperationException ex) { throw new IllegalArgumentException("Unable to inspect compiler expression.", ex); }
    }
  }
}
