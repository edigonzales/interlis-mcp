package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.AttributeLineRequest;
import ch.so.agi.mcp.model.AttributeLineRequest.Collection;
import ch.so.agi.mcp.model.AttributeLineResponse;
import ch.so.agi.mcp.model.BaseType;
import ch.so.agi.mcp.model.BasketTypeSpec;
import ch.so.agi.mcp.model.BlackboxTypeSpec;
import ch.so.agi.mcp.model.EnumTreeValueTypeSpec;
import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.model.MetaobjectTypeSpec;
import ch.so.agi.mcp.model.ObjectTypeSpec;
import ch.so.agi.mcp.model.ReferenceTypeSpec;
import ch.so.agi.mcp.model.TypeSpec;
import java.util.List;
import java.util.Map;
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

        assertEquals("hoehe : MANDATORY LIST OF NUMERIC 0.0 .. 100.0 [INTERLIS.m];", response.getIliSnippet());
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

    @Test
    void createAttributeLineV2_formatsBareNumeric() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("winkel");

        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.NUMERIC);

        TypeSpec spec = new TypeSpec();
        spec.setBaseType(baseType);
        request.setTypeSpec(spec);

        AttributeLineResponse response = attributeTools.createAttributeLine(request);

        assertEquals("winkel : NUMERIC;", response.getIliSnippet());
    }

    @Test
    void createAttributeLineV2_formatsNumericWithUnitAndRefSysCircular() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("azimut");

        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.NUMERIC);
        baseType.setUnitFqn("INTERLIS.deg");
        baseType.setRefSysFqn("MyModel.AngleRef");
        baseType.setCircular(true);

        TypeSpec spec = new TypeSpec();
        spec.setBaseType(baseType);
        request.setTypeSpec(spec);

        AttributeLineResponse response = attributeTools.createAttributeLine(request);

        assertEquals("azimut : NUMERIC [INTERLIS.deg] {REFSYS MyModel.AngleRef CIRCULAR};", response.getIliSnippet());
    }

    @Test
    void createAttributeLineV2_formatsNumericWithCircularOnly() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("phase");

        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.NUMERIC);
        baseType.setCircular(true);

        TypeSpec spec = new TypeSpec();
        spec.setBaseType(baseType);
        request.setTypeSpec(spec);

        AttributeLineResponse response = attributeTools.createAttributeLine(request);

        assertEquals("phase : NUMERIC {CIRCULAR};", response.getIliSnippet());
    }

    @Test
    void createAttributeLineV2_rendersIliDocAndMetaAttributes() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("phase");
        request.setIliDoc("Attributdoku");
        request.setMetaAttributes(List.of(meta("ch.so.attr", null, "TRUE")));

        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.BOOLEAN);

        TypeSpec spec = new TypeSpec();
        spec.setBaseType(baseType);
        request.setTypeSpec(spec);

        AttributeLineResponse response = attributeTools.createAttributeLine(request);

        assertEquals(String.join("\n",
                "/** Attributdoku */",
                "!!@ ch.so.attr=TRUE",
                "phase : BOOLEAN;"), response.getIliSnippet());
    }

    @Test
    void createAttributeLineV2_rejectsInvalidMetaAttributeDefinition() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("phase");
        request.setMetaAttributes(List.of(meta("ch.so.attr", "x", "Y")));

        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.BOOLEAN);

        TypeSpec spec = new TypeSpec();
        spec.setBaseType(baseType);
        request.setTypeSpec(spec);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> attributeTools.createAttributeLine(request));
        assertTrue(ex.getMessage().contains("exactly one"));
    }

    @Test
    void createAttributeLineV2_formatsReferenceType() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("ziel");

        ReferenceTypeSpec referenceType = new ReferenceTypeSpec();
        referenceType.setTargetClassFqn("Demo.Topic.Target");
        referenceType.setExternal(true);

        TypeSpec spec = new TypeSpec();
        spec.setReferenceType(referenceType);
        request.setTypeSpec(spec);

        AttributeLineResponse response = attributeTools.createAttributeLine(request);

        assertEquals("ziel : REFERENCE TO (EXTERNAL) Demo.Topic.Target;", response.getIliSnippet());
    }

    @Test
    void createAttributeLineV2_formatsBlackboxType() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("payload");

        BlackboxTypeSpec blackboxType = new BlackboxTypeSpec();
        blackboxType.setKind(BlackboxTypeSpec.Kind.XML);

        TypeSpec spec = new TypeSpec();
        spec.setBlackboxType(blackboxType);
        request.setTypeSpec(spec);

        AttributeLineResponse response = attributeTools.createAttributeLine(request);

        assertEquals("payload : BLACKBOX XML;", response.getIliSnippet());
    }

    @Test
    void createAttributeLineV2_formatsEnumTreeValueType() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("statusPfad");

        EnumTreeValueTypeSpec enumTreeValueType = new EnumTreeValueTypeSpec();
        enumTreeValueType.setEnumTreeDomainFqn("Demo.Topic.StatusTree");

        TypeSpec spec = new TypeSpec();
        spec.setEnumTreeValueType(enumTreeValueType);
        request.setTypeSpec(spec);

        AttributeLineResponse response = attributeTools.createAttributeLine(request);

        assertEquals("statusPfad : Demo.Topic.StatusTree;", response.getIliSnippet());
    }

    @Test
    void createAttributeLineV2_formatsBasketType() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("korb");

        BasketTypeSpec basketType = new BasketTypeSpec();
        basketType.setKind(BasketTypeSpec.Kind.DATA);
        basketType.setTopicFqn("Demo.Topic");

        TypeSpec spec = new TypeSpec();
        spec.setBasketType(basketType);
        request.setTypeSpec(spec);

        AttributeLineResponse response = attributeTools.createAttributeLine(request);

        assertEquals("korb : BASKET (DATA) OF Demo.Topic;", response.getIliSnippet());
    }

    @Test
    void createAttributeLineV2_formatsObjectType() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("objekte");

        ObjectTypeSpec objectType = new ObjectTypeSpec();
        objectType.setTargetClassFqn("Demo.Topic.Target");
        objectType.setObjects(true);

        TypeSpec spec = new TypeSpec();
        spec.setObjectType(objectType);
        request.setTypeSpec(spec);

        AttributeLineResponse response = attributeTools.createAttributeLine(request);

        assertEquals("objekte : OBJECTS OF Demo.Topic.Target;", response.getIliSnippet());
    }

    @Test
    void createAttributeLineV2_formatsMetaobjectType() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("meta");

        MetaobjectTypeSpec metaobjectType = new MetaobjectTypeSpec();
        metaobjectType.setTableFqn("Demo.Topic.MetaCatalog");

        TypeSpec spec = new TypeSpec();
        spec.setMetaobjectType(metaobjectType);
        request.setTypeSpec(spec);

        AttributeLineResponse response = attributeTools.createAttributeLine(request);

        assertEquals("meta : METAOBJECT OF Demo.Topic.MetaCatalog;", response.getIliSnippet());
    }

    @Test
    void createAttributeLineV2_rejectsMultipleTypeFamilies() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("phase");

        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.BOOLEAN);

        ReferenceTypeSpec referenceType = new ReferenceTypeSpec();
        referenceType.setTargetClassFqn("Demo.Topic.Target");

        TypeSpec spec = new TypeSpec();
        spec.setBaseType(baseType);
        spec.setReferenceType(referenceType);
        request.setTypeSpec(spec);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> attributeTools.createAttributeLine(request));
        assertTrue(ex.getMessage().contains("exactly one"));
    }

    @Test
    void createAttributeLineV2_generatedReferenceCompiles() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("ziel");

        ReferenceTypeSpec referenceType = new ReferenceTypeSpec();
        referenceType.setTargetClassFqn("Demo.Topic.Target");

        TypeSpec spec = new TypeSpec();
        spec.setReferenceType(referenceType);
        request.setTypeSpec(spec);

        String line = attributeTools.createAttributeLine(request).getIliSnippet();
        assertCompiles("""
            INTERLIS 2.4;

            MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-01" =
              TOPIC Topic =
                CLASS Target =
                END Target;
                CLASS Holder =
            """
            + "      " + line + "\n"
            + """
                END Holder;
              END Topic;
            END Demo.
            """);
    }

    @Test
    void createAttributeLineV2_generatedBlackboxCompiles() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("payload");

        BlackboxTypeSpec blackboxType = new BlackboxTypeSpec();
        blackboxType.setKind(BlackboxTypeSpec.Kind.BINARY);

        TypeSpec spec = new TypeSpec();
        spec.setBlackboxType(blackboxType);
        request.setTypeSpec(spec);

        String line = attributeTools.createAttributeLine(request).getIliSnippet();
        assertCompiles("""
            INTERLIS 2.4;

            MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-01" =
              TOPIC Topic =
                CLASS Holder =
            """
            + "      " + line + "\n"
            + """
                END Holder;
              END Topic;
            END Demo.
            """);
    }

    @Test
    void createAttributeLineV2_generatedEnumTreeValueCompiles() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("pfad");

        EnumTreeValueTypeSpec enumTreeValueType = new EnumTreeValueTypeSpec();
        enumTreeValueType.setEnumTreeDomainFqn("Demo.Topic.EnumTree");

        TypeSpec spec = new TypeSpec();
        spec.setEnumTreeValueType(enumTreeValueType);
        request.setTypeSpec(spec);

        String line = attributeTools.createAttributeLine(request).getIliSnippet();
        assertCompiles("""
            INTERLIS 2.4;

            MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-01" =
              TOPIC Topic =
                DOMAIN EnumTree = (
                  A (
                    B
                  ),
                  C
                );
                CLASS Holder =
            """
            + "      " + line + "\n"
            + """
                END Holder;
              END Topic;
            END Demo.
            """);
    }

    private void assertCompiles(String modelText) {
        ValidationTools validationTools = new ValidationTools();
        Map<String, Object> result = validationTools.validateIliModel(modelText, null);
        assertEquals(true, result.get("valid"), () -> "Expected valid model but got " + result);
    }

    private MetaAttributeSpec meta(String name, String value, String rawValue) {
        MetaAttributeSpec metaAttribute = new MetaAttributeSpec();
        metaAttribute.setName(name);
        metaAttribute.setValue(value);
        metaAttribute.setRawValue(rawValue);
        return metaAttribute;
    }
}
