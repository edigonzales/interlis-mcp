package ch.so.agi.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FormattingToolsTest {

  private final FormattingTools formattingTools = new FormattingTools();

  @Test
  @DisplayName("formatIliModel pretty-prints INTERLIS source")
  void formatIliModelPrettyPrints() {
    String input = """
        INTERLIS 2.4;
        MODEL Foo (de) AT "https://example.org/foo" VERSION "2024-05-01" =
        TOPIC T=
        CLASS C=
        attr:TEXT;
        END C;
        END T;
        END Foo.
        """;

    String output = formattingTools.formatIliModel(input, null);

    String expected = """
        INTERLIS 2.4;

        MODEL Foo (de)
          AT "https://example.org/foo"
          VERSION "2024-05-01"
          =

          TOPIC T =

            CLASS C =
              attr : TEXT;
            END C;

          END T;

        END Foo.
        """.stripIndent();

    assertEquals(expected, output);
  }

  @Test
  @DisplayName("formatIliModel rejects blank input")
  void formatIliModelRejectsBlankInput() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        formattingTools.formatIliModel("   ", null)
    );

    assertTrue(ex.getMessage().contains("Model text is required."));
  }

  @Test
  @DisplayName("formatIliModel surfaces compiler errors")
  void formatIliModelReportsCompilerErrors() {
    String invalid = """
        INTERLIS 2.4;
        MODEL Foo (de) AT "https://example.org/foo" VERSION "2024-05-01" =
        END Foo
        """;

    IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
        formattingTools.formatIliModel(invalid, null)
    );

    assertTrue(ex.getMessage().contains("ili2c failed"));
  }
}
