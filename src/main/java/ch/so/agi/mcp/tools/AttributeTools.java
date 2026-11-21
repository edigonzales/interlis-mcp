package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.*;
import ch.so.agi.mcp.model.AttributeLineRequest.Collection;
import ch.so.agi.mcp.util.AnnotationRenderer;
import ch.so.agi.mcp.util.NameValidator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
    List<String> referencedFqns = new ArrayList<>();
    String rhs = switch (selectedType) {
      case TypeSpec.NamedType namedType -> {
        nv.validateFqn(namedType.fqn(), namedType.structure() ? "Structure FQN" : "Domain FQN");
        referencedFqns.add(namedType.fqn());
        yield namedType.fqn().trim();
      }
      case BaseType bt -> {
        try {
          bt.validate();
        } catch (IllegalArgumentException error) {
          throw new IllegalArgumentException("Attribute " + req.getName() + ": " + error.getMessage(), error);
        }
        yield switch (bt.getKind()) {
          case TEXT -> (bt.getLength() == null) ? "TEXT" : "TEXT*" + bt.getLength();
          case MTEXT -> (bt.getLength() == null) ? "MTEXT" : "MTEXT*" + bt.getLength();
          case NUMERIC, NUM_RANGE -> numericFragment(bt, referencedFqns);
          case BOOLEAN -> "BOOLEAN";
        };
      }
      case ReferenceTypeSpec ref -> {
        ref.validate();
        nv.validateFqn(ref.getTargetClassFqn(), "Reference target FQN");
        referencedFqns.add(ref.getTargetClassFqn());
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
        referencedFqns.add(enumTreeValueType.getEnumTreeDomainFqn());
        yield enumTreeValueType.getEnumTreeDomainFqn().trim();
      }
      case BasketTypeSpec basket -> {
        basket.validate();
        nv.validateFqn(basket.getTopicFqn(), "Basket topic FQN");
        referencedFqns.add(basket.getTopicFqn());
        String kind = basket.getKind() == null ? "" : " (" + basket.getKind().name() + ")";
        yield "BASKET" + kind + " OF " + basket.getTopicFqn().trim();
      }
      case ObjectTypeSpec objectType -> {
        objectType.validate();
        nv.validateFqn(objectType.getTargetClassFqn(), "Object target FQN");
        referencedFqns.add(objectType.getTargetClassFqn());
        yield (Boolean.TRUE.equals(objectType.getObjects()) ? "OBJECTS OF " : "OBJECT OF ")
            + objectType.getTargetClassFqn().trim();
      }
      case MetaobjectTypeSpec metaobjectType -> {
        metaobjectType.validate();
        nv.validateFqn(metaobjectType.getTableFqn(), "Metaobject table FQN");
        referencedFqns.add(metaobjectType.getTableFqn());
        yield "METAOBJECT OF " + metaobjectType.getTableFqn().trim();
      }
      case GeometryTypeSpec geometryType -> {
        GeometryTypeRenderer.RenderedGeometry rendered = geometryRenderer.render(geometryType, iliVersion);
        referencedFqns.add(rendered.domainFqn());
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
        AnnotationRenderer.renderAnnotations(req.getIliDoc(), req.getMetaAttributes()) + line,
        List.copyOf(referencedFqns));
  }

  public record RenderedAttribute(String text, List<String> referencedFqns) {}

  private String numericFragment(BaseType bt, List<String> referencedFqns) {
    boolean hasUnit = bt.getUnitFqn() != null && !bt.getUnitFqn().isBlank();
    var sb = new StringBuilder();
    if (bt.getKind() == BaseType.Kind.NUM_RANGE) {
      sb.append(formatRangeValue(bt.getMin()))
          .append(" .. ")
          .append(formatRangeValue(bt.getMax()));
    } else {
      sb.append("NUMERIC");
    }

    if (hasUnit) {
      referencedFqns.add(bt.getUnitFqn());
      sb.append(" [").append(bt.getUnitFqn().trim()).append("]");
    }

    boolean hasRefSys = bt.getRefSysFqn() != null && !bt.getRefSysFqn().isBlank();
    boolean circular = Boolean.TRUE.equals(bt.getCircular());
    if (hasRefSys || circular) {
      sb.append(" {");
      if (hasRefSys) {
        referencedFqns.add(bt.getRefSysFqn());
        sb.append("REFSYS ").append(bt.getRefSysFqn().trim());
        if (circular) sb.append(" CIRCULAR");
      } else {
        sb.append("CIRCULAR");
      }
      sb.append("}");
    }

    return sb.toString();
  }

  private String formatRangeValue(BigDecimal value) {
    if (value == null) {
      throw new IllegalArgumentException("NUM_RANGE requires non-null range bounds.");
    }
    return value.toPlainString();
  }
}
