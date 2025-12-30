package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.*;
import ch.so.agi.mcp.model.AttributeLineRequest.Collection;
import ch.so.agi.mcp.util.NameValidator;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class AttributeTools {

  /**
   * New, strict version that prevents illegal types like bare "NUMERIC".
   * Input: AttributeLineRequest (name, mandatory?, collection?, typeSpec oneOf).
   * Output: AttributeLineResponse with a single ILI line.
   */
  @Tool(
      name = "createAttributeLineV2",
      description = """
        Create a single INTERLIS attribute line with strict typing.
        Use either typeSpec.domainFqn or typeSpec.baseType (TEXT, NUM_RANGE, BOOLEAN, COORD, POLYLINE, SURFACE_SIMPLE).
        Examples:
        - TEXT: {"baseType":{"kind":"TEXT","length":120}}
        - NUM_RANGE: {"baseType":{"kind":"NUM_RANGE","min":0.0,"max":100.0,"unitFqn":"INTERLIS.percent"}}
        - Domain: {"domainFqn":"Demo.Farbe"}
        """
  )
  public AttributeLineResponse createAttributeLine(AttributeLineRequest req) {
    // ---- basic checks
    if (req.getName() == null || req.getName().isBlank()) {
      throw new IllegalArgumentException("Attribute 'name' is required.");
    }
    
    var nv = NameValidator.ascii(); 
    nv.validateIdent(req.getName(), "Attribute name");

    
    if (req.getTypeSpec() == null) {
      throw new IllegalArgumentException("typeSpec is required.");
    }
    
    var ts = req.getTypeSpec();
    if (ts.getDomainFqn() != null && !ts.getDomainFqn().isBlank()) {
      nv.validateFqn(ts.getDomainFqn(), "Domain FQN");
    }

    req.getTypeSpec().validateOneOf();
    

    // ---- build RHS (type)
    String rhs;
    if (req.getTypeSpec().getDomainFqn() != null && !req.getTypeSpec().getDomainFqn().isBlank()) {
      rhs = req.getTypeSpec().getDomainFqn().trim();
    } else {

        var bt = req.getTypeSpec().getBaseType();
        bt.validate();
        rhs = switch (bt.getKind()) {
          case TEXT -> (bt.getLength() == null) ? "TEXT" : "TEXT*" + bt.getLength();
          case MTEXT -> (bt.getLength() == null) ? "MTEXT" : "MTEXT*" + bt.getLength();
          case NUM_RANGE -> {
            String unitPart = (bt.getUnitFqn() != null && !bt.getUnitFqn().isBlank())
                ? " [" + bt.getUnitFqn().trim() + "]" : "";
            yield bt.getMin() + " .. " + bt.getMax() + unitPart;
          }
          case BOOLEAN -> "BOOLEAN";
          case COORD -> "COORD";
          case POLYLINE -> "POLYLINE";
          case SURFACE_SIMPLE -> "SURFACE WITH (STRAIGHTS) VERTEX COORD";
        };
    }

    // ---- prefix (mandatory + collection)
    String prefix = Boolean.TRUE.equals(req.getMandatory()) ? "MANDATORY " : "";
    Collection col = (req.getCollection() == null) ? Collection.NONE : req.getCollection();
    String collectionPrefix = switch (col) {
      case NONE -> "";
      case LIST_OF -> "LIST OF ";
      case BAG_OF -> "BAG OF ";
    };

    String line = req.getName().trim() + " : " + prefix + collectionPrefix + rhs + ";";
    return new AttributeLineResponse(line);
  }
}
