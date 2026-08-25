package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.*;
import ch.so.agi.mcp.model.AttributeLineRequest.Collection;
import ch.so.agi.mcp.util.AnnotationRenderer;
import ch.so.agi.mcp.util.NameValidator;
import java.math.BigDecimal;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class AttributeTools {

  private final GeometryTypeRenderer geometryRenderer;

  public AttributeTools() {
    this(new GeometryTypeRenderer());
  }

  public AttributeTools(GeometryTypeRenderer geometryRenderer) {
    this.geometryRenderer = geometryRenderer;
  }

  /**
   * New, strict version that supports numeric ranges and simple NUMERIC variants.
   * Input: AttributeLineRequest (name, mandatory?, collection?, typeSpec oneOf).
   * Output: AttributeLineResponse with a single ILI line.
   */
  public AttributeLineResponse createAttributeLine(
      AttributeLineRequest req) {
    return renderAttribute(req, "2.4").response();
  }

  public RenderedAttribute renderAttribute(AttributeLineRequest req, String iliVersion) {
    // ---- basic checks
    if (req.getName() == null || req.getName().isBlank()) {
      throw new IllegalArgumentException("Attribute 'name' is required.");
    }

    var nv = NameValidator.ascii();
    nv.validateIdent(req.getName(), "Attribute name");

    if (req.getTypeSpec() == null) {
      throw new IllegalArgumentException("typeSpec is required.");
    }

    Object selectedType = req.getTypeSpec().requireSingleType();

    // ---- build RHS (type)
    java.util.List<String> requiredImports = new java.util.ArrayList<>();
    String rhs = switch (selectedType) {
      case TypeSpec.NamedType namedType -> {
        nv.validateFqn(namedType.fqn(), namedType.structure() ? "Structure FQN" : "Domain FQN");
        yield namedType.fqn().trim();
      }
      case BaseType bt -> {
        bt.validate();
        yield switch (bt.getKind()) {
          case TEXT -> (bt.getLength() == null) ? "TEXT" : "TEXT*" + bt.getLength();
          case MTEXT -> (bt.getLength() == null) ? "MTEXT" : "MTEXT*" + bt.getLength();
          case NUMERIC, NUM_RANGE -> numericFragment(bt);
          case BOOLEAN -> "BOOLEAN";
        };
      }
      case ReferenceTypeSpec ref -> {
        ref.validate();
        nv.validateFqn(ref.getTargetClassFqn(), "Reference target FQN");
        yield "REFERENCE TO" + (Boolean.TRUE.equals(ref.getExternal()) ? " (EXTERNAL) " : " ")
            + ref.getTargetClassFqn().trim();
      }
      case BlackboxTypeSpec blackbox -> {
        blackbox.validate();
        yield "BLACKBOX " + blackbox.getKind().name();
      }
      case EnumTreeValueTypeSpec enumTreeValueType -> {
        enumTreeValueType.validate();
        nv.validateFqn(enumTreeValueType.getEnumTreeDomainFqn(), "Enum tree domain FQN");
        yield enumTreeValueType.getEnumTreeDomainFqn().trim();
      }
      case BasketTypeSpec basket -> {
        basket.validate();
        nv.validateFqn(basket.getTopicFqn(), "Basket topic FQN");
        String kind = basket.getKind() == null ? "" : " (" + basket.getKind().name() + ")";
        yield "BASKET" + kind + " OF " + basket.getTopicFqn().trim();
      }
      case ObjectTypeSpec objectType -> {
        objectType.validate();
        nv.validateFqn(objectType.getTargetClassFqn(), "Object target FQN");
        yield (Boolean.TRUE.equals(objectType.getObjects()) ? "OBJECTS OF " : "OBJECT OF ")
            + objectType.getTargetClassFqn().trim();
      }
      case MetaobjectTypeSpec metaobjectType -> {
        metaobjectType.validate();
        nv.validateFqn(metaobjectType.getTableFqn(), "Metaobject table FQN");
        yield "METAOBJECT OF " + metaobjectType.getTableFqn().trim();
      }
      case GeometryTypeSpec geometryType -> {
        GeometryTypeRenderer.RenderedGeometry rendered = geometryRenderer.render(geometryType, iliVersion);
        requiredImports.addAll(rendered.requiredImports());
        yield rendered.typeText();
      }
      default -> throw new IllegalArgumentException("Unsupported typeSpec configuration.");
    };

    // ---- prefix (mandatory + collection)
    String prefix = Boolean.TRUE.equals(req.getMandatory()) ? "MANDATORY " : "";
    Collection col = (req.getCollection() == null) ? Collection.NONE : req.getCollection();
    String collectionPrefix = switch (col) {
      case NONE -> "";
      case LIST_OF -> "LIST OF ";
      case BAG_OF -> "BAG OF ";
    };

    String line = req.getName().trim() + " : " + prefix + collectionPrefix + rhs + ";";
    return new RenderedAttribute(
        new AttributeLineResponse(AnnotationRenderer.renderAnnotations(req.getIliDoc(), req.getMetaAttributes()) + line),
        java.util.List.copyOf(requiredImports));
  }

  public record RenderedAttribute(AttributeLineResponse response, java.util.List<String> requiredImports) {}

  private String numericFragment(BaseType bt) {
    boolean hasUnit = bt.getUnitFqn() != null && !bt.getUnitFqn().isBlank();
    var sb = new StringBuilder();
    if (bt.getKind() == BaseType.Kind.NUM_RANGE) {
      sb.append(formatRangeValue(bt.getMin(), hasUnit))
          .append(" .. ")
          .append(formatRangeValue(bt.getMax(), hasUnit));
    } else {
      sb.append("NUMERIC");
    }

    if (hasUnit) {
      sb.append(" [").append(bt.getUnitFqn().trim()).append("]");
    }

    boolean hasRefSys = bt.getRefSysFqn() != null && !bt.getRefSysFqn().isBlank();
    boolean circular = Boolean.TRUE.equals(bt.getCircular());
    if (hasRefSys || circular) {
      sb.append(" {");
      if (hasRefSys) {
        sb.append("REFSYS ").append(bt.getRefSysFqn().trim());
        if (circular) sb.append(" CIRCULAR");
      } else {
        sb.append("CIRCULAR");
      }
      sb.append("}");
    }

    return sb.toString();
  }

  private String formatRangeValue(BigDecimal value, boolean keepScale) {
    if (value == null) {
      throw new IllegalArgumentException("NUM_RANGE requires non-null range bounds.");
    }
    return (keepScale ? value : value.stripTrailingZeros()).toPlainString();
  }
}
