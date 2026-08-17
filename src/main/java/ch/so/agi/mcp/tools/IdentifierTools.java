package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.util.NameValidator;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class IdentifierTools {

  @McpTool(name = "sanitizeIdentifier",
      description = "Sanitize a string to a valid INTERLIS 2.4 identifier: ASCII letter first, then ASCII letters/digits/underscore, max 255 characters, and not a reserved word. Returns the sanitized value and whether it changed.")
  public Map<String, Object> sanitizeIdentifier(
      @McpToolParam(description = "Free-form value", required = true) String value) {
    if (value == null) {
      value = "";
    }
    String trimmed = value.trim();

    String s = trimmed.replaceAll("[^A-Za-z0-9_]", "_");
    if (s.isEmpty() || !s.substring(0, 1).matches("[A-Za-z]")) {
      s = "X_" + s;
    }
    s = s.replaceAll("_+", "_");
    s = s.replaceAll("_+$", "");
    if (s.length() > 255) {
      s = s.substring(0, 255);
    }
    if (NameValidator.isReservedWord(s)) {
      s = s + "_";
    }

    boolean changed = !s.equals(trimmed);
    return Map.of("value", s, "changed", changed);
  }

  @McpTool(name = "validateIdentifier",
      description = "Validate an INTERLIS 2.4 identifier: ASCII letter first, then ASCII letters/digits/underscore, max 255 characters, and not a reserved word. Returns {valid:true} or throws an error.")
  public Map<String, Object> validateIdentifier(
      @McpToolParam(description = "Identifier to validate", required = true) String value) {
    var nv = NameValidator.ascii();
    nv.validateIdent(value, "Identifier");
    return Map.of("valid", true);
  }

  @McpTool(name = "validateFqn",
      description = "Validate a fully qualified INTERLIS name (dot-separated identifiers). Returns {valid:true} or throws an error.")
  public Map<String, Object> validateFqn(
      @McpToolParam(description = "FQN to validate", required = true) String fqn) {
    var nv = NameValidator.ascii();
    nv.validateFqn(fqn, "FQN");
    return Map.of("valid", true);
  }
}
