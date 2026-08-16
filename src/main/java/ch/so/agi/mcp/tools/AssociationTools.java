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
import org.springframework.ai.mcp.annotation.McpTool;
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

  @McpTool(name = "createAssociationSnippet",
      description = "Erzeugt eine ASSOCIATION. Params: name (optional), roles (mindestens 2 Rollen mit classFQN, optional name, optional card, optional external), optional attrLines, iliDoc, metaAttributes. Fehlen name oder Rollenname, werden deterministische Default-Namen generiert. card ist die Kardinalitaet in INTERLIS-Notation, z.B. {1}, {0..1}, {1..*}.")
  public Map<String, Object> createAssociation(
      @McpToolParam(description = "Assoziationsname (optional; bei Leerwert wird ein Default wie KlasseA__KlasseB generiert)", required = false) @Nullable String name,
      @McpToolParam(description = "Rollen (mindestens 2) mit classFQN, optional name, optional card, optional external; card ist die Kardinalitaet in INTERLIS-Notation, z.B. {1}, {0..1}, {1..*}.", required = true) List<Role> roles,
      @McpToolParam(description = "Beziehungsattribute als rohe ILI-Attributzeilen", required = false) @Nullable List<String> attrLines,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der ASSOCIATION", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der ASSOCIATION", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    var nv = NameValidator.ascii();
    List<Role> normalizedRoles = validateRoles(roles, nv);
    List<Map<String, Object>> nameCollisionsResolved = new ArrayList<>();

    String associationName = resolveAssociationName(name, normalizedRoles, nv);
    boolean associationNameGenerated = (name == null || name.isBlank());
    ResolvedRoleSet resolvedRoleSet = resolveRoleNames(normalizedRoles, nv, nameCollisionsResolved);
    List<String> openQuestions = findOpenQuestions(resolvedRoleSet.roles(), normalizedRoles, resolvedRoleSet.generatedRoleNames());

    StringBuilder sb = new StringBuilder();
    sb.append(AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes));
    sb.append("ASSOCIATION ").append(associationName).append(" =\n");
    for (ResolvedRole role : resolvedRoleSet.roles()) {
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

    Map<String, Object> generatedNames = new LinkedHashMap<>();
    if (associationNameGenerated) {
      generatedNames.put("association", associationName);
    }
    if (!resolvedRoleSet.generatedRoleNames().isEmpty()) {
      generatedNames.put("roles", resolvedRoleSet.generatedRoleNames());
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("iliSnippet", sb.toString());
    result.put("generatedNames", generatedNames);
    result.put("openQuestions", openQuestions);
    result.put("nameCollisionsResolved", nameCollisionsResolved);
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

  private String resolveAssociationName(@Nullable String name, List<Role> roles, NameValidator nameValidator) {
    if (name != null && !name.isBlank()) {
      String trimmed = name.trim();
      nameValidator.validateIdent(trimmed, "Association name");
      return trimmed;
    }

    List<String> parts = new ArrayList<>();
    for (Role role : roles) {
      parts.add(shortClassName(role.classFQN));
    }
    String candidate = String.join("__", parts);
    String sanitized = sanitizeAssociationIdentifier(candidate, "Assoc");
    nameValidator.validateIdent(sanitized, "Association name");
    return sanitized;
  }

  private ResolvedRoleSet resolveRoleNames(
      List<Role> roles,
      NameValidator nameValidator,
      List<Map<String, Object>> nameCollisionsResolved) {
    Set<String> usedNames = new LinkedHashSet<>();
    List<ResolvedRole> resolvedRoles = new ArrayList<>();
    List<Map<String, Object>> generatedRoleNames = new ArrayList<>();

    // Reserve all explicit names first so generated names can avoid them.
    Map<Integer, String> explicitNamesByIndex = new LinkedHashMap<>();
    for (int i = 0; i < roles.size(); i++) {
      Role role = roles.get(i);
      if (role.name != null && !role.name.isBlank()) {
        String explicitName = sanitizeIdentifier(role.name.trim(), "Role");
        nameValidator.validateIdent(explicitName, "Association role name");
        if (!usedNames.add(explicitName)) {
          throw new IllegalArgumentException("Duplicate association role name: " + explicitName);
        }
        explicitNamesByIndex.put(i, explicitName);
      }
    }

    boolean selfAssociation = isSelfAssociation(roles);
    for (int i = 0; i < roles.size(); i++) {
      Role role = roles.get(i);
      String explicitName = explicitNamesByIndex.get(i);
      boolean generated = explicitName == null;
      String unique;
      if (generated) {
        String baseName = defaultRoleName(roles, i, selfAssociation);
        String sanitized = sanitizeIdentifier(baseName, "Role");
        nameValidator.validateIdent(sanitized, "Association role name");
        unique = ensureUniqueRoleName(sanitized, usedNames, nameCollisionsResolved, i, role.classFQN);
        usedNames.add(unique);
      } else {
        unique = explicitName;
      }

      if (generated) {
        generatedRoleNames.add(Map.of(
            "index", i,
            "classFQN", role.classFQN,
            "name", unique
        ));
      }

      resolvedRoles.add(new ResolvedRole(
          unique,
          role.classFQN,
          role.card,
          role.external != null && role.external));
    }
    return new ResolvedRoleSet(resolvedRoles, generatedRoleNames);
  }

  private boolean isSelfAssociation(List<Role> roles) {
    if (roles.size() != 2) {
      return false;
    }
    String left = shortClassName(roles.get(0).classFQN);
    String right = shortClassName(roles.get(1).classFQN);
    return left.equals(right);
  }

  private String defaultRoleName(List<Role> roles, int index, boolean selfAssociation) {
    if (roles.size() == 2) {
      if (selfAssociation) {
        String own = shortClassName(roles.get(index).classFQN);
        return "r_" + own + (index == 0 ? "_1" : "_2");
      }
      int otherIndex = index == 0 ? 1 : 0;
      return "r_" + shortClassName(roles.get(otherIndex).classFQN);
    }
    return "r_" + shortClassName(roles.get(index).classFQN);
  }

  private String shortClassName(String classFqn) {
    int idx = classFqn.lastIndexOf('.');
    return idx >= 0 && idx < classFqn.length() - 1 ? classFqn.substring(idx + 1) : classFqn;
  }

  private String sanitizeIdentifier(String raw, String fallbackPrefix) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < raw.length(); i++) {
      char ch = raw.charAt(i);
      if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_') {
        sb.append(ch);
      } else {
        sb.append('_');
      }
    }
    String sanitized = sb.toString().replaceAll("_+", "_");
    if (sanitized.isBlank()) {
      sanitized = fallbackPrefix;
    }
    if (!(sanitized.charAt(0) >= 'A' && sanitized.charAt(0) <= 'Z')
        && !(sanitized.charAt(0) >= 'a' && sanitized.charAt(0) <= 'z')) {
      sanitized = fallbackPrefix + "_" + sanitized;
    }
    return sanitized;
  }

  private String sanitizeAssociationIdentifier(String raw, String fallbackPrefix) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < raw.length(); i++) {
      char ch = raw.charAt(i);
      if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_') {
        sb.append(ch);
      } else {
        sb.append('_');
      }
    }
    String sanitized = sb.toString();
    if (sanitized.isBlank()) {
      sanitized = fallbackPrefix;
    }
    if (!(sanitized.charAt(0) >= 'A' && sanitized.charAt(0) <= 'Z')
        && !(sanitized.charAt(0) >= 'a' && sanitized.charAt(0) <= 'z')) {
      sanitized = fallbackPrefix + "_" + sanitized;
    }
    return sanitized;
  }

  private String ensureUniqueRoleName(
      String proposed,
      Set<String> usedNames,
      List<Map<String, Object>> nameCollisionsResolved,
      int roleIndex,
      String classFqn) {
    if (!usedNames.contains(proposed)) {
      return proposed;
    }
    int suffix = 2;
    String candidate = proposed + "_" + suffix;
    while (usedNames.contains(candidate)) {
      suffix++;
      candidate = proposed + "_" + suffix;
    }
    nameCollisionsResolved.add(Map.of(
        "index", roleIndex,
        "classFQN", classFqn,
        "from", proposed,
        "to", candidate
    ));
    return candidate;
  }

  private List<String> findOpenQuestions(
      List<ResolvedRole> roles,
      List<Role> originalRoles,
      List<Map<String, Object>> generatedRoleNames) {
    List<String> openQuestions = new ArrayList<>();
    for (int i = 0; i < roles.size(); i++) {
      ResolvedRole role = roles.get(i);
      if (role.card() == null || role.card().isBlank()) {
        openQuestions.add("Missing cardinality for role '" + role.name() + "' (index " + i + ").");
      }
    }
    if (originalRoles.size() > 2 && !generatedRoleNames.isEmpty()) {
      openQuestions.add("N-ary association uses fallback auto role names r_<OwnClass>; no unique opposite class exists.");
    }
    return openQuestions;
  }

  private record ResolvedRole(String name, String classFQN, @Nullable String card, boolean external) {
  }

  private record ResolvedRoleSet(List<ResolvedRole> roles, List<Map<String, Object>> generatedRoleNames) {
  }
}
