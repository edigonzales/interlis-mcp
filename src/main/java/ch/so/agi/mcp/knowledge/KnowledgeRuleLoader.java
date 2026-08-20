package ch.so.agi.mcp.knowledge;

import ch.so.agi.mcp.analysis.ModelPurpose;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeRuleLoader {

  private static final String CORE_RESOURCE = "/knowledge/modeling-rules.core.yml";
  private static final String SO_RESOURCE = "/knowledge/modeling-rules.so.yml";
  private final Map<ModelingRuleProfile, List<ModelingRule>> rulesByProfile;

  public KnowledgeRuleLoader() {
    Map<ModelingRuleProfile, List<ModelingRule>> loaded = new EnumMap<>(ModelingRuleProfile.class);
    loaded.put(ModelingRuleProfile.CORE, List.copyOf(loadRules(CORE_RESOURCE, ModelingRuleProfile.CORE)));
    loaded.put(ModelingRuleProfile.SO, List.copyOf(loadRules(SO_RESOURCE, ModelingRuleProfile.SO)));
    this.rulesByProfile = Map.copyOf(loaded);
  }

  public List<ModelingRule> rules() {
    return rules(ModelingRuleProfile.CORE);
  }

  public List<ModelingRule> rules(ModelingRuleProfile profile) {
    ModelingRuleProfile normalized = ModelingRuleProfile.normalize(profile);
    if (normalized == ModelingRuleProfile.CORE) {
      return rulesByProfile.getOrDefault(ModelingRuleProfile.CORE, List.of());
    }

    List<ModelingRule> merged = new ArrayList<>();
    merged.addAll(rulesByProfile.getOrDefault(ModelingRuleProfile.CORE, List.of()));
    merged.addAll(rulesByProfile.getOrDefault(normalized, List.of()));
    return List.copyOf(merged);
  }

  public String rulesAsMarkdown() {
    return rulesAsMarkdown(ModelingRuleProfile.CORE);
  }

  public String rulesAsMarkdown(ModelingRuleProfile profile) {
    ModelingRuleProfile normalizedProfile = ModelingRuleProfile.normalize(profile);
    List<ModelingRule> rules = rules(normalizedProfile);
    StringBuilder sb = new StringBuilder("# Kuratierte INTERLIS-Modellierungsregeln\n\n");
    sb.append("Aktives Profil: `").append(normalizedProfile).append("`\n\n");
    for (ModelingRule rule : rules) {
      sb.append("## ").append(rule.id()).append(" - ").append(rule.title()).append("\n\n")
          .append("- Profil: ").append(rule.profile()).append("\n")
          .append("- Schweregrad: ").append(rule.severity()).append("\n")
          .append("- Gilt fuer: ").append(rule.appliesTo()).append("\n")
          .append("- Pruefart: ").append(rule.checkKind()).append("\n")
          .append("- Quelle: ").append(rule.sourceUrl()).append(" (").append(rule.sourceSection()).append(")\n")
          .append("- Begruendung: ").append(rule.rationale()).append("\n")
          .append("- Empfehlung: ").append(rule.recommendation()).append("\n\n");
    }
    return sb.toString();
  }

  private List<ModelingRule> loadRules(String resource, ModelingRuleProfile defaultProfile) {
    InputStream input = KnowledgeRuleLoader.class.getResourceAsStream(resource);
    if (input == null) {
      throw new IllegalStateException("Missing resource " + resource);
    }

    List<Map<String, String>> rawRules = new ArrayList<>();
    Map<String, String> current = null;
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      for (String line; (line = reader.readLine()) != null; ) {
        String trimmed = line.trim();
        if (trimmed.isBlank() || trimmed.startsWith("#")) {
          continue;
        }
        if (trimmed.startsWith("- ")) {
          if (current != null) {
            rawRules.add(current);
          }
          current = new LinkedHashMap<>();
          putLine(current, trimmed.substring(2));
        } else if (current != null) {
          putLine(current, trimmed);
        }
      }
      if (current != null) {
        rawRules.add(current);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to read " + resource, e);
    }

    List<ModelingRule> parsed = new ArrayList<>();
    for (Map<String, String> raw : rawRules) {
      String rawProfile = raw.get("profile");
      ModelingRuleProfile profile = rawProfile == null || rawProfile.isBlank()
          ? defaultProfile
          : ModelingRuleProfile.valueOf(rawProfile);
      parsed.add(new ModelingRule(
          profile,
          require(raw, "id"),
          require(raw, "title"),
          ModelingRule.Severity.valueOf(require(raw, "severity")),
          ModelPurpose.valueOf(require(raw, "appliesTo")),
          ModelingRule.CheckKind.valueOf(require(raw, "checkKind")),
          require(raw, "sourceUrl"),
          require(raw, "sourceSection"),
          require(raw, "rationale"),
          require(raw, "recommendation")
      ));
    }
    return parsed;
  }

  private void putLine(Map<String, String> current, String line) {
    int split = line.indexOf(':');
    if (split < 0) {
      return;
    }
    String key = line.substring(0, split).trim();
    String value = line.substring(split + 1).trim();
    if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.substring(1, value.length() - 1);
    }
    current.put(key, value);
  }

  private String require(Map<String, String> raw, String key) {
    String value = raw.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing rule property: " + key);
    }
    return value;
  }
}
