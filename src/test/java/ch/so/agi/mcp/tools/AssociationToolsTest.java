package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.tools.AssociationTools.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssociationToolsTest {

  private final AssociationTools associationTools = new AssociationTools();

  @Test
  void createAssociation_formatsEachRoleOnOwnLine() {
    Map<String, Object> response = associationTools.createAssociation(
        "Link",
        List.of(role("from", "Mod.Topic.Source", "{1}", null), role("to", "Mod.Topic.Target", "{0..*}", null)),
        null,
        null,
        null);

    assertEquals(String.join("\n",
            "ASSOCIATION Link =",
            "  from -- {1} Mod.Topic.Source;",
            "  to -- {0..*} Mod.Topic.Target;",
            "END Link;"),
        response.get("iliSnippet"));
  }

  @Test
  void createAssociation_allowsMissingCardinality() {
    Map<String, Object> response = associationTools.createAssociation(
        "Link",
        List.of(role("from", "Mod.Topic.Source", null, null), role("to", "Mod.Topic.Target", "{0..1}", null)),
        null,
        null,
        null);

    assertEquals(String.join("\n",
            "ASSOCIATION Link =",
            "  from -- Mod.Topic.Source;",
            "  to -- {0..1} Mod.Topic.Target;",
            "END Link;"),
        response.get("iliSnippet"));
  }

  @Test
  void createAssociation_rendersExternalRole() {
    Map<String, Object> response = associationTools.createAssociation(
        "Link",
        List.of(role("from", "Mod.Topic.Source", "{1}", true), role("to", "Mod.Topic.Target", "{0..*}", null)),
        null,
        null,
        null);

    assertEquals(String.join("\n",
            "ASSOCIATION Link =",
            "  from (EXTERNAL) -- {1} Mod.Topic.Source;",
            "  to -- {0..*} Mod.Topic.Target;",
            "END Link;"),
        response.get("iliSnippet"));
  }

  @Test
  void createAssociation_rendersRelationshipAttributes() {
    Map<String, Object> response = associationTools.createAssociation(
        "Link",
        List.of(role("from", "Mod.Topic.Source", "{1}", null), role("to", "Mod.Topic.Target", "{0..*}", null)),
        List.of("/** Beziehungscode */\ncode : TEXT*20;"),
        null,
        null);

    assertEquals(String.join("\n",
            "ASSOCIATION Link =",
            "  from -- {1} Mod.Topic.Source;",
            "  to -- {0..*} Mod.Topic.Target;",
            "  ATTRIBUTE",
            "    /** Beziehungscode */",
            "    code : TEXT*20;",
            "END Link;"),
        response.get("iliSnippet"));
  }

  @Test
  void createAssociation_rejectsLessThanTwoRoles() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        associationTools.createAssociation(
            "Link",
            List.of(role("from", "Mod.Topic.Source", "{1}", null)),
            null,
            null,
            null));

    assertTrue(ex.getMessage().contains("At least 2 association roles"));
  }

  @Test
  void createAssociation_rejectsDuplicateRoleNames() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        associationTools.createAssociation(
            "Assoc",
            List.of(role("dup", "Mod.Topic.Source", "{1}", null), role("dup", "Mod.Topic.Target", "{0..1}", null)),
            null,
            null,
            null));

    assertTrue(ex.getMessage().contains("Duplicate association role name"));
  }

  @Test
  void createAssociation_rejectsInvalidRoleNames() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        associationTools.createAssociation(
            "Assoc",
            List.of(role("1bad", "Mod.Topic.Class", "{1}", null), role("good", "Mod.Topic.Other", "{0..1}", null)),
            null,
            null,
            null));

    assertTrue(ex.getMessage().contains("Association role name"));
  }

  @Test
  void createAssociation_rejectsInvalidRoleClassFqn() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        associationTools.createAssociation(
            "Assoc",
            List.of(role("good", "Not A Fqn", "{1}", null), role("other", "Mod.Topic.Other", "{0..1}", null)),
            null,
            null,
            null));

    assertTrue(ex.getMessage().contains("Association role class FQN"));
  }

  @Test
  void createAssociation_rejectsInvalidCardinality() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        associationTools.createAssociation(
            "Assoc",
            List.of(role("good", "Mod.Topic.Class", "0..1", null), role("other", "Mod.Topic.Other", "{0..1}", null)),
            null,
            null,
            null));

    assertTrue(ex.getMessage().contains("Association role cardinality"));
  }

  @Test
  void createAssociation_rejectsDescendingCardinalityRange() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        associationTools.createAssociation(
            "Assoc",
            List.of(role("good", "Mod.Topic.Class", "{2..1}", null), role("other", "Mod.Topic.Other", "{0..1}", null)),
            null,
            null,
            null));

    assertTrue(ex.getMessage().contains("upper bound must be >= lower bound"));
  }

  @Test
  void createAssociation_withExternalRole_compiles() {
    Map<String, Object> response = associationTools.createAssociation(
        "Link",
        List.of(role("from", "Demo.Topic.Source", "{1}", true), role("to", "Demo.Topic.Target", "{0..*}", null)),
        null,
        null,
        null);

    assertCompiles("""
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-01" =
          TOPIC Topic =
            CLASS Source =
            END Source;
            CLASS Target =
            END Target;
        """
        + indent(response.get("iliSnippet").toString(), "    ") + "\n"
        + """
          END Topic;
        END Demo.
        """);
  }

  @Test
  void createAssociation_withRelationshipAttribute_compiles() {
    Map<String, Object> response = associationTools.createAssociation(
        "Link",
        List.of(role("from", "Demo.Topic.Source", "{1}", null), role("to", "Demo.Topic.Target", "{0..*}", null)),
        List.of("code : TEXT*20;"),
        null,
        null);

    assertCompiles("""
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-01" =
          TOPIC Topic =
            CLASS Source =
            END Source;
            CLASS Target =
            END Target;
        """
        + indent(response.get("iliSnippet").toString(), "    ") + "\n"
        + """
          END Topic;
        END Demo.
        """);
  }

  private void assertCompiles(String modelText) {
    ValidationTools validationTools = new ValidationTools();
    Map<String, Object> result = validationTools.validateIliModel(modelText, null);
    assertEquals(true, result.get("valid"), () -> "Expected valid model but got " + result);
  }

  private Role role(String name, String classFqn, String card, Boolean external) {
    Role role = new Role();
    role.name = name;
    role.classFQN = classFqn;
    role.card = card;
    role.external = external;
    return role;
  }

  private String indent(String text, String prefix) {
    return text.replace("\n", "\n" + prefix);
  }
}
