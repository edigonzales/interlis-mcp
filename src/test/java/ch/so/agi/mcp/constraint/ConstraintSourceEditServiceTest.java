package ch.so.agi.mcp.constraint;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.service.IliCompilerService;
import org.junit.jupiter.api.Test;

class ConstraintSourceEditServiceTest {

  private static final String MODEL = """
      INTERLIS 2.4;

      MODEL SourceEdit (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Item =
            value : 0 .. 100; !! keep this comment
          END Item;
        END Data;
      END SourceEdit.
      """;

  @Test
  void insertsAtOwningTopicEndWithoutReformattingExistingSource() {
    IliCompilerService compiler = new IliCompilerService();
    IliCompilerService.CompilationResult compilation =
        compiler.compile(MODEL, null, "ili2c_constraint_source_edit_");
    assertThat(compilation.valid()).as(compilation.messages().toString()).isTrue();

    String block = "CONSTRAINTS OF SourceEdit.Data.Item =\n"
        + "  !!@ name = \"Check\"\n"
        + "  MANDATORY CONSTRAINT value >= 0;\n"
        + "END;";
    ConstraintSourceEditService.PreparedInsertion insertion =
        new ConstraintSourceEditService().insertConstraintBlock(
            MODEL,
            compilation,
            "SourceEdit.Data.Item",
            block);

    assertThat(insertion.updatedModelText())
        .contains("value : 0 .. 100; !! keep this comment")
        .contains("    CONSTRAINTS OF SourceEdit.Data.Item =\n"
            + "      !!@ name = \"Check\"\n"
            + "      MANDATORY CONSTRAINT value >= 0;\n"
            + "    END;\n\n"
            + "  END Data;");
    assertThat(insertion.sourceEdit().before()).isEmpty();
    assertThat(insertion.sourceEdit().description()).contains("SourceEdit.Data.Item");
  }
}
