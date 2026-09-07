package ch.so.agi.mcp.constraint;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.service.IliCompilerService;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    String fragment = "!!@ name = \"Check\"\nMANDATORY CONSTRAINT value >= 0;";
    ConstraintSourceEditService.PreparedInsertion insertion =
        new ConstraintSourceEditService().insertConstraint(
            MODEL,
            compilation,
            "SourceEdit.Data.Item",
            fragment);

    assertThat(insertion.updatedModelText())
        .contains("value : 0 .. 100; !! keep this comment")
        .contains("    CONSTRAINTS OF SourceEdit.Data.Item =\n"
            + "      !!@ name = \"Check\"\n"
            + "      MANDATORY CONSTRAINT value >= 0;\n"
            + "    END;\n\n"
            + "  END Data;");
    assertThat(insertion.sourceEdit().before()).isEmpty();
    assertThat(insertion.sourceEdit().description()).contains("SourceEdit.Data.Item");
    assertCompiles(compiler, insertion.updatedModelText());
    assertOnlyInsertions(MODEL, insertion);
  }

  private static final String VIEW_MODEL = """
      INTERLIS 2.4;
      MODEL SourceEdit (en) AT "https://example.org" VERSION "2026-09-07" =
        TOPIC Data =
          CLASS Item =
            value : MANDATORY 0 .. 100;
            code : MANDATORY TEXT*20;
          END Item;
        END Data;
        VIEW TOPIC Checks =
          DEPENDS ON Data;
          VIEW Items
            PROJECTION OF I ~ SourceEdit.Data.Item;
            WHERE I->value >= 0;
            WHERE I->value != 99;
            =
            ALL OF I;
            !! keep this comment: END Items;
            MANDATORY CONSTRAINT value <= 100;
          END Items;
        END Checks;
      END SourceEdit.
      """;

  @ParameterizedTest
  @ValueSource(strings = {"2.3", "2.4"})
  void insertsAllAffectedConstraintKindsInViewsWithoutChangingExistingBytes(String version) {
    List<String> constraints = List.of(
        "MANDATORY CONSTRAINT value >= 0;",
        "CONSTRAINT <= 50% value == 0;",
        "UNIQUE code, value;",
        "SET CONSTRAINT INTERLIS.objectCount(ALL) == 0;");
    IliCompilerService compiler = new IliCompilerService();
    for (String eol : List.of("\n", "\r\n")) {
      String source = VIEW_MODEL.replace("INTERLIS 2.4", "INTERLIS " + version)
          .replace("  ", "\t").replace("\n", eol);
      var before = assertCompiles(compiler, source);
      for (String constraint : constraints) {
        String fragment = "/** New rule. */\n!!@ name = \"AddedRule\"\n" + constraint;
        var insertion = new ConstraintSourceEditService().insertConstraint(
            source, before, "SourceEdit.Checks.Items", fragment);
        var after = assertCompiles(compiler, insertion.updatedModelText());
        assertThat(after.transferDescription().getElement("SourceEdit.Checks.Items.AddedRule"))
            .isNotNull();
        assertThat(insertion.updatedModelText())
            .contains("\t\t  " + constraint + eol + "\t\tEND Items;")
            .doesNotContain("CONSTRAINTS OF", "VIEW   ");
        if (eol.equals("\r\n")) {
          assertThat(insertion.updatedModelText().replace("\r\n", "")).doesNotContain("\n");
        }
        assertOnlyInsertions(source, insertion);
      }
    }
  }

  @Test
  void insertsBeforeEndEvenWhenItSharesALineWithViewContent() {
    String source = VIEW_MODEL.replace(
        "MANDATORY CONSTRAINT value <= 100;\n    END Items;",
        "MANDATORY CONSTRAINT value <= 100; END Items;");
    IliCompilerService compiler = new IliCompilerService();
    var insertion = new ConstraintSourceEditService().insertConstraint(
        source, assertCompiles(compiler, source), "SourceEdit.Checks.Items",
        "!!@ name = \"AddedRule\"\nMANDATORY CONSTRAINT value >= 0;");
    assertCompiles(compiler, insertion.updatedModelText());
    assertThat(insertion.updatedModelText()).contains(
        "value <= 100; \n      !!@ name = \"AddedRule\"");
    assertOnlyInsertions(source, insertion);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void importsIntoContractedModelWithoutCopyingModifiers(boolean inlineHeader) {
    String source = VIEW_MODEL.replace("MODEL SourceEdit", "CONTRACTED MODEL SourceEdit");
    if (inlineHeader) source = source.replace("=\n  TOPIC Data", "= TOPIC Data");
    IliCompilerService compiler = new IliCompilerService();
    var insertion = new ConstraintSourceEditService().insertConstraint(
        source, assertCompiles(compiler, source), "SourceEdit.Checks.Items",
        "MANDATORY CONSTRAINT Math_V2.abs(value) >= 0;", Set.of("Math_V2"));
    assertCompiles(compiler, insertion.updatedModelText());
    assertThat(insertion.sourceEdits()).hasSize(2);
    assertThat(insertion.updatedModelText())
        .contains("\n  IMPORTS Math_V2;\n")
        .doesNotContain("CONTRACTED   IMPORTS", "VIEW   ");
    assertOnlyInsertions(source, insertion);
  }

  private static IliCompilerService.CompilationResult assertCompiles(
      IliCompilerService compiler, String source) {
    var compiled = compiler.compile(source, null);
    assertThat(compiled.valid()).as(compiled.messages().toString()).isTrue();
    return compiled;
  }

  static void assertOnlyInsertions(String source, ConstraintSourceEditService.PreparedInsertion insertion) {
    StringBuilder replayed = new StringBuilder(source);
    insertion.sourceEdits().stream()
        .sorted(Comparator.comparingInt(ConstraintSourceEditService.SourceEdit::startOffset).reversed())
        .forEach(edit -> {
          assertThat(edit.startOffset()).isEqualTo(edit.endOffset());
          assertThat(edit.before()).isEmpty();
          replayed.insert(edit.startOffset(), edit.after());
        });
    assertThat(insertion.updatedModelText()).isEqualTo(replayed.toString());
  }
}
