package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.MetaAttributeSpec;
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
                List.of("art : TEXT;", "hoehe : 0 .. 20;"),
                null,
                null
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
                List.of(),
                null,
                null
        );

        assertEquals(String.join("\n",
                "CLASS Strauch =",
                "  /** Attribute hier */",
                "END Strauch;"
        ), response.get("iliSnippet"));
    }

    @Test
    void createClass_rejectsInvalidExtendsFqn() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                classTools.createClass("Test", null, "invalid fq", null, null, null, null)
        );

        assertTrue(ex.getMessage().contains("EXTENDS FQN"));
    }

    @Test
    void createClass_rendersIliDocAndMetaAttributesAndIndentsMultilineAttributes() {
        MetaAttributeSpec metaAttribute = meta("ch.so.test.flag", "alpha", null);

        Map<String, Object> response = classTools.createClass(
                "DokKlasse",
                false,
                null,
                null,
                List.of("/** Feldbeschreibung */\n!!@ ch.so.attr=\"beta\"\nwert : TEXT;"),
                "Klassenbeschreibung",
                List.of(metaAttribute)
        );

        assertEquals(String.join("\n",
                "/** Klassenbeschreibung */",
                "!!@ ch.so.test.flag=\"alpha\"",
                "CLASS DokKlasse =",
                "  /** Feldbeschreibung */",
                "  !!@ ch.so.attr=\"beta\"",
                "  wert : TEXT;",
                "END DokKlasse;"
        ), response.get("iliSnippet"));
    }

    private MetaAttributeSpec meta(String name, String value, String rawValue) {
        MetaAttributeSpec metaAttribute = new MetaAttributeSpec();
        metaAttribute.setName(name);
        metaAttribute.setValue(value);
        metaAttribute.setRawValue(rawValue);
        return metaAttribute;
    }
}
