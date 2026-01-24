package ch.so.agi.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TopicToolsTest {

    private final TopicTools topicTools = new TopicTools();

    @Test
    void createTopic_includesOidWhenProvided() {
        Map<String, Object> response = topicTools.createTopic("Geo", "OID AS OIDTYPE", false);

        assertEquals(String.join("\n",
                "TOPIC Geo =",
                "  OID AS OIDTYPE;",
                "  !! Klassen/Assoziationen hier",
                "END Geo;"
        ), response.get("iliSnippet"));
    }

    @Test
    void createTopic_marksAbstractWhenRequested() {
        Map<String, Object> response = topicTools.createTopic("Verkehr", null, true);

        assertTrue(response.get("iliSnippet").toString().startsWith("TOPIC Verkehr (ABSTRACT) ="));
    }
}
