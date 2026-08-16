package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.EnumTreeItem;
import ch.so.agi.mcp.model.EnumValueItem;
import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.util.AnnotationRenderer;
import ch.so.agi.mcp.util.NameValidator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class DomainTools {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @McpTool(name = "createEnumDomainSnippet",
        description = "Erzeugt eine Aufzählungs-DOMAIN. Params: name (required), items (legacy list of enum item names) XOR itemSpecs (annotated enum items), iliDoc, metaAttributes.")
  public Map<String,Object> createEnumDomain(
      @McpToolParam(description = "Domain-Name", required = true) String name,
      @McpToolParam(description = "Enum-Items in Reihenfolge (Legacy-Variante ohne Item-Metaattribute)", required = false) @Nullable List<String> items,
      @McpToolParam(description = "Annotierte Enum-Items in Reihenfolge mit optional iliDoc und metaAttributes", required = false) @Nullable List<EnumValueItem> itemSpecs,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der DOMAIN", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der DOMAIN", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Domain name is required.");
    }

    NameValidator.ascii().validateIdent(name.trim(), "Domain name");
    List<EnumValueItem> normalizedItems = normalizeFlatEnumItems(items, itemSpecs);
    String snippet = AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes)
        + "DOMAIN\n  " + name.trim() + " = " + renderFlatEnumItems(normalizedItems, 1) + ";";
    return Map.of("iliSnippet", snippet);
  }

  @McpTool(name = "createNumericDomainSnippet",
        description = "Erzeugt eine numerische DOMAIN. Params: name (required), min, max (required), unitFQN (optional), iliDoc, metaAttributes.")
  public Map<String,Object> createNumericDomain(
      @McpToolParam(description = "Domain-Name", required = true) String name,
      @McpToolParam(description = "Minimum", required = true) String min,
      @McpToolParam(description = "Maximum", required = true) String max,
      @McpToolParam(description = "Einheits-FQN, z. B. 'INTERLIS.m'", required = false) @Nullable String unitFqn,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der DOMAIN", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der DOMAIN", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    String range = min.trim() + " .. " + max.trim();
    String unit = (unitFqn != null && !unitFqn.isBlank()) ? " [" + unitFqn.trim() + "]" : "";
    String snippet = AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes)
        + "DOMAIN\n  " + name + " = " + range + unit + ";";
    return Map.of("iliSnippet", snippet);
  }

  @McpTool(name = "createUnitSnippet",
        description = "Erzeugt eine UNIT-Definition. Params: name (required), kind (e.g. LENGTH), base (e.g. INTERLIS.m), iliDoc, metaAttributes.")
  public Map<String,Object> createUnit(
      @McpToolParam(description = "Einheiten-Name", required = true) String name,
      @McpToolParam(description = "Einheitsart, z. B. LENGTH, AREA", required = true) String kind,
      @McpToolParam(description = "Basis-Einheit, z. B. 'INTERLIS.m'", required = true) String base,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der UNIT", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der UNIT", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    String snippet = AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes)
        + "UNIT\n  " + name + " = " + kind.trim() + " [" + base.trim() + "];";
    return Map.of("iliSnippet", snippet);
  }

  @McpTool(name = "createCoordDomainSnippet",
        description = "Erzeugt eine COORD-DOMAIN (2D/3D). Params: name (required), dimension (optional, default 2 oder anhand Name=Coord3), decimals (optional, Default 3 = Millimeter), iliDoc, metaAttributes.")
  public Map<String, Object> createCoordDomainSnippet(
      @McpToolParam(description = "Domain-Name", required = true) String name,
      @McpToolParam(description = "Koordinatendimension (2 oder 3)", required = false) @Nullable Integer dimension,
      @McpToolParam(description = "Nachkommastellen (Default 3 = Millimeter)", required = false) @Nullable Integer decimals,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der DOMAIN", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der DOMAIN", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Domain name is required.");
    }

    int fractionDigits = decimals == null ? 3 : decimals;
    if (fractionDigits < 0 || fractionDigits > 9) {
      throw new IllegalArgumentException("decimals must be between 0 and 9.");
    }

    String trimmedName = name.trim();
    int coordDimension = dimension != null ? dimension : (trimmedName.endsWith("3") ? 3 : 2);
    if (coordDimension != 2 && coordDimension != 3) {
      throw new IllegalArgumentException("dimension must be 2 or 3.");
    }

    String snippet = AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes)
        + buildCoordDomain(trimmedName, coordDimension, fractionDigits);
    return Map.of("iliSnippet", snippet);
  }

  @McpTool(name = "createEnumTreeDomainSnippet",
      description = "Erzeugt eine verschachtelte Aufzählungs-DOMAIN. Params: name (required), items (required: recursive tree items), iliDoc, metaAttributes.")
  public Map<String, Object> createEnumTreeDomainSnippet(
      @McpToolParam(description = "Domain-Name", required = true) String name,
      @McpToolParam(description = "Rekursiver Enum-Baum. Jedes Element ist ein Objekt mit name (required) und optional iliDoc, metaAttributes und children mit derselben Struktur.", required = true) List<?> items,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der DOMAIN", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der DOMAIN", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Domain name is required.");
    }

    NameValidator.ascii().validateIdent(name.trim(), "Domain name");
    List<EnumTreeItem> normalizedItems = normalizeEnumTreeItems(items);
    validateEnumTreeItems(normalizedItems, "items");

    String snippet = AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes)
        + "DOMAIN\n  " + name.trim() + " = " + renderEnumTree(normalizedItems, 1) + ";";
    return Map.of("iliSnippet", snippet);
  }

  @McpTool(name = "createMetaAttributeBlock",
      description = "Erzeugt einen reinen INTERLIS-Metaattribut-Block aus !!@-Zeilen. Params: metaAttributes (required).")
  public Map<String, Object> createMetaAttributeBlock(
      @McpToolParam(description = "INTERLIS-Metaattribute in Reihenfolge", required = true) List<MetaAttributeSpec> metaAttributes
  ) {
    String snippet = AnnotationRenderer.renderMetaAttributeBlock(metaAttributes, true, true);
    return Map.of("iliSnippet", snippet);
  }

  private String buildCoordDomain(String name, int dimension, int decimals) {
    StringBuilder sb = new StringBuilder();
    sb.append("DOMAIN\n  ").append(name).append(" = COORD\n");
    sb.append("    ")
        .append(formatValue(460000, decimals))
        .append(" .. ")
        .append(formatValue(870000, decimals))
        .append(" [INTERLIS.m],\n");
    sb.append("    ")
        .append(formatValue(45000, decimals))
        .append(" .. ")
        .append(formatValue(310000, decimals))
        .append(" [INTERLIS.m]");

    if (dimension == 3) {
      sb.append(",\n    ")
          .append(formatValue(-200, decimals))
          .append(" .. ")
          .append(formatValue(5000, decimals))
          .append(" [INTERLIS.m]\n");
    } else {
      sb.append(",\n");
    }

    sb.append("    ROTATION 2 -> 1;");
    return sb.toString();
  }

  private String formatValue(double value, int decimals) {
    String format = "%." + decimals + "f";
    return String.format(Locale.ROOT, format, value);
  }

  private List<EnumValueItem> normalizeFlatEnumItems(
      @Nullable List<String> items,
      @Nullable List<EnumValueItem> itemSpecs) {
    boolean hasItems = items != null && !items.isEmpty();
    boolean hasItemSpecs = itemSpecs != null && !itemSpecs.isEmpty();
    if (hasItems == hasItemSpecs) {
      throw new IllegalArgumentException("Exactly one of 'items' or 'itemSpecs' must be provided.");
    }

    if (hasItemSpecs) {
      return validateFlatEnumItems(itemSpecs, "itemSpecs");
    }

    List<EnumValueItem> normalized = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      String itemName = items.get(i);
      if (itemName == null || itemName.isBlank()) {
        throw new IllegalArgumentException("items[" + i + "] must not be blank.");
      }

      EnumValueItem item = new EnumValueItem();
      item.setName(itemName.trim());
      normalized.add(item);
    }
    return validateFlatEnumItems(normalized, "items");
  }

  private List<EnumTreeItem> normalizeEnumTreeItems(List<?> items) {
    return items.stream()
        .map(item -> item instanceof EnumTreeItem treeItem
            ? treeItem
            : OBJECT_MAPPER.convertValue(item, EnumTreeItem.class))
        .toList();
  }

  private List<EnumValueItem> validateFlatEnumItems(List<EnumValueItem> items, String path) {
    if (items == null || items.isEmpty()) {
      throw new IllegalArgumentException(path + " must not be empty.");
    }

    Set<String> siblingNames = new LinkedHashSet<>();
    for (int i = 0; i < items.size(); i++) {
      EnumValueItem item = items.get(i);
      if (item == null) {
        throw new IllegalArgumentException(path + "[" + i + "] must not be null.");
      }
      if (item.getName() == null || item.getName().isBlank()) {
        throw new IllegalArgumentException(path + "[" + i + "].name is required.");
      }

      String trimmedName = item.getName().trim();
      NameValidator.ascii().validateIdent(trimmedName, "Enum item name");
      if (!siblingNames.add(trimmedName)) {
        throw new IllegalArgumentException("Duplicate enum item '" + trimmedName + "' in " + path + ".");
      }
      item.setName(trimmedName);
      AnnotationRenderer.normalizeMetaAttributes(item.getMetaAttributes(), false, false);
    }
    return items;
  }

  private void validateEnumTreeItems(@Nullable List<EnumTreeItem> items, String path) {
    if (items == null || items.isEmpty()) {
      throw new IllegalArgumentException(path + " must not be empty.");
    }

    Set<String> siblingNames = new LinkedHashSet<>();
    for (int i = 0; i < items.size(); i++) {
      EnumTreeItem item = items.get(i);
      if (item == null) {
        throw new IllegalArgumentException(path + "[" + i + "] must not be null.");
      }
      if (item.getName() == null || item.getName().isBlank()) {
        throw new IllegalArgumentException(path + "[" + i + "].name is required.");
      }

      String trimmedName = item.getName().trim();
      NameValidator.ascii().validateIdent(trimmedName, "Enum item name");
      if (!siblingNames.add(trimmedName)) {
        throw new IllegalArgumentException("Duplicate enum item '" + trimmedName + "' in " + path + ".");
      }
      item.setName(trimmedName);
      AnnotationRenderer.normalizeMetaAttributes(item.getMetaAttributes(), false, false);

      if (item.getChildren() != null && !item.getChildren().isEmpty()) {
        validateEnumTreeItems(item.getChildren(), path + "[" + trimmedName + "].children");
      }
    }
  }

  private String renderFlatEnumItems(List<EnumValueItem> items, int level) {
    String indent = "  ".repeat(level);
    String childIndent = "  ".repeat(level + 1);
    return "(\n" + items.stream()
        .map(item -> renderAnnotatedEnumLine(item.getName(), item.getIliDoc(), item.getMetaAttributes(), childIndent))
        .collect(Collectors.joining(",\n"))
        + "\n" + indent + ")";
  }

  private String renderEnumTree(List<EnumTreeItem> items, int level) {
    String indent = "  ".repeat(level);
    return "(\n" + items.stream()
        .map(item -> renderEnumTreeItem(item, level + 1))
        .collect(Collectors.joining(",\n"))
        + "\n" + indent + ")";
  }

  private String renderEnumTreeItem(EnumTreeItem item, int level) {
    String line;
    if (item.getChildren() == null || item.getChildren().isEmpty()) {
      line = item.getName();
    } else {
      line = item.getName() + " " + renderEnumTree(item.getChildren(), level);
    }

    String indent = "  ".repeat(level);
    return renderAnnotatedEnumLine(line, item.getIliDoc(), item.getMetaAttributes(), indent);
  }

  private String renderAnnotatedEnumLine(
      String line,
      @Nullable String iliDoc,
      @Nullable List<MetaAttributeSpec> metaAttributes,
      String indent) {
    String annotations = AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes);
    if (annotations.isEmpty()) {
      return indent + line;
    }
    return AnnotationRenderer.indentBlock(annotations, indent) + "\n" + indent + line;
  }

}
