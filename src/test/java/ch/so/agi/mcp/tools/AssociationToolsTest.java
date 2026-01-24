package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.tools.AssociationTools.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssociationToolsTest {

    private final AssociationTools associationTools = new AssociationTools();

    @Test
    void createAssociation_formatsEachRoleOnOwnLine() {
        Role left = new Role();
        left.name = "from";
        left.card = "{1}";
        left.classFQN = "Mod.Topic.Source";

        Role right = new Role();
        right.name = "to";
        right.card = "{0..*}";
        right.classFQN = "Mod.Topic.Target";

        Map<String, Object> response = associationTools.createAssociation("Link", List.of(left, right));

        assertEquals(String.join("\n",
                "ASSOCIATION Link =",
                "  from -- {1} Mod.Topic.Source;",
                "  to -- {0..*} Mod.Topic.Target;",
                "END Link;"
        ), response.get("iliSnippet"));
    }

    @Test
    void createAssociation_validatesRoleNames() {
        Role invalid = new Role();
        invalid.name = "1bad";
        invalid.card = "{1}";
        invalid.classFQN = "Mod.Topic.Class";

        Role other = new Role();
        other.name = "good";
        other.card = "{0..1}";
        other.classFQN = "Mod.Topic.Other";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                associationTools.createAssociation("Assoc", List.of(invalid, other))
        );

        assertTrue(ex.getMessage().contains("Association role name"));
    }
}
