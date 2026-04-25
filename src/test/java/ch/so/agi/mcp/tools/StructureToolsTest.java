package ch.so.agi.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StructureToolsTest {

    private final StructureTools structureTools = new StructureTools();

    @Test
    void createStructure_includesExtendsAndAttributes() {
        Map<String, Object> response = structureTools.createStructure(
                "Adresse",
                false,
                "Basis.Adresse",
                List.of("plz : 4 .. 5;"),
                null,
                null
        );

        assertEquals(String.join("\n",
                "STRUCTURE Adresse EXTENDS Basis.Adresse =",
                "  plz : 4 .. 5;",
                "END Adresse;"
        ), response.get("iliSnippet"));
    }

    @Test
    void createStructure_usesPlaceholderWhenNoAttributes() {
        Map<String, Object> response = structureTools.createStructure("Koordinate", true, null, null, null, null);

        assertEquals(String.join("\n",
                "STRUCTURE Koordinate (ABSTRACT) =",
                "  /** Attribute hier */",
                "END Koordinate;"
        ), response.get("iliSnippet"));
    }
}
