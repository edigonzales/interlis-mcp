package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintExistenceSpecialCaseGenerationTest {

  private static final String STRUCTURE_MODEL = """
      INTERLIS 2.4;

      MODEL ExistenceStructureProof (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          STRUCTURE Key =
            code : TEXT*10;
            number : 0..100;
          END Key;

          CLASS Target =
            key : Key;
          END Target;

          CLASS Source =
            key : Key;

            !!@ name = "StructureExists"
            EXISTENCE CONSTRAINT key REQUIRED IN Target : key;
          END Source;
        END Data;
      END ExistenceStructureProof.
      """;

  private static final String REFERENCE_MODEL = """
      INTERLIS 2.4;

      MODEL ExistenceReferenceProof (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Entity =
          END Entity;

          CLASS Target =
            ref : REFERENCE TO Entity;
          END Target;

          CLASS Source =
            ref : REFERENCE TO Entity;

            !!@ name = "ReferenceExists"
            EXISTENCE CONSTRAINT ref REQUIRED IN Target : ref;
          END Source;
        END Data;
      END ExistenceReferenceProof.
      """;

  private static final String COORD_MODEL = """
      INTERLIS 2.4;

      MODEL ExistenceCoordProof (en) AT "https://example.org" VERSION "2026-08-19" =
        DOMAIN
          Coord2 = COORD
            2600000.0 .. 2600100.0,
            1200000.0 .. 1200100.0,
            ROTATION 2 -> 1;

        TOPIC Data =
          CLASS Target =
            position : Coord2;
          END Target;

          CLASS Source =
            position : Coord2;

            !!@ name = "CoordExists"
            EXISTENCE CONSTRAINT position REQUIRED IN Target : position;
          END Source;
        END Data;
      END ExistenceCoordProof.
      """;

  @Test
  void structureExistenceGeneratesMemberWiseVerifiedProof() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        STRUCTURE_MODEL,
        "StructureExists");

    assertThat(result.get("automaticCasesGenerated")).isEqualTo(true);
    assertThat(result.get("automaticCasesAvailable")).isEqualTo(true);
    assertThat(result.get("generationVerified")).isEqualTo(true);
    assertThat(result.get("coverageComplete")).isEqualTo(true);
    assertThat(result.get("pattern")).isEqualTo("EXISTENCE_SEMANTIC_PROOF");
    assertThat(maps(result.get("generatedCases")))
        .anySatisfy(item -> assertCase(
            item,
            "defined structure missing from all REQUIRED IN targets",
            false))
        .anySatisfy(item -> assertCase(
            item,
            "equal structure exists in REQUIRED IN target 1",
            true))
        .anySatisfy(item -> assertThat(item.get("name").toString())
            .contains("structure target differs in comparable member"))
        .anySatisfy(item -> assertCase(
            item,
            "undefined optional structure",
            true));
    assertThat(map(result.get("verification")).get("allPassed")).isEqualTo(true);
  }

  @Test
  void structureProofStillUsesExactlyOneRealCompile() {
    CountingCompiler compiler = new CountingCompiler();

    Map<String, Object> result = tools(compiler).generateIliConstraintCases(
        STRUCTURE_MODEL,
        "StructureExists");

    assertThat(result.get("generationVerified")).isEqualTo(true);
    assertThat(compiler.calls).isEqualTo(1);
  }

  @Test
  void referenceExistenceIsRejectedByExplicitSafetyGate() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        REFERENCE_MODEL,
        "ReferenceExists");

    assertThat(result.get("automaticCasesGenerated")).isEqualTo(false);
    assertThat(result.get("automaticCasesAvailable")).isEqualTo(false);
    assertThat(result.get("generationVerified")).isEqualTo(false);
    assertThat(result.get("reasonCode")).isEqualTo("EXISTENCE_REFERENCE_VALUE_PROOF_UNSAFE");
    assertThat(result.get("reason").toString()).contains("not value-discriminating");
  }

  @Test
  void coordExistenceReportsFixtureBoundaryInsteadOfPretendingScalarSemantics() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        COORD_MODEL,
        "CoordExists");

    assertThat(result.get("automaticCasesGenerated")).isEqualTo(false);
    assertThat(result.get("reasonCode")).isEqualTo("EXISTENCE_COORD_FIXTURE_NOT_VALUE_AWARE");
    assertThat(result.get("reason").toString()).contains("coordinate values");
  }

  private ConstraintCaseGenerationTools tools(IliCompilerService compiler) {
    return new ConstraintCaseGenerationTools(
        new ConstraintContextService(compiler),
        new ConstraintTestTools(compiler));
  }

  private void assertCase(Map<String, Object> item, String name, boolean expected) {
    assertThat(item.get("name")).isEqualTo(name);
    assertThat(item.get("expectedConstraintValid")).isEqualTo(expected);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> maps(Object value) {
    return (List<Map<String, Object>>) value;
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
