package ch.so.agi.mcp.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NameValidatorTest {

  @Test
  void asciiValidator_acceptsSimpleIdentifier() {
    NameValidator.ascii().validateIdent("Model1", "Model name");
  }

  @Test
  void asciiValidator_rejectsInvalidIdentifier() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> NameValidator.ascii().validateIdent("1Model", "Model name"));
    assertTrue(ex.getMessage().contains("Model name"));
  }

  @Test
  void asciiValidator_rejectsReservedWord() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> NameValidator.ascii().validateIdent("MODEL", "Model name"));

    assertTrue(ex.getMessage().contains("reserved word"));
    assertThrows(
        IllegalArgumentException.class,
        () -> NameValidator.ascii().validateIdent("INTERLIS", "Model name"));
    assertDoesNotThrow(() -> NameValidator.ascii().validateIdent("Model", "Model name"));
  }

  @Test
  void asciiValidator_rejectsTooLongIdentifier() {
    String value = "A".repeat(256);

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> NameValidator.ascii().validateIdent(value, "Model name"));

    assertTrue(ex.getMessage().contains("255"));
  }

  @Test
  void validateFqn_acceptsCompoundName() {
    NameValidator.ascii().validateFqn("Model.Topic.Class", "Class FQN");
  }

  @Test
  void validateFqn_acceptsPredefinedInterlisReference() {
    assertDoesNotThrow(() -> NameValidator.ascii().validateFqn("INTERLIS.m", "Unit FQN"));
  }

  @Test
  void validateFqn_rejectsEmptySegment() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> NameValidator.ascii().validateFqn("Model..Class", "Class FQN"));
    assertTrue(ex.getMessage().contains("Class FQN"));
  }

  @Test
  void validateFqn_rejectsTrailingEmptySegment() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> NameValidator.ascii().validateFqn("Model.Topic.", "Class FQN"));
    assertTrue(ex.getMessage().contains("Class FQN"));
  }

  @Test
  void validateFqn_rejectsReservedSegment() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> NameValidator.ascii().validateFqn("Demo.MODEL.Class", "Class FQN"));

    assertTrue(ex.getMessage().contains("reserved word"));
  }
}
