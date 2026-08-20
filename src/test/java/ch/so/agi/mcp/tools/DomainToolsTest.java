package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.EnumTreeItem;
import ch.so.agi.mcp.model.EnumValueItem;
import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.service.IliCompilerService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DomainToolsTest {

  private final DomainTools domainTools = new DomainTools();

  @Test
  void createCoordDomainSnippet_usesExplicitAxesWithoutInventingRanges() {
    Map<String, Object> result = domainTools.createCoordDomainSnippet(
        "LocalCoord",
        List.of(axis("0.00", "100.00", "INTERLIS.m"), axis("10.00", "200.00", "INTERLIS.m")),
        null,
        null,
        null,
        null);

    assertEquals(
        """
            DOMAIN
              LocalCoord = COORD
                0.00 .. 100.00 [INTERLIS.m],
                10.00 .. 200.00 [INTERLIS.m];""",
        result.get("iliSnippet"));
  }

  @Test
  void createCoordDomainSnippet_rendersThreeAxesAndExplicitRotation() {
    Map<String, Object> result = domainTools.createCoordDomainSnippet(
        "Coord3",
        List.of(
            axis("2600000.0", "2600100.0", "INTERLIS.m"),
            axis("1200000.0", "1200100.0", "INTERLIS.m"),
            axis("-200.0", "5000.0", "INTERLIS.m")),
        2,
        1,
        null,
        null);

    assertEquals(
        """
            DOMAIN
              Coord3 = COORD
                2600000.0 .. 2600100.0 [INTERLIS.m],
                1200000.0 .. 1200100.0 [INTERLIS.m],
                -200.0 .. 5000.0 [INTERLIS.m],
                ROTATION 2 -> 1;""",
        result.get("iliSnippet"));
  }

  @Test
  void createCoordDomainSnippet_rejectsInvalidAxes() {
    assertThrows(
        IllegalArgumentException.class,
        () -> domainTools.createCoordDomainSnippet(
            "CoordX", List.of(axis("10", "0", "INTERLIS.m"), axis("0", "1", "INTERLIS.m")),
            null, null, null, null));
  }

  @Test
  void createCoordDomainSnippet_generatedThreeDimensionalDomainCompiles() {
    String snippet = String.valueOf(domainTools.createCoordDomainSnippet(
        "Coord3",
        List.of(
            axis("2600000.0", "2600100.0", "INTERLIS.m"),
            axis("1200000.0", "1200100.0", "INTERLIS.m"),
            axis("-200.0", "5000.0", "INTERLIS.m")),
        2, 1, null, null).get("iliSnippet"));

    IliCompilerService.CompilationResult compilation = new IliCompilerService().compile("""
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-20" =
        """ + snippet + "\n" + """
          TOPIC Data =
            CLASS Point =
              position : MANDATORY Coord3;
            END Point;
          END Data;
        END Demo.
        """, null);

    assertTrue(compilation.valid(), () -> "Expected generated COORD domain to compile: " + compilation.messages());
  }

  private DomainTools.CoordinateAxis axis(String min, String max, String unitFqn) {
    return new DomainTools.CoordinateAxis(new BigDecimal(min), new BigDecimal(max), unitFqn);
  }

  @Test
  void createUnitSnippet_rendersIliDoc() {
    Map<String, Object> result = domainTools.createUnit(
        "Kilometer", new BigDecimal("1000"), "INTERLIS.m", "Einheit", List.of());

    assertEquals(String.join("\n",
        "/** Einheit */",
        "UNIT",
        "  Kilometer = 1000 [INTERLIS.m];"), result.get("iliSnippet"));
  }

  @Test
  void createNumericDomainRejectsInvertedRangeAndInvalidUnit() {
    assertThrows(
        IllegalArgumentException.class,
        () -> domainTools.createNumericDomain("Height", "10", "0", "INTERLIS.m", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> domainTools.createNumericDomain("Height", "0", "10", "invalid unit", null, null));
  }

  @Test
  void createUnitSnippet_generatedUnitCompiles() {
    Map<String, Object> result = domainTools.createUnit(
        "Kilometer", new BigDecimal("1000"), "INTERLIS.m", null, null);

    ValidationTools validationTools = new ValidationTools(new IliCompilerService());
    Map<String, Object> validation = validationTools.validateIliModel("""
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-01" =
        """
        + result.get("iliSnippet") + "\n\n"
        + """
        END Demo.
        """);

    assertEquals(true, validation.get("valid"), () -> "Expected valid model but got " + validation);
  }

  @Test
  void createUnitSnippet_rejectsInvalidFactorAndMissingBase() {
    assertThrows(
        IllegalArgumentException.class,
        () -> domainTools.createUnit("Meter", BigDecimal.ZERO, "INTERLIS.m", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> domainTools.createUnit("Meter", BigDecimal.ONE, " ", null, null));
  }

  @Test
  void createEnumDomainSnippet_legacyItemsStillWork() {
    Map<String, Object> result = domainTools.createEnumDomain(
        "Status",
        List.of("A", "B"),
        null,
        null,
        null);

    assertEquals("""
        DOMAIN
          Status = (
            A,
            B
          );""", result.get("iliSnippet"));
  }

  @Test
  void createEnumDomainSnippet_itemSpecsRenderItemAnnotations() {
    Map<String, Object> result = domainTools.createEnumDomain(
        "GebaeudeArt",
        null,
        List.of(
            enumValue("Wohnhaus", "Wohngebaeude", List.of(meta("ili2db.dispName", "Wohngebaeude", null))),
            enumValue("Gewerbe", null, null)),
        null,
        null);

    assertEquals("""
        DOMAIN
          GebaeudeArt = (
            /** Wohngebaeude */
            !!@ ili2db.dispName="Wohngebaeude"
            Wohnhaus,
            Gewerbe
          );""", result.get("iliSnippet"));
  }

  @Test
  void createEnumDomainSnippet_rejectsItemsAndItemSpecsTogether() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> domainTools.createEnumDomain(
            "Status",
            List.of("A"),
            List.of(enumValue("B", null, null)),
            null,
            null));

    assertTrue(ex.getMessage().contains("Exactly one of 'items' or 'itemSpecs'"));
  }

  @Test
  void createEnumDomainSnippet_generatedDomainWithItemMetaCompiles() {
    Map<String, Object> result = domainTools.createEnumDomain(
        "GebaeudeArt",
        null,
        List.of(
            enumValue("Wohnhaus", null, List.of(meta("ili2db.dispName", "Wohngebaeude", null))),
            enumValue("Gewerbe", null, null)),
        null,
        null);

    ValidationTools validationTools = new ValidationTools(new IliCompilerService());
    Map<String, Object> validation = validationTools.validateIliModel("""
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-01" =
        """
        + result.get("iliSnippet") + "\n\n"
        + """
        END Demo.
        """);

    assertEquals(true, validation.get("valid"), () -> "Expected valid model but got " + validation);
  }

  @Test
  void createEnumTreeDomainSnippet_rendersNestedTree() {
    Map<String, Object> result = domainTools.createEnumTreeDomainSnippet(
        "StatusTree",
        List.of(
            item("A", List.of(item("B", null), item("C", null))),
            item("D", null)),
        null,
        null);

    assertEquals("""
        DOMAIN
          StatusTree = (
            A (
              B,
              C
            ),
            D
          );""", result.get("iliSnippet"));
  }

  @Test
  void createEnumTreeDomainSnippet_rendersItemAnnotations() {
    Map<String, Object> result = domainTools.createEnumTreeDomainSnippet(
        "StatusTree",
        List.of(
            treeItem("A", "Elternwert", List.of(meta("ili2db.dispName", "Eltern", null)),
                List.of(treeItem("B", null, List.of(meta("ili2db.dispName", "Kind", null)), null)))),
        null,
        null);

    assertEquals("""
        DOMAIN
          StatusTree = (
            /** Elternwert */
            !!@ ili2db.dispName="Eltern"
            A (
              !!@ ili2db.dispName="Kind"
              B
            )
          );""", result.get("iliSnippet"));
  }

  @Test
  void createEnumTreeDomainSnippet_rendersAnnotations() {
    Map<String, Object> result = domainTools.createEnumTreeDomainSnippet(
        "StatusTree",
        List.of(item("A", null)),
        "Baum",
        List.of(meta("ch.so.tree", "yes", null)));

    assertEquals("""
        /** Baum */
        !!@ ch.so.tree="yes"
        DOMAIN
          StatusTree = (
            A
          );""", result.get("iliSnippet"));
  }

  @Test
  void createEnumTreeDomainSnippet_rejectsDuplicateSiblingNames() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> domainTools.createEnumTreeDomainSnippet(
            "StatusTree",
            List.of(item("A", null), item("A", null)),
            null,
            null));

    assertTrue(ex.getMessage().contains("Duplicate enum item"));
  }

  @Test
  void createEnumTreeDomainSnippet_rejectsInvalidItemMetaAttribute() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> domainTools.createEnumTreeDomainSnippet(
            "StatusTree",
            List.of(treeItem("A", null, List.of(meta("ili2db.dispName", "x", "Y")), null)),
            null,
            null));

    assertTrue(ex.getMessage().contains("exactly one"));
  }

  @Test
  void createEnumTreeDomainSnippet_generatedDomainCompiles() {
    Map<String, Object> result = domainTools.createEnumTreeDomainSnippet(
        "StatusTree",
        List.of(
            treeItem("A", null, List.of(meta("ili2db.dispName", "A-Name", null)),
                List.of(treeItem("B", null, null, null))),
            treeItem("C", null, List.of(meta("ili2db.dispName", "C-Name", null)), null)),
        null,
        null);

    ValidationTools validationTools = new ValidationTools(new IliCompilerService());
    Map<String, Object> validation = validationTools.validateIliModel("""
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-01" =
        """
        + result.get("iliSnippet") + "\n\n"
        + """
        END Demo.
        """);

    assertEquals(true, validation.get("valid"), () -> "Expected valid model but got " + validation);
  }

  private EnumTreeItem item(String name, List<EnumTreeItem> children) {
    return treeItem(name, null, null, children);
  }

  private EnumTreeItem treeItem(String name, String iliDoc, List<MetaAttributeSpec> metaAttributes, List<EnumTreeItem> children) {
    EnumTreeItem item = new EnumTreeItem();
    item.setName(name);
    item.setIliDoc(iliDoc);
    item.setMetaAttributes(metaAttributes);
    item.setChildren(children);
    return item;
  }

  private EnumValueItem enumValue(String name, String iliDoc, List<MetaAttributeSpec> metaAttributes) {
    EnumValueItem item = new EnumValueItem();
    item.setName(name);
    item.setIliDoc(iliDoc);
    item.setMetaAttributes(metaAttributes);
    return item;
  }

  private MetaAttributeSpec meta(String name, String value, String rawValue) {
    MetaAttributeSpec metaAttribute = new MetaAttributeSpec();
    metaAttribute.setName(name);
    metaAttribute.setValue(value);
    metaAttribute.setRawValue(rawValue);
    return metaAttribute;
  }
}
