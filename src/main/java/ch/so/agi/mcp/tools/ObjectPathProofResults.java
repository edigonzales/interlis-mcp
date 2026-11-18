package ch.so.agi.mcp.tools;

import java.util.*;

/** Every concrete route must prove the same requested constraint; no best-route selection. */
final class ObjectPathProofResults {
  private ObjectPathProofResults() {}
  static Map<String,Object> merge(List<Map<String,Object>> results, List<Map<String,String>> routes) {
    if (results.size()==1) return results.getFirst();
    var merged=new LinkedHashMap<>(results.getFirst());
    for (String flag:List.of("automaticCasesAvailable","automaticCasesGenerated","generationVerified","coverageComplete"))
      merged.put(flag,results.stream().allMatch(r->Boolean.TRUE.equals(r.get(flag))));
    for(String field:List.of("coverageGoalCount","coverageSolvedCount","coverageExcludedCount"))
      merged.put(field,results.stream().mapToInt(r->((Number)r.getOrDefault(field,0)).intValue()).sum());
    for(String field:List.of("generatedCases","coverageUnsolved","coverageExcludedGoals","objectPathGoals","objectPathGaps","objectPathExcluded")) {
      var list=new ArrayList<Object>();
      for(int index=0;index<results.size();index++) {
        for(Object item:(List<?>)results.get(index).getOrDefault(field,List.of())) {
          var copy=new LinkedHashMap<>((Map<String,Object>)item);
          if(copy.get("name")!=null)copy.put("name","route "+(index+1)+": "+copy.get("name"));
          copy.put("concreteRoute",routes.get(index));list.add(copy);
        }
      }
      merged.put(field,list);
    }
    var verification=new LinkedHashMap<String,Object>();
    for(String field:List.of("cases","viewScopeGoals","viewScopeGaps","viewScopeExcluded")) {
      var list=new ArrayList<Object>();
      for(int index=0;index<results.size();index++) {
        var checked=(Map<?,?>)results.get(index).getOrDefault("verification",Map.of());
        Object raw=checked.get(field);
        if(raw instanceof List<?> items)for(var item:items) {
          var copy=new LinkedHashMap<>((Map<String,Object>)item);
          if(copy.get("name")!=null)copy.put("name","route "+(index+1)+": "+copy.get("name"));
          list.add(copy);
        }
      }
      verification.put(field,list);
    }
    verification.put("allPassed",Boolean.TRUE.equals(merged.get("generationVerified")));
    var cases=(List<Map<String,Object>>)verification.get("cases");
    verification.put("caseCount",cases.size());verification.put("passedCount",(int)cases.stream().filter(c->Boolean.TRUE.equals(c.get("passed"))).count());
    merged.put("verification",verification);
    for(var result:results)if(!Boolean.TRUE.equals(result.get("generationVerified")) || !Boolean.TRUE.equals(result.get("coverageComplete"))) {
      for(String field:List.of("reasonCode","reason","proofIncomplete"))if(result.containsKey(field))merged.put(field,result.get(field));
      break;
    }
    return merged;
  }
}
