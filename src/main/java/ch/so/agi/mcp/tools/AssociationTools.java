package ch.so.agi.mcp.tools;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import ch.so.agi.mcp.util.NameValidator;

import java.util.List;
import java.util.Map;

@Component
public class AssociationTools {

  public static class Role {
    public String name;
    public String classFQN;
    public String card; // e.g. {1}, {0..1}, {1..*}
  }

  @McpTool(name = "createAssociationSnippet",
        description = "Erzeugt eine ASSOCIATION. Params: name (required), roles (2+ Rollen mit name,classFQN,card).")
  public Map<String,Object> createAssociation(
      @McpToolParam(description = "Assoziationsname", required = true) String name,
      @McpToolParam(description = "Rollen (mindestens 2)", required = true) List<Role> roles
  ) {
      
      var nv = NameValidator.ascii();
   
    StringBuilder sb = new StringBuilder();
    sb.append("ASSOCIATION ").append(name).append(" =\n");
    for (Role r : roles) {
        nv.validateIdent(r.name, "Association role name");
        nv.validateFqn(r.classFQN, "Association role class FQN");

      sb.append("  ").append(r.name).append(" -- ").append(r.card).append(" ").append(r.classFQN).append(";\n");
    }
    sb.append("END ").append(name).append(";");
    return Map.of("iliSnippet", sb.toString(), "cursorHint", Map.of("line", 1, "col", 2));
  }
}
