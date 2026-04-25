package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.MetaAttributeSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureAttributeToolsTest {

  private final StructureAttributeTools structureAttributeTools = new StructureAttributeTools();

  @Test
  void createStructureAttributeLine_rendersIliDocAndMetaAttributes() {
    MetaAttributeSpec metaAttribute = new MetaAttributeSpec();
    metaAttribute.setName("ch.so.attr");
    metaAttribute.setValue("doc");

    Map<String, Object> response = structureAttributeTools.createStructureAttributeLine(
        "kontakt",
        "Demo.Core.Contact",
        true,
        StructureAttributeTools.Collection.BAG_OF,
        "Attributkommentar",
        List.of(metaAttribute));

    assertEquals(String.join("\n",
        "/** Attributkommentar */",
        "!!@ ch.so.attr=\"doc\"",
        "kontakt : MANDATORY BAG OF Demo.Core.Contact;"), response.get("iliSnippet"));
  }
}
