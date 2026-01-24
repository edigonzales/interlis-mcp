package ch.so.agi.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConstraintToolsTest {

    private final ConstraintTools constraintTools = new ConstraintTools();

    @Test
    void uniqueConstraint_trimsAttributesAndJoinsWithComma() {
        Map<String, Object> response = constraintTools.unique(List.of("  name  ", "lage"));

        assertEquals(String.join("\n",
                "CONSTRAINTS",
                "  UNIQUE (name, lage);"
        ), response.get("iliSnippet"));
    }

    @Test
    void setConstraint_indentsExpression() {
        Map<String, Object> response = constraintTools.setConstraint("AREA->STANDORT->count() > 0");

        assertEquals(String.join("\n",
                "CONSTRAINTS",
                "  SET CONSTRAINT",
                "    AREA->STANDORT->count() > 0;"
        ), response.get("iliSnippet"));
    }

    @Test
    void existenceConstraint_joinsFqns() {
        Map<String, Object> response = constraintTools.existence("obj.ref", List.of("Mod1.ClassA", "Mod2.ClassB"));

        assertEquals(String.join("\n",
                "CONSTRAINTS",
                "  EXISTENCE CONSTRAINT obj.ref REQUIRED IN Mod1.ClassA, Mod2.ClassB;"
        ), response.get("iliSnippet"));
    }
}
