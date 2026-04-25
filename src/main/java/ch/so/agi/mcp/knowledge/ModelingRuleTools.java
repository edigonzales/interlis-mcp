package ch.so.agi.mcp.knowledge;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelPurpose;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ModelingRuleTools {

  private final KnowledgeRuleLoader ruleLoader;
  private final ModelAnalysisTools analysisTools;

  public ModelingRuleTools(KnowledgeRuleLoader ruleLoader, ModelAnalysisTools analysisTools) {
    this.ruleLoader = ruleLoader;
    this.analysisTools = analysisTools;
  }

  @McpTool(
      name = "listModelingRules",
      description = "Listet die kuratierten INTERLIS-Modellierungsregeln mit id, title, severity, appliesTo und checkKind auf."
  )
  public Map<String, Object> listModelingRules() {
    return Map.of("rules", ruleLoader.rules().stream().map(this::ruleSummary).toList());
  }

  @McpTool(
      name = "checkModelingRules",
      description = "Prueft ein INTERLIS-Modell gegen kuratierte Modellierungsregeln. Automatisierte Findings und manuelle Checks werden getrennt ausgewiesen."
  )
  public Map<String, Object> checkModelingRules(
      @McpToolParam(description = "INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Modellzweck: CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false) @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition", required = false) @Nullable String modelRepositories,
      @McpToolParam(description = "Optionale Regel-IDs, die geprueft werden sollen", required = false) @Nullable List<String> ruleIds
  ) {
    ModelPurpose purpose = ModelPurpose.normalize(modelPurpose);
    Map<String, Object> analysis = analysisTools.analyzeIliModel(modelText, modelRepositories, purpose);
    List<Map<String, Object>> findings = new ArrayList<>();
    List<Map<String, Object>> manualChecks = new ArrayList<>();
    Set<String> selectedRuleIds = normalizeRuleIds(ruleIds);

    for (ModelingRule rule : ruleLoader.rules()) {
      if (!selectedRuleIds.isEmpty() && !selectedRuleIds.contains(rule.id())) {
        continue;
      }
      if (!applies(rule, purpose)) {
        continue;
      }
      if (rule.checkKind() == ModelingRule.CheckKind.MANUAL) {
        manualChecks.add(ruleFinding(rule, rule.rationale(), null));
      }
    }

    addCompilerFindings(findings, selectedRuleIds, analysis);
    addPublicationAssociationFinding(findings, selectedRuleIds, purpose, analysis);
    addGeometryConventionFinding(findings, selectedRuleIds, analysis);
    addMetaAttributeFinding(findings, selectedRuleIds, analysis);

    boolean validForAutomatedRules = findings.stream()
        .noneMatch(finding -> "ERROR".equals(finding.get("severity")) || "WARNING".equals(finding.get("severity")));
    return Map.of(
        "validForAutomatedRules", validForAutomatedRules,
        "findings", findings,
        "manualChecks", manualChecks
    );
  }

  private void addCompilerFindings(List<Map<String, Object>> findings, Set<String> selectedRuleIds, Map<String, Object> analysis) {
    if (!isSelected(selectedRuleIds, "MDE-020")) {
      return;
    }
    ModelingRule rule = rule("MDE-020");
    if (Boolean.TRUE.equals(analysis.get("valid"))) {
      return;
    }
    Object messages = analysis.get("messages");
    String message = "ili2c validation failed.";
    if (messages instanceof List<?> list && !list.isEmpty()) {
      message = "ili2c validation failed: " + list.getFirst();
    }
    findings.add(ruleFinding(rule, message, null));
  }

  private void addPublicationAssociationFinding(
      List<Map<String, Object>> findings,
      Set<String> selectedRuleIds,
      ModelPurpose purpose,
      Map<String, Object> analysis) {
    if (!isSelected(selectedRuleIds, "MDE-010") || purpose != ModelPurpose.PUBLICATION) {
      return;
    }
    List<?> associations = listValue(analysis, "associations");
    if (!associations.isEmpty()) {
      findings.add(ruleFinding(rule("MDE-010"),
          "Publication model contains " + associations.size() + " association(s).",
          firstLocation(associations)));
    }
  }

  private void addGeometryConventionFinding(List<Map<String, Object>> findings, Set<String> selectedRuleIds, Map<String, Object> analysis) {
    if (!isSelected(selectedRuleIds, "MDE-030")) {
      return;
    }
    List<?> attributes = listValue(analysis, "attributes");
    boolean hasGeometry = attributes.stream().anyMatch(item -> item instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("geometry")));
    if (!hasGeometry) {
      return;
    }

    String haystack = (analysis.get("imports") + " " + analysis.get("domains") + " " + analysis.get("attributes")).toLowerCase(Locale.ROOT);
    if (!(haystack.contains("lv95") || haystack.contains("chlv95") || haystack.contains("epsg:2056") || haystack.contains("2056"))) {
      findings.add(ruleFinding(rule("MDE-030"),
          "Geometry attributes are present, but no LV95/CHLV95 convention is visible.",
          firstGeometryLocation(attributes)));
    }
  }

  private void addMetaAttributeFinding(List<Map<String, Object>> findings, Set<String> selectedRuleIds, Map<String, Object> analysis) {
    if (!isSelected(selectedRuleIds, "MDE-060")) {
      return;
    }
    Set<String> present = new LinkedHashSet<>();
    for (Object item : listValue(analysis, "metaAttributes")) {
      if (item instanceof Map<?, ?> map && map.get("name") != null) {
        present.add(map.get("name").toString());
      }
    }
    List<String> missing = new ArrayList<>();
    for (String required : List.of("title", "shortDescription", "technicalContact")) {
      if (!present.contains(required)) {
        missing.add(required);
      }
    }
    if (!missing.isEmpty()) {
      findings.add(ruleFinding(rule("MDE-060"),
          "Missing model meta attribute(s): " + String.join(", ", missing) + ".",
          null));
    }
  }

  private Map<String, Object> ruleSummary(ModelingRule rule) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", rule.id());
    map.put("title", rule.title());
    map.put("severity", rule.severity().name());
    map.put("appliesTo", rule.appliesTo().name());
    map.put("checkKind", rule.checkKind().name());
    return map;
  }

  private Map<String, Object> ruleFinding(ModelingRule rule, String message, @Nullable Object location) {
    Map<String, Object> map = ruleSummary(rule);
    map.put("message", message);
    map.put("recommendation", rule.recommendation());
    map.put("sourceUrl", rule.sourceUrl());
    if (location != null) {
      map.put("location", location);
    }
    return map;
  }

  private ModelingRule rule(String id) {
    return ruleLoader.rules().stream()
        .filter(rule -> id.equals(rule.id()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Missing rule " + id));
  }

  private boolean applies(ModelingRule rule, ModelPurpose purpose) {
    return rule.appliesTo() == ModelPurpose.ANY
        || purpose == ModelPurpose.UNKNOWN
        || rule.appliesTo() == purpose;
  }

  private Set<String> normalizeRuleIds(@Nullable List<String> ruleIds) {
    Set<String> selected = new LinkedHashSet<>();
    if (ruleIds != null) {
      for (String ruleId : ruleIds) {
        if (ruleId != null && !ruleId.isBlank()) {
          selected.add(ruleId.trim());
        }
      }
    }
    return selected;
  }

  private boolean isSelected(Set<String> selectedRuleIds, String ruleId) {
    return selectedRuleIds.isEmpty() || selectedRuleIds.contains(ruleId);
  }

  private List<?> listValue(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof List<?> list ? list : List.of();
  }

  private @Nullable Object firstLocation(List<?> elements) {
    return elements.stream()
        .filter(Map.class::isInstance)
        .map(Map.class::cast)
        .findFirst()
        .map(map -> map.getOrDefault("scopedName", map.get("name")))
        .orElse(null);
  }

  private @Nullable Object firstGeometryLocation(List<?> attributes) {
    return attributes.stream()
        .filter(item -> item instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("geometry")))
        .map(Map.class::cast)
        .findFirst()
        .map(map -> map.getOrDefault("scopedName", map.get("name")))
        .orElse(null);
  }
}
