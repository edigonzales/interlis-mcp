package ch.so.agi.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConstraintToolsTest {

    private final ConstraintTools constraintTools = new ConstraintTools();

    @Test
    void uniqueConstraint_trimsAttributesAndJoinsWithComma() {
        Map<String, Object> response = constraintTools.unique(List.of("  name  ", "lage"), null, null);

        assertEquals(String.join("\n",
                "CONSTRAINTS",
                "  UNIQUE name, lage;"
        ), response.get("iliSnippet"));
    }

}
