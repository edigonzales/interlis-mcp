package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.service.IliCompilerService;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SetConstraintAuthoringToolsTest {

  private static final String MODEL = """
      INTERLIS 2.4;

      MODEL SetAuthor (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Item =
            value : MANDATORY 0..10;
          END Item;
        END Data;
      END SetAuthor.
      """;

  @Test
  void authorsSourcePreservingSetWhereAndVerifiesProofWithTwoCompiles() {
    CountingCompiler compiler = new CountingCompiler();
    ConstraintCaseGenerationTools cases = new ConstraintCaseGenerationTools(
        compiler,
        new ConstraintContextService(compiler));
    SetConstraintAuthoringTools tools = new SetConstraintAuthoringTools(compiler, cases);

    SetConstraintAuthoringTools.WhereSpec where = new SetConstraintAuthoringTools.WhereSpec();
    where.attribute = "value";
    where.operator = ">=";
    where.valueKind = "NUMERIC";
    where.value = 5;

    Map<String, Object> result = tools.authorIliSetConstraint(
        MODEL,
        "SetAuthor.Data.Item",
        "AtLeastTwoHigh",
        ">=",
        new BigDecimal("2"),
        false,
        where,
        null);

    assertThat(result.get("generated")).as("%s", result).isEqualTo(true);
    assertThat(result.get("proofVerified")).as("%s", result).isEqualTo(true);
    assertThat(result.get("operator")).isEqualTo(">=");
    assertThat(result.get("threshold")).isEqualTo("2");
    assertThat(result.get("perBasket")).isEqualTo(false);
    assertThat(result.get("whereExpression")).isEqualTo("value >= 5");
    assertThat(result.get("compileContract")).isEqualTo(
        "2_REAL_COMPILES_BEFORE_AND_AFTER; proof reuses compiled After context.");
    assertThat(result.get("updatedModelText").toString())
        .contains("CONSTRAINTS OF SetAuthor.Data.Item =")
        .contains("!!@ name = \"AtLeastTwoHigh\"")
        .contains("SET CONSTRAINT WHERE value >= 5:")
        .contains("INTERLIS.objectCount(ALL) >= 2;");
    assertThat(((Map<?, ?>) result.get("proof")).get("pattern"))
        .isEqualTo("SET_OBJECT_COUNT_PROOF");
    assertThat(compiler.calls).isEqualTo(2);
  }

  @Test
  void authorsBasketScopedSetAndRoundTripsScope() {
    IliCompilerService compiler = new IliCompilerService();
    ConstraintCaseGenerationTools cases = new ConstraintCaseGenerationTools(
        compiler,
        new ConstraintContextService(compiler));
    SetConstraintAuthoringTools tools = new SetConstraintAuthoringTools(compiler, cases);

    Map<String, Object> result = tools.authorIliSetConstraint(
        MODEL,
        "SetAuthor.Data.Item",
        "TwoPerBasket",
        ">=",
        new BigDecimal("2"),
        true,
        null,
        null);

    assertThat(result.get("generated")).as("%s", result).isEqualTo(true);
    assertThat(result.get("proofVerified")).as("%s", result).isEqualTo(true);
    assertThat(result.get("perBasket")).isEqualTo(true);
    assertThat(result.get("updatedModelText").toString())
        .contains("SET CONSTRAINT (BASKET)")
        .contains("INTERLIS.objectCount(ALL) >= 2;");
  }

  @Test
  void rejectsInvalidOperatorBeforeCompilation() {
    CountingCompiler compiler = new CountingCompiler();
    ConstraintCaseGenerationTools cases = new ConstraintCaseGenerationTools(
        compiler,
        new ConstraintContextService(compiler));
    SetConstraintAuthoringTools tools = new SetConstraintAuthoringTools(compiler, cases);

    Map<String, Object> result = tools.authorIliSetConstraint(
        MODEL,
        "SetAuthor.Data.Item",
        "BadSet",
        "~=",
        BigDecimal.ONE,
        false,
        null,
        null);

    assertThat(result.get("generated")).isEqualTo(false);
    assertThat(result.get("reasonCode")).isEqualTo("INVALID_AUTHORING_SPEC");
    assertThat(compiler.calls).isZero();
  }

  private static class CountingCompiler extends IliCompilerService {
    private int calls;

    @Override
    public CompilationResult compile(
        String modelText,
        String modelRepositories,
        String tempPrefix) {
      calls++;
      return super.compile(modelText, modelRepositories, tempPrefix);
    }
  }
}
