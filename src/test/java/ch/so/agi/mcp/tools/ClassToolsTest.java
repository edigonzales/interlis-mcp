package ch.so.agi.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClassToolsTest {

    private final ClassTools classTools = new ClassTools();

    @Test
    void createClass_buildsAbstractExtendingSnippetWithAttributes() {
        Map<String, Object> response = classTools.createClass(
                "Baum",
                true,
                "Basis.Top.Tree",
                "OID AS UUIDOID",
                List.of("art : TEXT;", "hoehe : 0 .. 20;")
        );

        String expected = String.join("\n",
                "CLASS Baum (ABSTRACT) EXTENDS Basis.Top.Tree =",
                "  OID AS UUIDOID;",
                "  art : TEXT;",
                "  hoehe : 0 .. 20;",
                "END Baum;"
        );
        assertEquals(expected, response.get("iliSnippet"));
    }

    @Test
    void createClass_usesPlaceholderWhenNoAttributes() {
        Map<String, Object> response = classTools.createClass(
                "Strauch",
                false,
                null,
                null,
                List.of()
        );

        assertEquals(String.join("\n",
                "CLASS Strauch =",
                "  !! Attribute hier",
                "END Strauch;"
        ), response.get("iliSnippet"));
    }

    @Test
    void createClass_rejectsInvalidExtendsFqn() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                classTools.createClass("Test", null, "invalid fq", null, null)
        );

        assertTrue(ex.getMessage().contains("EXTENDS FQN"));
    }
}
