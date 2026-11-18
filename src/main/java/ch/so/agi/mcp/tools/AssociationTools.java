package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.util.AnnotationRenderer;
import ch.so.agi.mcp.util.NameValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class AssociationTools {

  private static final Pattern CARDINALITY_PATTERN =
      Pattern.compile("\\{\\s*(\\d+)(?:\\s*\\.\\.\\s*(\\d+|\\*))?\\s*\\}");

  public static class Role {
    public @Nullable String name;
    public String classFQN;
    public @Nullable String card; // e.g. {1}, {0..1}, {1..*}
    public @Nullable Boolean external;
  }

  public Map<String, Object> createAssociation(
      @McpToolParam(description = "Expliziter Assoziationsname", required = true) String name,
      @McpToolParam(description = "Mindestens zwei Rollen mit explizitem name, classFQN und card; card verwendet INTERLIS-Notation wie {1}, {0..1} oder {1..*}.", required = true) List<Role> roles,
      @McpToolParam(description = "Beziehungsattribute als rohe ILI-Attributzeilen", required = false) @Nullable List<String> attrLines,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der ASSOCIATION", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der ASSOCIATION", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    var nv = NameValidator.ascii();
    List<Role> normalizedRoles = validateRoles(roles, nv);
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Association name is required and must not be invented.");
    }
    String associationName = name.trim();
    nv.validateIdent(associationName, "Association name");
    for (int index = 0; index < normalizedRoles.size(); index++) {
      if (normalizedRoles.get(index).name == null || normalizedRoles.get(index).name.isBlank()) {
        throw new IllegalArgumentException(
            "roles[" + index + "].name is required and must not be invented.");
      }
    }
    List<ResolvedRole> resolvedRoles = resolveRoleNames(normalizedRoles, nv);

    StringBuilder sb = new StringBuilder();
    sb.append(AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes));
    sb.append("ASSOCIATION ").append(associationName).append(" =\n");
    for (ResolvedRole role : resolvedRoles) {
      sb.append("  ").append(role.name());
      if (role.external()) {
        sb.append(" (EXTERNAL)");
      }
      sb.append(" --");
      if (role.card() != null && !role.card().isBlank()) {
        sb.append(" ").append(role.card().trim());
      }
      sb.append(" ").append(role.classFQN().trim()).append(";\n");
    }
    if (attrLines != null && !attrLines.isEmpty()) {
      sb.append("  ATTRIBUTE\n");
      for (String line : attrLines) {
        sb.append(AnnotationRenderer.indentBlock(line, "    ")).append("\n");
      }
    }
    sb.append("END ").append(associationName).append(";");

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("iliSnippet", sb.toString());
    result.put("generatedNames", Map.of());
    result.put("openQuestions", List.of());
    result.put("nameCollisionsResolved", List.of());
    return result;
  }

  private List<Role> validateRoles(@Nullable List<Role> roles, NameValidator nameValidator) {
    if (roles == null || roles.size() < 2) {
      throw new IllegalArgumentException("At least 2 association roles are required.");
    }

    List<Role> normalizedRoles = new ArrayList<>();
    for (Role role : roles) {
      if (role == null) {
        throw new IllegalArgumentException("Association role must not be null.");
      }
      if (role.name != null && !role.name.isBlank()) {
        nameValidator.validateIdent(role.name.trim(), "Association role name");
      }
      nameValidator.validateFqn(role.classFQN, "Association role class FQN");
      if (role.card == null || role.card.isBlank()) {
        throw new IllegalArgumentException(
            "Association role cardinality is required and must not be invented.");
      }
      validateCardinality(role.card);

      Role normalized = new Role();
      normalized.name = role.name;
      normalized.classFQN = role.classFQN.trim();
      normalized.card = role.card;
      normalized.external = role.external;
      normalizedRoles.add(normalized);
    }
    return normalizedRoles;
  }

  private void validateCardinality(@Nullable String card) {
    if (card == null || card.isBlank()) {
      return;
    }

    String trimmed = card.trim();
    Matcher matcher = CARDINALITY_PATTERN.matcher(trimmed);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Association role cardinality must use INTERLIS notation like {1}, {0..1} or {1..*}: " + trimmed);
    }

    String upper = matcher.group(2);
    if (upper != null && !"*".equals(upper)) {
      int lowerValue = Integer.parseInt(matcher.group(1));
      int upperValue = Integer.parseInt(upper);
      if (upperValue < lowerValue) {
        throw new IllegalArgumentException("Association role cardinality upper bound must be >= lower bound: " + trimmed);
      }
    }
  }

  private List<ResolvedRole> resolveRoleNames(
      List<Role> roles, NameValidator nameValidator) {
    Set<String> usedNames = new LinkedHashSet<>();
    List<ResolvedRole> resolvedRoles = new ArrayList<>();
    for (int i = 0; i < roles.size(); i++) {
      Role role = roles.get(i);
      String roleName = role.name.trim();
      nameValidator.validateIdent(roleName, "Association role name");
      if (!usedNames.add(roleName)) {
        throw new IllegalArgumentException("Duplicate association role name: " + roleName);
      }
      resolvedRoles.add(new ResolvedRole(
          roleName,
          role.classFQN,
          role.card,
          role.external != null && role.external));
    }
    return List.copyOf(resolvedRoles);
  }

  private record ResolvedRole(String name, String classFQN, @Nullable String card, boolean external) {
  }

}
