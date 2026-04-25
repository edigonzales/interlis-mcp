package ch.so.agi.mcp.knowledge;

import ch.so.agi.mcp.analysis.ModelPurpose;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeRuleLoader {

  private static final String RESOURCE = "/knowledge/modeling-rules.yml";
  private final List<ModelingRule> rules;

  public KnowledgeRuleLoader() {
    this.rules = List.copyOf(loadRules());
  }

  public List<ModelingRule> rules() {
    return rules;
  }

  public String rulesAsMarkdown() {
    StringBuilder sb = new StringBuilder("# Curated INTERLIS Modeling Rules\n\n");
    for (ModelingRule rule : rules) {
      sb.append("## ").append(rule.id()).append(" - ").append(rule.title()).append("\n\n")
          .append("- Severity: ").append(rule.severity()).append("\n")
          .append("- Applies to: ").append(rule.appliesTo()).append("\n")
          .append("- Check kind: ").append(rule.checkKind()).append("\n")
          .append("- Source: ").append(rule.sourceUrl()).append(" (").append(rule.sourceSection()).append(")\n")
          .append("- Rationale: ").append(rule.rationale()).append("\n")
          .append("- Recommendation: ").append(rule.recommendation()).append("\n\n");
    }
    return sb.toString();
  }

  private List<ModelingRule> loadRules() {
    InputStream input = KnowledgeRuleLoader.class.getResourceAsStream(RESOURCE);
    if (input == null) {
      throw new IllegalStateException("Missing resource " + RESOURCE);
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
      throw new UncheckedIOException("Unable to read " + RESOURCE, e);
    }

    List<ModelingRule> parsed = new ArrayList<>();
    for (Map<String, String> raw : rawRules) {
      parsed.add(new ModelingRule(
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

