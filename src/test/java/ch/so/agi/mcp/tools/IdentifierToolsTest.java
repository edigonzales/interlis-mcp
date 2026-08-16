package ch.so.agi.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class IdentifierToolsTest {

  private final IdentifierTools identifierTools = new IdentifierTools();

  @Test
  void sanitizeIdentifier_avoidsReservedWord() {
    Map<String, Object> result = identifierTools.sanitizeIdentifier("MODEL");

    assertEquals("MODEL_", result.get("value"));
    assertEquals(true, result.get("changed"));
    assertEquals(true, identifierTools.validateIdentifier((String) result.get("value")).get("valid"));
  }

  @Test
  void sanitizeIdentifier_replacesNonAsciiLetters() {
    Map<String, Object> result = identifierTools.sanitizeIdentifier("Äpfel");

    assertEquals("X_pfel", result.get("value"));
    assertEquals(true, result.get("changed"));
    assertEquals(true, identifierTools.validateIdentifier((String) result.get("value")).get("valid"));
  }

  @Test
  void sanitizeIdentifier_limitsLength() {
    Map<String, Object> result = identifierTools.sanitizeIdentifier("A".repeat(300));
    String value = (String) result.get("value");

    assertEquals(255, value.length());
    assertTrue((Boolean) result.get("changed"));
    assertEquals(true, identifierTools.validateIdentifier(value).get("valid"));
  }
}
