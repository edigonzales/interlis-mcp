package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.EnumTreeItem;
import ch.so.agi.mcp.model.EnumValueItem;
import ch.so.agi.mcp.model.MetaAttributeSpec;
import ch.so.agi.mcp.util.AnnotationRenderer;
import ch.so.agi.mcp.util.NameValidator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class DomainTools {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public static final class CoordinateAxis {
    @JsonProperty(required = true)
    private BigDecimal min;
    @JsonProperty(required = true)
    private BigDecimal max;
    @JsonProperty(required = true)
    private String unitFqn;

    public CoordinateAxis() {}

    public CoordinateAxis(BigDecimal min, BigDecimal max, String unitFqn) {
      this.min = min;
      this.max = max;
      this.unitFqn = unitFqn;
    }

    public BigDecimal getMin() {
      return min;
    }

    public void setMin(BigDecimal min) {
      this.min = min;
    }

    public BigDecimal getMax() {
      return max;
    }

    public void setMax(BigDecimal max) {
      this.max = max;
    }

    public String getUnitFqn() {
      return unitFqn;
    }

    public void setUnitFqn(String unitFqn) {
      this.unitFqn = unitFqn;
    }
  }

  public Map<String,Object> createEnumDomain(
      @McpToolParam(description = "Domain-Name", required = true) String name,
      @McpToolParam(description = "Enum-Items in Reihenfolge (einfache Variante ohne Item-Metaattribute)", required = false) @Nullable List<String> items,
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

  public Map<String,Object> createNumericDomain(
      @McpToolParam(description = "Domain-Name", required = true) String name,
      @McpToolParam(description = "Minimum", required = true) String min,
      @McpToolParam(description = "Maximum", required = true) String max,
      @McpToolParam(description = "Einheits-FQN, z. B. 'INTERLIS.m'", required = false) @Nullable String unitFqn,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der DOMAIN", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der DOMAIN", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    if (name == null || name.isBlank() || min == null || min.isBlank() || max == null || max.isBlank()) {
      throw new IllegalArgumentException("Numeric domain requires name, min and max.");
    }
    String normalizedName = name.trim();
    String normalizedMin = min.trim();
    String normalizedMax = max.trim();
    if (!normalizedMin.matches("-?\\d+(?:\\.\\d+)?")
        || !normalizedMax.matches("-?\\d+(?:\\.\\d+)?")) {
      throw new IllegalArgumentException("Numeric domain min and max must use INTERLIS decimal notation.");
    }
    NameValidator validator = NameValidator.ascii();
    validator.validateIdent(normalizedName, "Domain name");
    BigDecimal minimum;
    BigDecimal maximum;
    try {
      minimum = new BigDecimal(normalizedMin);
      maximum = new BigDecimal(normalizedMax);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Numeric domain min and max must be decimal numbers.");
    }
    if (minimum.compareTo(maximum) >= 0) {
      throw new IllegalArgumentException("Numeric domain requires min < max.");
    }
    String unit = "";
    if (unitFqn != null && !unitFqn.isBlank()) {
      validator.validateFqn(unitFqn.trim(), "Unit FQN");
      unit = " [" + unitFqn.trim() + "]";
    }
    String range = normalizedMin + " .. " + normalizedMax;
    String snippet = AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes)
        + "DOMAIN\n  " + normalizedName + " = " + range + unit + ";";
    return Map.of("iliSnippet", snippet);
  }

  public Map<String,Object> createUnit(
      @McpToolParam(description = "Einheiten-Name", required = true) String name,
      @McpToolParam(description = "Positiver Faktor zur Basis-Einheit, z. B. 1000 für Kilometer", required = true) BigDecimal factor,
      @McpToolParam(description = "Basis-Unit, z. B. 'INTERLIS.m'", required = true) String base,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der UNIT", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der UNIT", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Unit name is required.");
    }
    if (factor == null || factor.signum() <= 0) {
      throw new IllegalArgumentException("Unit factor must be greater than zero.");
    }
    if (base == null || base.isBlank()) {
      throw new IllegalArgumentException("Base unit reference is required.");
    }

    NameValidator validator = NameValidator.ascii();
    String trimmedName = name.trim();
    String trimmedBase = base.trim();
    validator.validateIdent(trimmedName, "Unit name");
    validator.validateFqn(trimmedBase, "Base unit reference");

    String snippet = AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes)
        + "UNIT\n  " + trimmedName + " = " + factor.stripTrailingZeros().toPlainString()
        + " [" + trimmedBase + "];";
    return Map.of("iliSnippet", snippet);
  }

  public Map<String, Object> createCoordDomainSnippet(
      @McpToolParam(description = "Domain-Name", required = true) String name,
      @McpToolParam(description = "Zwei oder drei Achsen mit min, max und unitFqn", required = true) List<CoordinateAxis> axes,
      @McpToolParam(description = "Optionale erste ROTATION-Achse (1-basiert; nur zusammen mit rotationTo)", required = false) @Nullable Integer rotationFrom,
      @McpToolParam(description = "Optionale zweite ROTATION-Achse (1-basiert; nur zusammen mit rotationFrom)", required = false) @Nullable Integer rotationTo,
      @McpToolParam(description = "IliDoc-Blockkommentar direkt vor der DOMAIN", required = false) @Nullable String iliDoc,
      @McpToolParam(description = "INTERLIS-Metaattribute direkt vor der DOMAIN", required = false) @Nullable List<MetaAttributeSpec> metaAttributes
  ) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Domain name is required.");
    }

    if (axes == null || (axes.size() != 2 && axes.size() != 3)) {
      throw new IllegalArgumentException("axes must contain exactly two or three coordinate axes.");
    }

    String trimmedName = name.trim();
    NameValidator validator = NameValidator.ascii();
    validator.validateIdent(trimmedName, "Domain name");
    for (int index = 0; index < axes.size(); index++) {
      CoordinateAxis axis = axes.get(index);
      if (axis == null || axis.getMin() == null || axis.getMax() == null) {
        throw new IllegalArgumentException("axes[" + index + "] requires min and max.");
      }
      if (axis.getMin().compareTo(axis.getMax()) >= 0) {
        throw new IllegalArgumentException("axes[" + index + "].min must be smaller than max.");
      }
      if (axis.getUnitFqn() == null || axis.getUnitFqn().isBlank()) {
        throw new IllegalArgumentException("axes[" + index + "].unitFqn is required.");
      }
      validator.validateFqn(axis.getUnitFqn().trim(), "Coordinate axis unit");
    }

    if ((rotationFrom == null) != (rotationTo == null)) {
      throw new IllegalArgumentException("rotationFrom and rotationTo must be provided together.");
    }
    if (rotationFrom != null
        && (rotationFrom < 1 || rotationFrom > axes.size()
            || rotationTo < 1 || rotationTo > axes.size()
            || rotationFrom.equals(rotationTo))) {
      throw new IllegalArgumentException("ROTATION axes must be distinct and within the coordinate dimensions.");
    }

    StringBuilder definition = new StringBuilder("DOMAIN\n  ")
        .append(trimmedName)
        .append(" = COORD\n");
    for (int index = 0; index < axes.size(); index++) {
      CoordinateAxis axis = axes.get(index);
      definition.append("    ")
          .append(axis.getMin().toPlainString())
          .append(" .. ")
          .append(axis.getMax().toPlainString())
          .append(" [")
          .append(axis.getUnitFqn().trim())
          .append("]");
      if (index < axes.size() - 1 || rotationFrom != null) {
        definition.append(",");
      }
      definition.append("\n");
    }
    if (rotationFrom != null) {
      definition.append("    ROTATION ").append(rotationFrom).append(" -> ").append(rotationTo).append("\n");
    }
    definition.setLength(definition.length() - 1);
    definition.append(";");

    String snippet = AnnotationRenderer.renderAnnotations(iliDoc, metaAttributes)
        + definition;
    return Map.of("iliSnippet", snippet);
  }

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
      AnnotationRenderer.normalizeMetaAttributes(item.getMetaAttributes(), false, true);
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
      AnnotationRenderer.normalizeMetaAttributes(item.getMetaAttributes(), false, true);

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
