package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.RenameElementKind;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RenameToolsTest {

  private final RenameTools renameTools = new RenameTools(new IliCompilerService());

  @Test
  void renameModelElement_renamesClassAndUpdatesReferences_withoutExpectedKind() {
    Map<String, Object> result = renameTools.renameModelElement(
        """
            INTERLIS 2.4;

            MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-01" =
              TOPIC Topic =
                CLASS Target =
                  code : TEXT*20;
                END Target;
                CLASS Holder =
                  ref : REFERENCE TO Target;
                END Holder;
              END Topic;
            END Demo.
            """,
        "Demo.Topic.Target",
        null,
        "TargetRenamed");

    String updatedModelText = result.get("updatedModelText").toString();
    assertTrue(updatedModelText.contains("CLASS TargetRenamed ="));
    assertTrue(updatedModelText.contains("ref : REFERENCE TO Demo.Topic.TargetRenamed;"));
    assertEquals("Demo.Topic.TargetRenamed", result.get("newElementFqn"));
    assertEquals("CLASS_OR_STRUCTURE", result.get("expectedKind"));
  }

  @Test
  void renameModelElement_renamesAttributeAndUpdatesConstraint_withExpectedKind() {
    Map<String, Object> result = renameTools.renameModelElement(
        """
            INTERLIS 2.4;

            MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-01" =
              TOPIC Topic =
                CLASS Target =
                  code : TEXT*20;
                  MANDATORY CONSTRAINT code <> "";
                END Target;
              END Topic;
            END Demo.
            """,
        "Demo.Topic.Target.code",
        RenameElementKind.ATTRIBUTE,
        "businessCode");

    String updatedModelText = result.get("updatedModelText").toString();
    assertTrue(updatedModelText.contains("businessCode : TEXT*20;"));
    assertTrue(updatedModelText.contains("MANDATORY CONSTRAINT businessCode <> \"\";"));
    assertEquals("Demo.Topic.Target.businessCode", result.get("newElementFqn"));
    assertEquals("ATTRIBUTE", result.get("expectedKind"));
  }

  @Test
  void renameModelElement_rejectsUnknownElement() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> renameTools.renameModelElement(
            minimalModel(),
            "Demo.Topic.DoesNotExist",
            null,
            "Renamed"));

    assertTrue(ex.getMessage().contains("Element not found"));
  }

  @Test
  void renameModelElement_rejectsWrongExpectedKind() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> renameTools.renameModelElement(
            minimalModel(),
            "Demo.Topic.Target",
            RenameElementKind.ATTRIBUTE,
            "Renamed"));

    assertTrue(ex.getMessage().contains("not of expected kind ATTRIBUTE"));
  }

  @Test
  void renameModelElement_rejectsNameCollision() {
    RuntimeException ex = assertThrows(
        RuntimeException.class,
        () -> renameTools.renameModelElement(
            """
                INTERLIS 2.4;

                MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-01" =
                  TOPIC Topic =
                    CLASS Target =
                      code : TEXT*20;
                      other : TEXT*20;
                    END Target;
                  END Topic;
                END Demo.
                """,
            "Demo.Topic.Target.code",
            RenameElementKind.ATTRIBUTE,
            "other"));

    assertFalse(ex.getMessage().isBlank());
  }

  private String minimalModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-01" =
          TOPIC Topic =
            CLASS Target =
            END Target;
          END Topic;
        END Demo.
        """;
  }
}
