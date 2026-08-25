package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.MetaAttributeSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TopicToolsTest {

    private final TopicTools topicTools = new TopicTools();

    @Test
    void createTopic_includesOidWhenProvided() {
        Map<String, Object> response = topicTools.createTopic("Geo", "OID AS OIDTYPE", false, null, null);

        assertEquals(String.join("\n",
                "TOPIC Geo =",
                "  OID AS OIDTYPE;",
                "END Geo;"
        ), response.get("iliSnippet"));
    }

    @Test
    void createTopic_marksAbstractWhenRequested() {
        Map<String, Object> response = topicTools.createTopic("Verkehr", null, true, null, null);

        assertTrue(response.get("iliSnippet").toString().startsWith("TOPIC Verkehr (ABSTRACT) ="));
    }

    @Test
    void createTopic_rendersIliDocAndMetaAttributes() {
        MetaAttributeSpec metaAttribute = new MetaAttributeSpec();
        metaAttribute.setName("ch.so.topic");
        metaAttribute.setRawValue("TRUE");

        Map<String, Object> response = topicTools.createTopic("DokTopic", null, false, "Topic Doc", List.of(metaAttribute));

        assertEquals(String.join("\n",
                "/** Topic Doc */",
                "!!@ ch.so.topic=TRUE",
                "TOPIC DokTopic =",
                "END DokTopic;"
        ), response.get("iliSnippet"));
    }
}
