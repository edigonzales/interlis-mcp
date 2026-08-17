package ch.so.agi.mcp.knowledge;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ModelingRuleTools {

  private static final Pattern VERSION_DECLARATION =
      Pattern.compile("\\bVERSION\\s+\"[^\"]+\"", Pattern.CASE_INSENSITIVE);

  private final KnowledgeRuleLoader ruleLoader;
  private final ModelAnalysisTools analysisTools;
  private final IliCompilerService compilerService;

  public ModelingRuleTools(
      KnowledgeRuleLoader ruleLoader,
      ModelAnalysisTools analysisTools,
      IliCompilerService compilerService) {
    this.ruleLoader = ruleLoader;
    this.analysisTools = analysisTools;
    this.compilerService = compilerService;
  }

  @McpTool(
      name = "listModelingRules",
      description = "Katalog-Tool fuer die kuratierten INTERLIS-Modellierungsregeln mit id, title, severity, appliesTo und checkKind. Verwenden, um verfuegbare Regeln oder Regel-IDs zu verstehen. Nicht zum Pruefen eines Modells verwenden; dafuer reviewIliModel oder fuer gezielte Regelpruefungen checkModelingRules."
  )
  public Map<String, Object> listModelingRules(
      @McpToolParam(description = "Regelprofil: CORE oder SO (Default CORE)", required = false) @Nullable ModelingRuleProfile profile
  ) {
    ModelingRuleProfile normalizedProfile = ModelingRuleProfile.normalize(profile);
    return Map.of(
        "profile", normalizedProfile.name(),
        "rules", ruleLoader.rules(normalizedProfile).stream().map(this::ruleSummary).toList());
  }

  @McpTool(
      name = "checkModelingRules",
      description = "Low-Level-Tool fuer gezielte Modellierungsregel-Diagnosen, insbesondere einzelne ruleIds oder wenn nur Regel-Findings benoetigt werden. Automatisierte Findings und manuelle Checks werden getrennt ausgewiesen. Nicht als Standardreview eines vollstaendigen Modells verwenden; dafuer reviewIliModel."
  )
  public Map<String, Object> checkModelingRules(
      @McpToolParam(description = "INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Modellzweck: CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false) @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition", required = false) @Nullable String modelRepositories,
      @McpToolParam(description = "Optionale Regel-IDs, die geprueft werden sollen", required = false) @Nullable List<String> ruleIds,
      @McpToolParam(description = "Regelprofil: CORE oder SO (Default CORE)", required = false) @Nullable ModelingRuleProfile profile
  ) {
    ModelPurpose purpose = ModelPurpose.normalize(modelPurpose);
    ModelingRuleProfile normalizedProfile = ModelingRuleProfile.normalize(profile);
    Map<String, Object> analysis = analysisTools.analyzeIliModel(modelText, modelRepositories, purpose);
    return checkAnalyzedModel(modelText, purpose, analysis, ruleIds, normalizedProfile);
  }

  @McpTool(
      name = "reviewIliModel",
      description = "Standard-Tool fuer Baseline- und Abschlussreview eines vollstaendigen aktuellen INTERLIS-Modells. Kompiliert genau einmal und kombiniert Compilerdiagnostik, Struktur, automatisierte Modellierungsregeln, manuelle Checks und offene fachliche Fragen. Nicht routinemaessig zusaetzlich analyzeIliModel, checkModelingRules oder validateIliModel aufrufen; diese nur fuer gezielte Detaildiagnosen."
  )
  public Map<String, Object> reviewIliModel(
      @McpToolParam(description = "INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Modellzweck: CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false) @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Regelprofil: CORE oder SO (Default CORE)", required = false) @Nullable ModelingRuleProfile ruleProfile,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition", required = false) @Nullable String modelRepositories
  ) {
    ModelPurpose purpose = ModelPurpose.normalize(modelPurpose);
    ModelingRuleProfile profile = ModelingRuleProfile.normalize(ruleProfile);

    IliCompilerService.CompilationResult compilation =
        compilerService.compile(modelText, modelRepositories, "ili2c_review_");
    ModelAnalysisTools.AnalysisData data =
        analysisTools.analyzeCompiled(compilation.transferDescription(), modelText);
    Map<String, Object> analysis = analysisTools.toResponse(
        compilation.valid(), compilation.messages(), data, purpose);
    Map<String, Object> review = reviewAnalyzedModel(modelText, purpose, profile, analysis);

    Map<String, Object> structure = new LinkedHashMap<>(analysis);
    structure.remove("valid");
    structure.remove("messages");
    structure.remove("summaryMarkdown");

    boolean validForAutomatedRules = Boolean.TRUE.equals(review.get("validForAutomatedRules"));
    boolean valid = compilation.valid() && validForAutomatedRules;

    return Map.ofEntries(
        Map.entry("valid", valid),
        Map.entry("compilerValid", compilation.valid()),
        Map.entry("validForAutomatedRules", validForAutomatedRules),
        Map.entry("modelPurpose", review.get("modelPurpose")),
        Map.entry("ruleProfile", review.get("ruleProfile")),
        Map.entry("compilerDiagnostics", compilation.messages()),
        Map.entry("structure", structure),
        Map.entry("ruleFindings", review.get("ruleFindings")),
        Map.entry("manualChecks", review.get("manualChecks")),
        Map.entry("openQuestions", review.get("openQuestions"))
    );
  }

  public Map<String, Object> reviewAnalyzedModel(
      String modelText,
      @Nullable ModelPurpose modelPurpose,
      @Nullable ModelingRuleProfile ruleProfile,
      Map<String, Object> analysis) {
    ModelPurpose purpose = ModelPurpose.normalize(modelPurpose);
    ModelingRuleProfile profile = ModelingRuleProfile.normalize(ruleProfile);
    Map<String, Object> ruleReview = checkAnalyzedModel(modelText, purpose, analysis, null, profile);

    List<Map<String, Object>> openQuestions = new ArrayList<>();
    if (purpose == ModelPurpose.UNKNOWN) {
      openQuestions.add(Map.of(
          "kind", "MODEL_PURPOSE",
          "question", "What is the intended model purpose?",
          "options", List.of("CAPTURE", "PUBLICATION", "VALIDATION"),
          "reason", "Some modeling rules depend on whether the model is used for capture, publication or validation."));
    }

    return Map.ofEntries(
        Map.entry("validForAutomatedRules", ruleReview.get("validForAutomatedRules")),
        Map.entry("modelPurpose", purpose.name()),
        Map.entry("ruleProfile", profile.name()),
        Map.entry("ruleFindings", ruleReview.get("findings")),
        Map.entry("manualChecks", ruleReview.get("manualChecks")),
        Map.entry("openQuestions", openQuestions));
  }

  private Map<String, Object> checkAnalyzedModel(
      String modelText,
      ModelPurpose purpose,
      Map<String, Object> analysis,
      @Nullable List<String> ruleIds,
      ModelingRuleProfile profile) {
    List<Map<String, Object>> findings = new ArrayList<>();
    List<Map<String, Object>> manualChecks = new ArrayList<>();
    Set<String> selectedRuleIds = normalizeRuleIds(ruleIds);
    List<ModelingRule> activeRules = ruleLoader.rules(profile);
    Map<String, ModelingRule> rulesById = new LinkedHashMap<>();
    for (ModelingRule rule : activeRules) {
      rulesById.put(rule.id(), rule);
    }

    for (ModelingRule rule : activeRules) {
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

    addCompilerFindings(findings, selectedRuleIds, analysis, rulesById);
    addPublicationAssociationFinding(findings, selectedRuleIds, purpose, analysis, rulesById);
    addGeometryConventionFinding(findings, selectedRuleIds, analysis, rulesById);
    addMetaAttributeFinding(findings, selectedRuleIds, analysis, rulesById);
    addTabCharacterFinding(findings, selectedRuleIds, modelText, rulesById);
    addModelVersionFindings(findings, selectedRuleIds, modelText, analysis, rulesById);
    addDocumentationFindings(findings, selectedRuleIds, analysis, rulesById, "MDE-209", "attributes", "Attribute");
    addDocumentationFindings(findings, selectedRuleIds, analysis, rulesById, "MDE-210", "classes", "Class");
    addNameLengthFindings(findings, selectedRuleIds, analysis, rulesById);
    addViewPurposeFindings(findings, selectedRuleIds, purpose, analysis, rulesById);
    addTextLengthFindings(findings, selectedRuleIds, analysis, rulesById);
    addRoleCardinalityFindings(findings, selectedRuleIds, analysis, rulesById);
    addObjectIdentificationFindings(findings, selectedRuleIds, analysis, rulesById);

    boolean validForAutomatedRules = findings.stream()
        .noneMatch(finding -> "ERROR".equals(finding.get("severity")) || "WARNING".equals(finding.get("severity")));
    return Map.of(
        "profile", profile.name(),
        "validForAutomatedRules", validForAutomatedRules,
        "findings", findings,
        "manualChecks", manualChecks
    );
  }

  private void addCompilerFindings(List<Map<String, Object>> findings, Set<String> selectedRuleIds, Map<String, Object> analysis, Map<String, ModelingRule> rulesById) {
    if (!isSelected(selectedRuleIds, "MDE-020")) {
      return;
    }
    ModelingRule rule = rule("MDE-020", rulesById);
    if (rule == null) {
      return;
    }
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
      Map<String, Object> analysis,
      Map<String, ModelingRule> rulesById) {
    if (!isSelected(selectedRuleIds, "MDE-010") || purpose != ModelPurpose.PUBLICATION) {
      return;
    }
    List<?> associations = listValue(analysis, "associations");
    if (!associations.isEmpty()) {
      ModelingRule rule = rule("MDE-010", rulesById);
      if (rule == null) {
        return;
      }
      findings.add(ruleFinding(rule,
          "Publication model contains " + associations.size() + " association(s).",
          firstLocation(associations)));
    }
  }

  private void addGeometryConventionFinding(
      List<Map<String, Object>> findings,
      Set<String> selectedRuleIds,
      Map<String, Object> analysis,
      Map<String, ModelingRule> rulesById) {
    if (!isSelected(selectedRuleIds, "MDE-030")) {
      return;
    }
    ModelingRule rule = rule("MDE-030", rulesById);
    if (rule == null) {
      return;
    }
    List<?> attributes = listValue(analysis, "attributes");
    boolean hasGeometry = attributes.stream().anyMatch(item -> item instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("geometry")));
    if (!hasGeometry) {
      return;
    }

    String haystack = (analysis.get("imports") + " " + analysis.get("domains") + " " + analysis.get("attributes")).toLowerCase(Locale.ROOT);
    if (!(haystack.contains("lv95") || haystack.contains("chlv95") || haystack.contains("epsg:2056") || haystack.contains("2056"))) {
      findings.add(ruleFinding(rule,
          "Geometry attributes are present, but no LV95/CHLV95 convention is visible.",
          firstGeometryLocation(attributes)));
    }
  }

  private void addMetaAttributeFinding(
      List<Map<String, Object>> findings,
      Set<String> selectedRuleIds,
      Map<String, Object> analysis,
      Map<String, ModelingRule> rulesById) {
    if (!isSelected(selectedRuleIds, "MDE-060")) {
      return;
    }
    ModelingRule rule = rule("MDE-060", rulesById);
    if (rule == null) {
      return;
    }

    Set<String> modelOwners = new LinkedHashSet<>();
    for (Object item : listValue(analysis, "models")) {
      if (item instanceof Map<?, ?> map && map.get("scopedName") != null) {
        modelOwners.add(map.get("scopedName").toString());
      }
    }

    Set<String> present = new LinkedHashSet<>();
    for (Object item : listValue(analysis, "metaAttributes")) {
      if (item instanceof Map<?, ?> map
          && map.get("name") != null
          && map.get("owner") != null
          && modelOwners.contains(map.get("owner").toString())) {
        present.add(map.get("name").toString());
      }
    }
    List<String> missing = new ArrayList<>();
    for (String required : List.of("furtherInformation", "technicalContact", "title", "shortDescription")) {
      if (!present.contains(required)) {
        missing.add(required);
      }
    }
    if (!missing.isEmpty()) {
      findings.add(ruleFinding(rule,
          "Missing model meta attribute(s): " + String.join(", ", missing) + ".",
          null));
    }
  }

  private void addTabCharacterFinding(
      List<Map<String, Object>> findings,
      Set<String> selectedRuleIds,
      String modelText,
      Map<String, ModelingRule> rulesById) {
    if (!isSelected(selectedRuleIds, "MDE-206")) {
      return;
    }
    ModelingRule rule = rule("MDE-206", rulesById);
    if (rule == null) {
      return;
    }

    List<Integer> tabLines = new ArrayList<>();
    String[] lines = modelText.split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].indexOf('\t') >= 0) {
        tabLines.add(i + 1);
        if (tabLines.size() >= 20) {
          break;
        }
      }
    }
    if (tabLines.isEmpty()) {
      return;
    }

    findings.add(ruleFinding(
        rule,
        "Model contains tab character(s) on line(s): " + join(tabLines) + ".",
        Map.of("lines", tabLines)));
  }

  private void addModelVersionFindings(
      List<Map<String, Object>> findings,
      Set<String> selectedRuleIds,
      String modelText,
      Map<String, Object> analysis,
      Map<String, ModelingRule> rulesById) {
    if (!isSelected(selectedRuleIds, "MDE-208")) {
      return;
    }
    ModelingRule rule = rule("MDE-208", rulesById);
    if (rule == null) {
      return;
    }

    List<?> models = listValue(analysis, "models");
    if (models.isEmpty()) {
      if (!VERSION_DECLARATION.matcher(modelText).find()) {
        findings.add(ruleFinding(rule, "Model source has no VERSION declaration.", null));
      }
      return;
    }

    for (Object item : models) {
      if (item instanceof Map<?, ?> map && isBlank(map.get("version"))) {
        findings.add(ruleFinding(rule,
            "Model has no VERSION declaration.",
            elementLocation(map)));
      }
    }
  }

  private void addDocumentationFindings(
      List<Map<String, Object>> findings,
      Set<String> selectedRuleIds,
      Map<String, Object> analysis,
      Map<String, ModelingRule> rulesById,
      String ruleId,
      String category,
      String label) {
    if (!isSelected(selectedRuleIds, ruleId)) {
      return;
    }
    ModelingRule rule = rule(ruleId, rulesById);
    if (rule == null) {
      return;
    }
    for (Object item : listValue(analysis, category)) {
      if (item instanceof Map<?, ?> map && isBlank(map.get("documentation"))) {
        findings.add(ruleFinding(rule,
            label + " has no documentation comment.",
            elementLocation(map)));
      }
    }
  }

  private void addNameLengthFindings(
      List<Map<String, Object>> findings,
      Set<String> selectedRuleIds,
      Map<String, Object> analysis,
      Map<String, ModelingRule> rulesById) {
    if (!isSelected(selectedRuleIds, "MDE-302")) {
      return;
    }
    ModelingRule rule = rule("MDE-302", rulesById);
    if (rule == null) {
      return;
    }
    for (String category : List.of("topics", "classes", "associations", "attributes")) {
      for (Object item : listValue(analysis, category)) {
        if (item instanceof Map<?, ?> map && map.get("name") != null) {
          String name = map.get("name").toString();
          if (name.length() > 29) {
            findings.add(ruleFinding(rule,
                "Model element name has " + name.length() + " characters: " + name + ".",
                elementLocation(map)));
          }
        }
      }
    }
  }

  private void addViewPurposeFindings(
      List<Map<String, Object>> findings,
      Set<String> selectedRuleIds,
      ModelPurpose purpose,
      Map<String, Object> analysis,
      Map<String, ModelingRule> rulesById) {
    if (!isSelected(selectedRuleIds, "MDE-501")
        || purpose == ModelPurpose.VALIDATION
        || purpose == ModelPurpose.UNKNOWN) {
      return;
    }
    ModelingRule rule = rule("MDE-501", rulesById);
    if (rule == null) {
      return;
    }
    for (Object item : listValue(analysis, "views")) {
      if (item instanceof Map<?, ?> map) {
        findings.add(ruleFinding(rule,
            "VIEW is only allowed by the modeling handbook in validation models.",
            elementLocation(map)));
      }
    }
  }

  private void addTextLengthFindings(
      List<Map<String, Object>> findings,
      Set<String> selectedRuleIds,
      Map<String, Object> analysis,
      Map<String, ModelingRule> rulesById) {
    if (!isSelected(selectedRuleIds, "MDE-502")) {
      return;
    }
    ModelingRule rule = rule("MDE-502", rulesById);
    if (rule == null) {
      return;
    }
    for (String category : List.of("domains", "attributes")) {
      for (Object item : listValue(analysis, category)) {
        if (item instanceof Map<?, ?> map && map.get("typeText") != null) {
          String typeText = map.get("typeText").toString();
          if ("TEXT".equals(typeText) || "MTEXT".equals(typeText)) {
            findings.add(ruleFinding(rule,
                "Unbounded " + typeText + " type has no explicit maximum length.",
                elementLocation(map)));
          }
        }
      }
    }
  }

  private void addRoleCardinalityFindings(
      List<Map<String, Object>> findings,
      Set<String> selectedRuleIds,
      Map<String, Object> analysis,
      Map<String, ModelingRule> rulesById) {
    if (!isSelected(selectedRuleIds, "MDE-601")) {
      return;
    }
    ModelingRule rule = rule("MDE-601", rulesById);
    if (rule == null) {
      return;
    }
    for (Object associationItem : listValue(analysis, "associations")) {
      if (!(associationItem instanceof Map<?, ?> association)) {
        continue;
      }
      Object rolesValue = association.get("roles");
      if (!(rolesValue instanceof List<?> roles)) {
        continue;
      }
      for (Object roleItem : roles) {
        if (roleItem instanceof Map<?, ?> role && !Boolean.TRUE.equals(role.get("cardinalityDefined"))) {
          Object cardinality = role.get("cardinality");
          findings.add(ruleFinding(rule,
              "Association role relies on the implicit cardinality "
                  + (cardinality != null ? cardinality : "{1}") + ".",
              elementLocation(role)));
        }
      }
    }
  }

  private void addObjectIdentificationFindings(
      List<Map<String, Object>> findings,
      Set<String> selectedRuleIds,
      Map<String, Object> analysis,
      Map<String, ModelingRule> rulesById) {
    if (!isSelected(selectedRuleIds, "MDE-603")) {
      return;
    }
    ModelingRule rule = rule("MDE-603", rulesById);
    if (rule == null) {
      return;
    }
    for (Object item : listValue(analysis, "classes")) {
      if (item instanceof Map<?, ?> map
          && !Boolean.TRUE.equals(map.get("abstract"))
          && isBlank(map.get("oid"))) {
        findings.add(ruleFinding(rule,
            "Concrete class has no effective OID domain.",
            elementLocation(map)));
      }
    }
  }

  private boolean isBlank(@Nullable Object value) {
    return value == null || value.toString().isBlank();
  }

  private @Nullable Object elementLocation(Map<?, ?> map) {
    Object scopedName = map.get("scopedName");
    Object line = map.get("line");
    if (scopedName != null && line != null) {
      return Map.of("scopedName", scopedName, "line", line);
    }
    if (scopedName != null) {
      return scopedName;
    }
    return line;
  }

  private String join(List<Integer> values) {
    return values.stream().map(String::valueOf).reduce((left, right) -> left + ", " + right).orElse("");
  }

  private Map<String, Object> ruleSummary(ModelingRule rule) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("profile", rule.profile().name());
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

  private @Nullable ModelingRule rule(String id, Map<String, ModelingRule> rulesById) {
    return rulesById.get(id);
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
