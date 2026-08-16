package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.EnumTreeItem;
import ch.so.agi.mcp.model.EnumValueItem;
import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.service.IliCompilerService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DomainToolsTest {

  private final DomainTools domainTools = new DomainTools();

  @Test
  void createCoordDomainSnippet_defaultsTo2dAndMillimeter() {
    Map<String, Object> result = domainTools.createCoordDomainSnippet("Coord2", null, null, null, null);

    assertEquals(
        """
            DOMAIN
              Coord2 = COORD
                460000.000 .. 870000.000 [INTERLIS.m],
                45000.000 .. 310000.000 [INTERLIS.m],
                ROTATION 2 -> 1;""",
        result.get("iliSnippet"));
  }

  @Test
  void createCoordDomainSnippet_infers3dFromNameAndRespectsDecimals() {
    Map<String, Object> result = domainTools.createCoordDomainSnippet("Coord3", null, 1, null, null);

    assertEquals(
        """
            DOMAIN
              Coord3 = COORD
                460000.0 .. 870000.0 [INTERLIS.m],
                45000.0 .. 310000.0 [INTERLIS.m],
                -200.0 .. 5000.0 [INTERLIS.m]
                ROTATION 2 -> 1;""",
        result.get("iliSnippet"));
  }

  @Test
  void createCoordDomainSnippet_rejectsInvalidDimension() {
    assertThrows(
        IllegalArgumentException.class,
        () -> domainTools.createCoordDomainSnippet("CoordX", 4, null, null, null));
  }

  @Test
  void createUnitSnippet_rendersIliDoc() {
    Map<String, Object> result = domainTools.createUnit("Meter", "LENGTH", "INTERLIS.m", "Einheit", List.of());

    assertEquals(String.join("\n",
        "/** Einheit */",
        "UNIT",
        "  Meter = LENGTH [INTERLIS.m];"), result.get("iliSnippet"));
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
        """, null);

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
        """, null);

    assertEquals(true, validation.get("valid"), () -> "Expected valid model but got " + validation);
  }

  @Test
  void createMetaAttributeBlock_rendersStringAndRawValues() {
    Map<String, Object> result = domainTools.createMetaAttributeBlock(List.of(
        meta("title", "Demo", null),
        meta("ch.so.flag", null, "TRUE")));

    assertEquals("""
        !!@ title="Demo"
        !!@ ch.so.flag=TRUE""", result.get("iliSnippet"));
  }

  @Test
  void createMetaAttributeBlock_rejectsEmptyList() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> domainTools.createMetaAttributeBlock(List.of()));

    assertTrue(ex.getMessage().contains("At least one"));
  }

  @Test
  void createMetaAttributeBlock_rejectsDuplicateNames() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> domainTools.createMetaAttributeBlock(List.of(
            meta("title", "a", null),
            meta("title", "b", null))));

    assertTrue(ex.getMessage().contains("Duplicate meta attribute"));
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
