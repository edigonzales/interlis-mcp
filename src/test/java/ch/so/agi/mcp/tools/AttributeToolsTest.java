package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.AttributeLineRequest;
import ch.so.agi.mcp.model.AttributeLineRequest.Collection;
import ch.so.agi.mcp.model.AttributeLineResponse;
import ch.so.agi.mcp.model.BaseType;
import ch.so.agi.mcp.model.TypeSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttributeToolsTest {

    private final AttributeTools attributeTools = new AttributeTools();

    @Test
    void createAttributeLineV2_usesDomainWhenPresent() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("farbe");
        TypeSpec spec = new TypeSpec();
        spec.setDomainFqn("Demo.Core.Farbe");
        request.setTypeSpec(spec);

        AttributeLineResponse response = attributeTools.createAttributeLine(request);

        assertEquals("farbe : Demo.Core.Farbe;", response.getIliSnippet());
        assertEquals(0, response.getCursorHint().get("line"));
        assertEquals(0, response.getCursorHint().get("col"));
    }

    @Test
    void createAttributeLineV2_formatsBaseTypeRange() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("hoehe");
        request.setMandatory(true);
        request.setCollection(Collection.LIST_OF);

        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.NUM_RANGE);
        baseType.setMin(0.0);
        baseType.setMax(100.0);
        baseType.setUnitFqn("INTERLIS.m");

        TypeSpec spec = new TypeSpec();
        spec.setBaseType(baseType);
        request.setTypeSpec(spec);

        AttributeLineResponse response = attributeTools.createAttributeLine(request);

        assertEquals("hoehe : MANDATORY LIST OF 0.0 .. 100.0 [INTERLIS.m];", response.getIliSnippet());
    }

    @Test
    void createAttributeLineV2_rejectsInvalidAttributeName() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("1invalid");
        TypeSpec spec = new TypeSpec();
        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.BOOLEAN);
        spec.setBaseType(baseType);
        request.setTypeSpec(spec);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> attributeTools.createAttributeLine(request));
        assertTrue(ex.getMessage().contains("Attribute name"));
    }
}
