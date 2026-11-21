package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.AttributeLineRequest;
import ch.so.agi.mcp.model.AttributeLineRequest.Collection;
import ch.so.agi.mcp.model.BaseType;
import ch.so.agi.mcp.model.BasketTypeSpec;
import ch.so.agi.mcp.model.BlackboxTypeSpec;
import ch.so.agi.mcp.model.EnumTreeValueTypeSpec;
import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.model.MetaobjectTypeSpec;
import ch.so.agi.mcp.model.ObjectTypeSpec;
import ch.so.agi.mcp.model.ReferenceTypeSpec;
import ch.so.agi.mcp.model.TypeSpec;
import ch.so.agi.mcp.service.IliCompilerService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttributeToolsTest {

    private final AttributeTools attributeTools = new AttributeTools();

    @Test
    void renderAttribute_usesDomainWhenPresent() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("farbe");
        TypeSpec spec = new TypeSpec();
        spec.setDomainFqn("Demo.Core.Farbe");
        request.setTypeSpec(spec);

        AttributeTools.RenderedAttribute rendered = attributeTools.renderAttribute(request, "2.4");

        assertEquals("farbe : Demo.Core.Farbe;", rendered.text());
    }

    @Test
    void renderAttribute_usesStructureWhenPresent() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("addresses");
        request.setCollection(Collection.LIST_OF);
        TypeSpec spec = new TypeSpec();
        spec.setStructureFqn("Demo.Core.Address");
        request.setTypeSpec(spec);

        AttributeTools.RenderedAttribute rendered = attributeTools.renderAttribute(request, "2.4");

        assertEquals("addresses : LIST OF Demo.Core.Address;", rendered.text());
    }

    @Test
    void renderAttribute_formatsBaseTypeRange() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("hoehe");
        request.setMandatory(true);
        request.setCollection(Collection.LIST_OF);

        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.NUM_RANGE);
        baseType.setMin(new BigDecimal("0.0"));
        baseType.setMax(new BigDecimal("100.0"));
        baseType.setUnitFqn("INTERLIS.m");

        TypeSpec spec = new TypeSpec();
        spec.setBaseType(baseType);
        request.setTypeSpec(spec);

        AttributeTools.RenderedAttribute rendered = attributeTools.renderAttribute(request, "2.4");

        assertEquals("hoehe : MANDATORY LIST OF 0.0 .. 100.0 [INTERLIS.m];", rendered.text());
    }

    @Test
    void renderAttribute_formatsIntegerRangeWithoutNumericKeyword() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("BFSNr");
        request.setMandatory(true);

        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.NUM_RANGE);
        baseType.setMin(new BigDecimal("2000"));
        baseType.setMax(new BigDecimal("3000"));

        TypeSpec spec = new TypeSpec();
        spec.setBaseType(baseType);
        request.setTypeSpec(spec);

        AttributeTools.RenderedAttribute rendered = attributeTools.renderAttribute(request, "2.4");

        assertEquals("BFSNr : MANDATORY 2000 .. 3000;", rendered.text());
    }

    @Test
    void renderAttribute_preservesHighPrecisionRange() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("wert");

        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.NUM_RANGE);
        baseType.setMin(new BigDecimal("0.000000000000000001"));
        baseType.setMax(new BigDecimal("0.000000000000000009"));

        TypeSpec spec = new TypeSpec();
        spec.setBaseType(baseType);
        request.setTypeSpec(spec);

        AttributeTools.RenderedAttribute rendered = attributeTools.renderAttribute(request, "2.4");

        assertEquals("wert : 0.000000000000000001 .. 0.000000000000000009;", rendered.text());
    }

    @Test
    void renderAttribute_rejectsInvalidAttributeName() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("1invalid");
        TypeSpec spec = new TypeSpec();
        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.BOOLEAN);
        spec.setBaseType(baseType);
        request.setTypeSpec(spec);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> attributeTools.renderAttribute(request, "2.4"));
        assertTrue(ex.getMessage().contains("Attribute name"));
    }

    @Test
    void renderAttribute_formatsBareNumeric() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("winkel");

        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.NUMERIC);

        TypeSpec spec = new TypeSpec();
        spec.setBaseType(baseType);
        request.setTypeSpec(spec);

        AttributeTools.RenderedAttribute rendered = attributeTools.renderAttribute(request, "2.4");

        assertEquals("winkel : NUMERIC;", rendered.text());
    }

    @Test
    void renderAttribute_formatsNumericWithUnitAndRefSysCircular() {
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

        AttributeTools.RenderedAttribute rendered = attributeTools.renderAttribute(request, "2.4");

        assertEquals("azimut : NUMERIC [INTERLIS.deg] {REFSYS MyModel.AngleRef CIRCULAR};", rendered.text());
    }

    @Test
    void renderAttribute_formatsNumericWithCircularOnly() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("phase");

        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.NUMERIC);
        baseType.setCircular(true);

        TypeSpec spec = new TypeSpec();
        spec.setBaseType(baseType);
        request.setTypeSpec(spec);

        AttributeTools.RenderedAttribute rendered = attributeTools.renderAttribute(request, "2.4");

        assertEquals("phase : NUMERIC {CIRCULAR};", rendered.text());
    }

    @Test
    void renderAttribute_rendersIliDocAndMetaAttributes() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("phase");
        request.setIliDoc("Attributdoku");
        request.setMetaAttributes(List.of(meta("ch.so.attr", null, "TRUE")));

        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.BOOLEAN);

        TypeSpec spec = new TypeSpec();
        spec.setBaseType(baseType);
        request.setTypeSpec(spec);

        AttributeTools.RenderedAttribute rendered = attributeTools.renderAttribute(request, "2.4");

        assertEquals(String.join("\n",
                "/** Attributdoku */",
                "!!@ ch.so.attr=TRUE",
                "phase : BOOLEAN;"), rendered.text());
    }

    @Test
    void renderAttribute_rejectsInvalidMetaAttributeDefinition() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("phase");
        request.setMetaAttributes(List.of(meta("ch.so.attr", "x", "Y")));

        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.BOOLEAN);

        TypeSpec spec = new TypeSpec();
        spec.setBaseType(baseType);
        request.setTypeSpec(spec);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> attributeTools.renderAttribute(request, "2.4"));
        assertTrue(ex.getMessage().contains("exactly one"));
    }

    @Test
    void renderAttribute_formatsReferenceType() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("ziel");

        ReferenceTypeSpec referenceType = new ReferenceTypeSpec();
        referenceType.setTargetClassFqn("Demo.Topic.Target");
        referenceType.setExternal(true);

        TypeSpec spec = new TypeSpec();
        spec.setReferenceType(referenceType);
        request.setTypeSpec(spec);

        AttributeTools.RenderedAttribute rendered = attributeTools.renderAttribute(request, "2.4");

        assertEquals("ziel : REFERENCE TO (EXTERNAL) Demo.Topic.Target;", rendered.text());
    }

    @Test
    void renderAttribute_formatsBlackboxType() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("payload");

        BlackboxTypeSpec blackboxType = new BlackboxTypeSpec();
        blackboxType.setKind(BlackboxTypeSpec.Kind.XML);

        TypeSpec spec = new TypeSpec();
        spec.setBlackboxType(blackboxType);
        request.setTypeSpec(spec);

        AttributeTools.RenderedAttribute rendered = attributeTools.renderAttribute(request, "2.4");

        assertEquals("payload : BLACKBOX XML;", rendered.text());
    }

    @Test
    void renderAttribute_formatsEnumTreeValueType() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("statusPfad");

        EnumTreeValueTypeSpec enumTreeValueType = new EnumTreeValueTypeSpec();
        enumTreeValueType.setEnumTreeDomainFqn("Demo.Topic.StatusTree");

        TypeSpec spec = new TypeSpec();
        spec.setEnumTreeValueType(enumTreeValueType);
        request.setTypeSpec(spec);

        AttributeTools.RenderedAttribute rendered = attributeTools.renderAttribute(request, "2.4");

        assertEquals("statusPfad : Demo.Topic.StatusTree;", rendered.text());
    }

    @Test
    void renderAttribute_formatsBasketType() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("korb");

        BasketTypeSpec basketType = new BasketTypeSpec();
        basketType.setKind(BasketTypeSpec.Kind.DATA);
        basketType.setTopicFqn("Demo.Topic");

        TypeSpec spec = new TypeSpec();
        spec.setBasketType(basketType);
        request.setTypeSpec(spec);

        AttributeTools.RenderedAttribute rendered = attributeTools.renderAttribute(request, "2.4");

        assertEquals("korb : BASKET (DATA) OF Demo.Topic;", rendered.text());
    }

    @Test
    void renderAttribute_formatsObjectType() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("objekte");

        ObjectTypeSpec objectType = new ObjectTypeSpec();
        objectType.setTargetClassFqn("Demo.Topic.Target");
        objectType.setObjects(true);

        TypeSpec spec = new TypeSpec();
        spec.setObjectType(objectType);
        request.setTypeSpec(spec);

        AttributeTools.RenderedAttribute rendered = attributeTools.renderAttribute(request, "2.4");

        assertEquals("objekte : OBJECTS OF Demo.Topic.Target;", rendered.text());
    }

    @Test
    void renderAttribute_formatsMetaobjectType() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("meta");

        MetaobjectTypeSpec metaobjectType = new MetaobjectTypeSpec();
        metaobjectType.setTableFqn("Demo.Topic.MetaCatalog");

        TypeSpec spec = new TypeSpec();
        spec.setMetaobjectType(metaobjectType);
        request.setTypeSpec(spec);

        AttributeTools.RenderedAttribute rendered = attributeTools.renderAttribute(request, "2.4");

        assertEquals("meta : METAOBJECT OF Demo.Topic.MetaCatalog;", rendered.text());
    }

    @Test
    void renderAttribute_rejectsMultipleTypeFamilies() {
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
            () -> attributeTools.renderAttribute(request, "2.4"));
        assertTrue(ex.getMessage().contains("exactly one"));
    }

    @Test
    void renderAttribute_generatedReferenceCompiles() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("ziel");

        ReferenceTypeSpec referenceType = new ReferenceTypeSpec();
        referenceType.setTargetClassFqn("Demo.Topic.Target");

        TypeSpec spec = new TypeSpec();
        spec.setReferenceType(referenceType);
        request.setTypeSpec(spec);

        String line = attributeTools.renderAttribute(request, "2.4").text();
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
    void renderAttribute_generatedBlackboxCompiles() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("payload");

        BlackboxTypeSpec blackboxType = new BlackboxTypeSpec();
        blackboxType.setKind(BlackboxTypeSpec.Kind.BINARY);

        TypeSpec spec = new TypeSpec();
        spec.setBlackboxType(blackboxType);
        request.setTypeSpec(spec);

        String line = attributeTools.renderAttribute(request, "2.4").text();
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
    void renderAttribute_generatedEnumTreeValueCompiles() {
        AttributeLineRequest request = new AttributeLineRequest();
        request.setName("pfad");

        EnumTreeValueTypeSpec enumTreeValueType = new EnumTreeValueTypeSpec();
        enumTreeValueType.setEnumTreeDomainFqn("Demo.Topic.EnumTree");

        TypeSpec spec = new TypeSpec();
        spec.setEnumTreeValueType(enumTreeValueType);
        request.setTypeSpec(spec);

        String line = attributeTools.renderAttribute(request, "2.4").text();
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
        ValidationTools validationTools = new ValidationTools(new IliCompilerService());
        Map<String, Object> result = validationTools.validateIliModel(modelText);
        assertEquals(true, result.get("valid"), () -> "Expected valid model but got " + result);
    }

    private MetaAttributeSpec meta(String name, String value, String rawValue) {
        MetaAttributeSpec metaAttribute = new MetaAttributeSpec();
        metaAttribute.setName(name);
        metaAttribute.setValue(value);
        metaAttribute.setRawValue(rawValue);
        return metaAttribute;
    }
    @Test
    void decimalStringsPreserveZeroAndScaleWithoutUnit() throws Exception {
        var mapper = new tools.jackson.databind.ObjectMapper();
        for (var bounds : java.util.List.of(java.util.List.of("0.0", "100.0"), java.util.List.of("0.00", "100.00"), java.util.List.of("0.00", "999.99"), java.util.List.of("-1.00", "9007199254740993.00"))) {
            BaseType base = mapper.readValue("{\"kind\":\"NUM_RANGE\",\"min\":\"" + bounds.get(0) + "\",\"max\":\"" + bounds.get(1) + "\"}", BaseType.class);
            TypeSpec spec = new TypeSpec(); spec.setBaseType(base);
            AttributeLineRequest request = new AttributeLineRequest(); request.setName("value"); request.setTypeSpec(spec);
            assertEquals("value : " + bounds.get(0) + " .. " + bounds.get(1) + ";", attributeTools.renderAttribute(request, "2.4").text());
        }
        BaseType invalid = mapper.readValue("{\"kind\":\"NUM_RANGE\",\"min\":\"0.0\",\"max\":\"100.00\"}", BaseType.class);
        TypeSpec invalidSpec = new TypeSpec(); invalidSpec.setBaseType(invalid);
        AttributeLineRequest invalidRequest = new AttributeLineRequest();
        invalidRequest.setName("percentage"); invalidRequest.setTypeSpec(invalidSpec);
        String message = assertThrows(IllegalArgumentException.class,
            () -> attributeTools.renderAttribute(invalidRequest, "2.4")).getMessage();
        assertTrue(message.contains("percentage"));
        assertTrue(message.contains("min=0.0 (scale=1)"));
        assertTrue(message.contains("max=100.00 (scale=2)"));
    }
}
