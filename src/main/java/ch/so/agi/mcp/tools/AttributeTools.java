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

  /**
   * New, strict version that supports numeric ranges and simple NUMERIC variants.
   * Input: AttributeLineRequest (name, mandatory?, collection?, typeSpec oneOf).
   * Output: AttributeLineResponse with a single ILI line.
   */
  @McpTool(
      name = "createAttributeLine",
      description = """
        Create a single INTERLIS attribute line with strict typing.
        Use exactly one typeSpec family: domainFqn, baseType, referenceType, blackboxType, enumTreeValueType, basketType, objectType or metaobjectType.
        Examples:
        - TEXT: {"baseType":{"kind":"TEXT","length":120}}
        - NUM_RANGE: {"baseType":{"kind":"NUM_RANGE","min":0.0,"max":100.0,"unitFqn":"INTERLIS.percent"}}
        - NUMERIC with refSys: {"baseType":{"kind":"NUMERIC","unitFqn":"INTERLIS.deg","refSysFqn":"MyModel.AngleRef","circular":true}}
        - Domain: {"domainFqn":"Demo.Farbe"}
        - Reference: {"referenceType":{"targetClassFqn":"Demo.Topic.Target","external":true}}
        - Blackbox: {"blackboxType":{"kind":"XML"}}
        - Enum tree value domain: {"enumTreeValueType":{"enumTreeDomainFqn":"Demo.Topic.StatusTree"}}
        - Basket: {"basketType":{"kind":"DATA","topicFqn":"Demo.Topic"}}
        """
  )
  public AttributeLineResponse createAttributeLine(
      @McpToolParam(description = "Structured attribute definition (name, mandatory, collection, typeSpec oneOf)", required = true)
      AttributeLineRequest req) {
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
    String rhs = switch (selectedType) {
      case String domainFqn -> {
        nv.validateFqn(domainFqn, "Domain FQN");
        yield domainFqn.trim();
      }
      case BaseType bt -> {
        bt.validate();
        yield switch (bt.getKind()) {
          case TEXT -> (bt.getLength() == null) ? "TEXT" : "TEXT*" + bt.getLength();
          case MTEXT -> (bt.getLength() == null) ? "MTEXT" : "MTEXT*" + bt.getLength();
          case NUMERIC, NUM_RANGE -> numericFragment(bt);
          case BOOLEAN -> "BOOLEAN";
          case COORD -> "COORD";
          case POLYLINE -> "POLYLINE";
          case SURFACE_SIMPLE -> "SURFACE WITH (STRAIGHTS) VERTEX COORD";
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
    return new AttributeLineResponse(AnnotationRenderer.renderAnnotations(req.getIliDoc(), req.getMetaAttributes()) + line);
  }

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
