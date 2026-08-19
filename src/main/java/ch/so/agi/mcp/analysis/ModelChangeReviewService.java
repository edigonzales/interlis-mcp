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
import org.springframework.stereotype.Service;

@Service
public class ModelChangeReviewService {

  private static final List<String> CATEGORIES = List.of(
      "models", "imports", "topics", "classes", "structures", "domains", "units", "associations", "attributes", "constraints", "metaAttributes");
  private static final Set<String> IDENTITY_FIELDS = Set.of("kind", "name", "scopedName");
  private static final Set<String> IGNORED_FIELDS = Set.of("line", "metaAttributes");

  private final ModelAnalysisTools analysisTools;
  private final ModelingRuleTools ruleTools;

  public ModelChangeReviewService(ModelAnalysisTools analysisTools, ModelingRuleTools ruleTools) {
    this.analysisTools = analysisTools;
    this.ruleTools = ruleTools;
  }

  public Map<String, Object> reviewCompiledChange(
      IliCompilerService.CompilationResult beforeCompilation,
      IliCompilerService.CompilationResult afterCompilation,
      String beforeModelText,
      String afterModelText,
      ModelPurpose purpose,
      ModelingRuleProfile profile) {

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
        Map.entry("limitations", limitations()));
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
        Map.entry("limitations", limitations()));
  }

  private Map<String, Object> unavailableAfterReview(ModelPurpose purpose, ModelingRuleProfile profile) {
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
      String kind = String.valueOf(item.get("kind"));
      if (!"IMPORT".equals(kind) && !"META_ATTRIBUTE".equals(kind)) {
        result.add(impactFinding(item, "Removing a model element can break existing data or consumers."));
      }
    }

    for (Map<String, Object> item : added) {
      String kind = String.valueOf(item.get("kind"));
      if ("ATTRIBUTE".equals(kind) && Boolean.TRUE.equals(item.get("mandatory"))) {
        result.add(impactFinding(item, "Adding a mandatory attribute requires values in existing data."));
      } else if (isConstraintKind(kind)) {
        result.add(impactFinding(item, "Adding a constraint can invalidate data that was valid before."));
      }
    }

    for (Map<String, Object> change : changed) {
      String kind = String.valueOf(change.get("kind"));
      @SuppressWarnings("unchecked")
      List<String> fields = (List<String>) change.getOrDefault("changedFields", List.of());

      if ("ILI_VERSION".equals(kind)) {
        result.add(impactFinding(change, "Changing the INTERLIS language version may affect compatibility."));
      } else if (isConstraintKind(kind)) {
        result.add(impactFinding(change, "Changing a constraint changes the set of valid data and requires domain review."));
      } else if ("UNIT".equals(kind) && fields.contains("definitionText")) {
        result.add(impactFinding(change, "Changing a unit definition may change the meaning of existing numeric values."));
      } else if ("TOPIC".equals(kind) && hasAny(fields, "extends", "dependsOn", "abstract", "final")) {
        result.add(impactFinding(change, "Changing topic inheritance, dependencies or modifiers may affect model compatibility."));
      } else if (("CLASS".equals(kind) || "STRUCTURE".equals(kind))
          && hasAny(fields, "extends", "abstract", "final")) {
        result.add(impactFinding(change, "Changing inheritance or class/structure modifiers may affect existing data or consumers."));
      } else if ("DOMAIN".equals(kind)
          && hasAny(fields, "type", "typeText", "extends", "abstract", "final")) {
        result.add(impactFinding(change, "Changing a domain type, inheritance or modifiers may invalidate existing values or consumers."));
      } else if ("ASSOCIATION".equals(kind)
          && hasAny(fields, "roles", "extends", "abstract", "final")) {
        result.add(impactFinding(change, "Changing association roles, inheritance or modifiers may change relationship semantics."));
      } else if ("ATTRIBUTE".equals(kind)
          && hasAny(fields, "type", "typeText", "mandatory", "geometry", "container")) {
        result.add(impactFinding(change, "Changing an attribute type, mandatory state, geometry semantics or container may be incompatible."));
      }
    }
    return result;
  }

  private boolean hasAny(List<String> fields, String... candidates) {
    for (String candidate : candidates) {
      if (fields.contains(candidate)) {
        return true;
      }
    }
    return false;
  }

  private boolean isConstraintKind(String kind) {
    return kind.endsWith("_CONSTRAINT");
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
      case "constraints" -> data.constraints;
      case "metaAttributes" -> data.metaAttributes;
      default -> List.of();
    };
  }

  private String identity(Map<String, Object> item, String category) {
    if ("metaAttributes".equals(category)) {
      Object owner = item.get("owner");
      Object name = item.get("name");
      if (owner != null && name != null) {
        return owner + "!!@" + name;
      }
    }
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
      case "constraints" -> "CONSTRAINT";
      case "metaAttributes" -> "META_ATTRIBUTE";
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
        "Unnamed constraints are matched by ili2c-generated names; reordering them can appear as multiple changes.",
        "Source line changes are ignored.");
  }
}
