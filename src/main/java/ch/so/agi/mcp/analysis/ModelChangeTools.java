package ch.so.agi.mcp.analysis;

import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ModelChangeTools {

  private static final List<String> CATEGORIES = List.of(
      "models", "imports", "topics", "classes", "structures", "domains", "units", "associations", "attributes");
  private static final Set<String> IDENTITY_FIELDS = Set.of("kind", "name", "scopedName");
  private static final Set<String> IGNORED_FIELDS = Set.of("line");

  private final IliCompilerService compilerService;
  private final ModelAnalysisTools analysisTools;
  private final ModelingRuleTools ruleTools;

  public ModelChangeTools(
      IliCompilerService compilerService,
      ModelAnalysisTools analysisTools,
      ModelingRuleTools ruleTools) {
    this.compilerService = compilerService;
    this.analysisTools = analysisTools;
    this.ruleTools = ruleTools;
  }

  @McpTool(
      name = "reviewIliChange",
      description = "Standard-Tool, wenn ein Vorher- und ein Nachher-Stand eines vollstaendigen INTERLIS-Modells vorliegen. Vergleicht beide semantisch, kompiliert jede Version genau einmal und prueft das After-Modell gegen die Modellierungsregeln. Rueckgabe: added, removed, changed, potentiallyBreakingChanges und afterReview. Nicht fuer einen einzelnen Modellstand verwenden; dafuer reviewIliModel."
  )
  public Map<String, Object> reviewIliChange(
      @McpToolParam(description = "INTERLIS-2 Modelltext vor der Aenderung", required = true) String beforeModelText,
      @McpToolParam(description = "INTERLIS-2 Modelltext nach der Aenderung", required = true) String afterModelText,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition fuer beide Versionen", required = false) @Nullable String modelRepositories,
      @McpToolParam(description = "Modellzweck fuer das Review des After-Modells: CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false) @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Regelprofil fuer das Review des After-Modells: CORE oder SO (Default CORE)", required = false) @Nullable ModelingRuleProfile ruleProfile
  ) {
    ModelPurpose purpose = ModelPurpose.normalize(modelPurpose);
    ModelingRuleProfile profile = ModelingRuleProfile.normalize(ruleProfile);

    IliCompilerService.CompilationResult beforeCompilation =
        compilerService.compile(beforeModelText, modelRepositories, "ili2c_change_before_");
    IliCompilerService.CompilationResult afterCompilation =
        compilerService.compile(afterModelText, modelRepositories, "ili2c_change_after_");

    if (!afterCompilation.valid()) {
      return notComparableResponse(
          beforeCompilation,
          afterCompilation,
          unavailableAfterReview(purpose, profile));
    }

    ModelAnalysisTools.AnalysisData after =
        analysisTools.analyzeCompiled(afterCompilation.transferDescription(), afterModelText);
    Map<String, Object> afterAnalysis =
        analysisTools.toResponse(true, afterCompilation.messages(), after, purpose);
    Map<String, Object> afterReview =
        ruleTools.reviewAnalyzedModel(afterModelText, purpose, profile, afterAnalysis);

    if (!beforeCompilation.valid()) {
      return notComparableResponse(beforeCompilation, afterCompilation, afterReview);
    }

    ModelAnalysisTools.AnalysisData before =
        analysisTools.analyzeCompiled(beforeCompilation.transferDescription(), beforeModelText);

    List<Map<String, Object>> added = new ArrayList<>();
    List<Map<String, Object>> removed = new ArrayList<>();
    List<Map<String, Object>> changed = new ArrayList<>();

    if (!Objects.equals(before.iliVersion, after.iliVersion)) {
      changed.add(Map.of(
          "kind", "ILI_VERSION",
          "scopedName", "INTERLIS",
          "changedFields", List.of("iliVersion"),
          "before", Map.of("iliVersion", before.iliVersion),
          "after", Map.of("iliVersion", after.iliVersion)));
    }

    for (String category : CATEGORIES) {
      diffCategory(category, categoryItems(before, category), categoryItems(after, category), added, removed, changed);
    }

    List<Map<String, Object>> potentiallyBreaking = potentiallyBreaking(added, removed, changed);
    boolean hasChanges = !added.isEmpty() || !removed.isEmpty() || !changed.isEmpty();
    String impact = potentiallyBreaking.isEmpty()
        ? (hasChanges ? "ADDITIVE_OR_METADATA_ONLY" : "NONE")
        : "POTENTIALLY_BREAKING";

    return Map.ofEntries(
        Map.entry("valid", true),
        Map.entry("comparable", true),
        Map.entry("beforeCompilerValid", true),
        Map.entry("afterCompilerValid", true),
        Map.entry("beforeDiagnostics", beforeCompilation.messages()),
        Map.entry("afterDiagnostics", afterCompilation.messages()),
        Map.entry("hasChanges", hasChanges),
        Map.entry("added", added),
        Map.entry("removed", removed),
        Map.entry("changed", changed),
        Map.entry("potentiallyBreakingChanges", potentiallyBreaking),
        Map.entry("impact", impact),
        Map.entry("afterReview", afterReview),
        Map.entry("summary", summary(added.size(), removed.size(), changed.size(), potentiallyBreaking.size())),
        Map.entry("limitations", limitations())
    );
  }

  private Map<String, Object> notComparableResponse(
      IliCompilerService.CompilationResult beforeCompilation,
      IliCompilerService.CompilationResult afterCompilation,
      Map<String, Object> afterReview) {
    return Map.ofEntries(
        Map.entry("valid", false),
        Map.entry("comparable", false),
        Map.entry("beforeCompilerValid", beforeCompilation.valid()),
        Map.entry("afterCompilerValid", afterCompilation.valid()),
        Map.entry("beforeDiagnostics", beforeCompilation.messages()),
        Map.entry("afterDiagnostics", afterCompilation.messages()),
        Map.entry("added", List.of()),
        Map.entry("removed", List.of()),
        Map.entry("changed", List.of()),
        Map.entry("potentiallyBreakingChanges", List.of()),
        Map.entry("impact", "UNKNOWN"),
        Map.entry("afterReview", afterReview),
        Map.entry("summary", summary(0, 0, 0, 0)),
        Map.entry("limitations", limitations())
    );
  }

  private Map<String, Object> unavailableAfterReview(
      ModelPurpose purpose,
      ModelingRuleProfile profile) {
    return Map.of(
        "available", false,
        "modelPurpose", purpose.name(),
        "ruleProfile", profile.name(),
        "reason", "The after model must compile before modeling rules can be reviewed.");
  }

  private void diffCategory(
      String category,
      List<Map<String, Object>> beforeItems,
      List<Map<String, Object>> afterItems,
      List<Map<String, Object>> added,
      List<Map<String, Object>> removed,
      List<Map<String, Object>> changed) {
    Map<String, Map<String, Object>> beforeByKey = index(category, beforeItems);
    Map<String, Map<String, Object>> afterByKey = index(category, afterItems);

    for (Map.Entry<String, Map<String, Object>> entry : beforeByKey.entrySet()) {
      Map<String, Object> afterItem = afterByKey.get(entry.getKey());
      if (afterItem == null) {
        removed.add(entry.getValue());
        continue;
      }

      List<String> changedFields = changedFields(entry.getValue(), afterItem);
      if (!changedFields.isEmpty()) {
        changed.add(Map.of(
            "kind", kind(entry.getValue(), category),
            "scopedName", identity(entry.getValue(), category),
            "changedFields", changedFields,
            "before", entry.getValue(),
            "after", afterItem));
      }
    }

    for (Map.Entry<String, Map<String, Object>> entry : afterByKey.entrySet()) {
      if (!beforeByKey.containsKey(entry.getKey())) {
        added.add(entry.getValue());
      }
    }
  }

  private Map<String, Map<String, Object>> index(String category, List<Map<String, Object>> items) {
    Map<String, Map<String, Object>> result = new LinkedHashMap<>();
    for (Map<String, Object> item : items) {
      Map<String, Object> normalized = normalize(category, item);
      result.put(identity(normalized, category), normalized);
    }
    return result;
  }

  private Map<String, Object> normalize(String category, Map<String, Object> item) {
    Map<String, Object> normalized = new LinkedHashMap<>();
    normalized.put("kind", kind(item, category));
    for (Map.Entry<String, Object> entry : item.entrySet()) {
      if (!IGNORED_FIELDS.contains(entry.getKey())) {
        normalized.put(entry.getKey(), entry.getValue());
      }
    }
    if (!normalized.containsKey("scopedName")) {
      Object name = normalized.get("name");
      if (name == null && "imports".equals(category)) {
        name = normalized.get("model");
      }
      if (name != null) {
        normalized.put("scopedName", name.toString());
      }
    }
    return normalized;
  }

  private List<String> changedFields(Map<String, Object> before, Map<String, Object> after) {
    Set<String> keys = new LinkedHashSet<>();
    keys.addAll(before.keySet());
    keys.addAll(after.keySet());
    keys.removeAll(IDENTITY_FIELDS);

    List<String> changed = new ArrayList<>();
    for (String key : keys) {
      if (!Objects.equals(before.get(key), after.get(key))) {
        changed.add(key);
      }
    }
    return changed;
  }

  private List<Map<String, Object>> potentiallyBreaking(
      List<Map<String, Object>> added,
      List<Map<String, Object>> removed,
      List<Map<String, Object>> changed) {
    List<Map<String, Object>> result = new ArrayList<>();

    for (Map<String, Object> item : removed) {
      if (!"IMPORT".equals(item.get("kind"))) {
        result.add(impactFinding(item, "Removing a model element can break existing data or consumers."));
      }
    }

    for (Map<String, Object> item : added) {
      if ("ATTRIBUTE".equals(item.get("kind")) && Boolean.TRUE.equals(item.get("mandatory"))) {
        result.add(impactFinding(item, "Adding a mandatory attribute requires values in existing data."));
      }
    }

    for (Map<String, Object> change : changed) {
      String kind = String.valueOf(change.get("kind"));
      @SuppressWarnings("unchecked")
      List<String> fields = (List<String>) change.getOrDefault("changedFields", List.of());

      if ("ILI_VERSION".equals(kind)) {
        result.add(impactFinding(change, "Changing the INTERLIS language version may affect compatibility."));
      } else if ("DOMAIN".equals(kind) && (fields.contains("type") || fields.contains("typeText"))) {
        result.add(impactFinding(change, "Changing a domain type may invalidate existing values or consumers."));
      } else if ("ATTRIBUTE".equals(kind)
          && (fields.contains("type") || fields.contains("typeText") || fields.contains("mandatory")
              || fields.contains("geometry") || fields.contains("container"))) {
        result.add(impactFinding(change, "Changing an attribute type, mandatory state, geometry semantics or container may be incompatible."));
      }
    }

    return result;
  }

  private Map<String, Object> impactFinding(Map<String, Object> change, String reason) {
    Map<String, Object> finding = new LinkedHashMap<>();
    finding.put("kind", change.get("kind"));
    finding.put("scopedName", change.get("scopedName"));
    finding.put("reason", reason);
    return finding;
  }

  private List<Map<String, Object>> categoryItems(ModelAnalysisTools.AnalysisData data, String category) {
    return switch (category) {
      case "models" -> data.models;
      case "imports" -> data.imports;
      case "topics" -> data.topics;
      case "classes" -> data.classes;
      case "structures" -> data.structures;
      case "domains" -> data.domains;
      case "units" -> data.units;
      case "associations" -> data.associations;
      case "attributes" -> data.attributes;
      default -> List.of();
    };
  }

  private String identity(Map<String, Object> item, String category) {
    Object scopedName = item.get("scopedName");
    if (scopedName != null && !scopedName.toString().isBlank()) {
      return scopedName.toString();
    }
    Object model = item.get("model");
    if (model != null && !model.toString().isBlank()) {
      return model.toString();
    }
    return category + ":" + item;
  }

  private String kind(Map<String, Object> item, String category) {
    Object kind = item.get("kind");
    if (kind != null && !kind.toString().isBlank()) {
      return kind.toString();
    }
    return switch (category) {
      case "models" -> "MODEL";
      case "imports" -> "IMPORT";
      case "topics" -> "TOPIC";
      case "classes" -> "CLASS";
      case "structures" -> "STRUCTURE";
      case "domains" -> "DOMAIN";
      case "units" -> "UNIT";
      case "associations" -> "ASSOCIATION";
      case "attributes" -> "ATTRIBUTE";
      default -> category.toUpperCase();
    };
  }

  private Map<String, Object> summary(int added, int removed, int changed, int potentiallyBreaking) {
    return Map.of(
        "added", added,
        "removed", removed,
        "changed", changed,
        "potentiallyBreaking", potentiallyBreaking);
  }

  private List<String> limitations() {
    return List.of(
        "Renames are not inferred; they appear as one removed and one added element.",
        "Source line changes are ignored.");
  }
}
