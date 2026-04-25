package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.util.AnnotationRenderer;
import ch.so.agi.mcp.util.NameValidator;
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
    public String name;
    public String classFQN;
    public String card; // e.g. {1}, {0..1}, {1..*}
    public Boolean external;
  }

  @McpTool(name = "createAssociationSnippet",
        description = "Erzeugt eine ASSOCIATION. Params: name (required), roles (mindestens 2 Rollen mit name,classFQN, optional card, optional external), optional attrLines, iliDoc, metaAttributes. card ist die Kardinalität in INTERLIS-Notation, z.B. {1}, {0..1}, {1..*}.")
  public Map<String,Object> createAssociation(
      @McpToolParam(description = "Assoziationsname", required = true) String name,
      @McpToolParam(description = "Rollen (mindestens 2) mit name,classFQN, optional card, optional external; card ist die Kardinalität in INTERLIS-Notation, z.B. {1}, {0..1}, {1..*}.", required = true) List<Role> roles,
      @McpToolParam(description = "Beziehungsattribute als rohe ILI-Attributzeilen", required = false) @Nullable List<String> attrLines,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der ASSOCIATION", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der ASSOCIATION", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    var nv = NameValidator.ascii();
    nv.validateIdent(name, "Association name");
    validateRoles(roles, nv);

    StringBuilder sb = new StringBuilder();
    sb.append(AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes));
    sb.append("ASSOCIATION ").append(name).append(" =\n");
    for (Role r : roles) {
      sb.append("  ").append(r.name);
      if (r.external != null && r.external) {
        sb.append(" (EXTERNAL)");
      }
      sb.append(" --");
      if (r.card != null && !r.card.isBlank()) {
        sb.append(" ").append(r.card.trim());
      }
      sb.append(" ").append(r.classFQN.trim()).append(";\n");
    }
    if (attrLines != null && !attrLines.isEmpty()) {
      sb.append("  ATTRIBUTE\n");
      for (String line : attrLines) {
        sb.append(AnnotationRenderer.indentBlock(line, "    ")).append("\n");
      }
    }
    sb.append("END ").append(name).append(";");
    return Map.of("iliSnippet", sb.toString());
  }

  private void validateRoles(@Nullable List<Role> roles, NameValidator nameValidator) {
    if (roles == null || roles.size() < 2) {
      throw new IllegalArgumentException("At least 2 association roles are required.");
    }

    Set<String> roleNames = new LinkedHashSet<>();
    for (Role role : roles) {
      if (role == null) {
        throw new IllegalArgumentException("Association role must not be null.");
      }
      nameValidator.validateIdent(role.name, "Association role name");
      nameValidator.validateFqn(role.classFQN, "Association role class FQN");
      if (!roleNames.add(role.name)) {
        throw new IllegalArgumentException("Duplicate association role name: " + role.name);
      }
      validateCardinality(role.card);
    }
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
}
