package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintExistenceCaseGenerationTest {

  private static final String MODEL = """
      INTERLIS 2.4;

      MODEL ExistenceProof (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          CLASS TargetA =
            code : 0..10;
            textCode : TEXT*20;
          END TargetA;

          CLASS TargetB =
            code : 0..10;
          END TargetB;

          CLASS Source =
            mandatoryCode : MANDATORY 0..10;
            optionalCode : 0..10;
            textCode : TEXT*20;

            !!@ name = "CodeExists"
            EXISTENCE CONSTRAINT mandatoryCode REQUIRED IN TargetA : code;

            !!@ name = "CodeExistsOr"
            EXISTENCE CONSTRAINT mandatoryCode REQUIRED IN TargetA : code OR TargetB : code;

            !!@ name = "OptionalExists"
            EXISTENCE CONSTRAINT optionalCode REQUIRED IN TargetA : code;

            !!@ name = "TextExists"
            EXISTENCE CONSTRAINT textCode REQUIRED IN TargetA : textCode;
          END Source;
        END Data;
      END ExistenceProof.
      """;

  @Test
  void scalarExistenceGeneratesVerifiedWitnessAndCounterexamples() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        MODEL,
        "CodeExists",
        null);

    assertVerifiedExistence(result);
    assertThat(result.get("coverageComplete")).isEqualTo(true);
    assertThat(maps(result.get("generatedCases")))
        .anySatisfy(item -> assertCase(
            item,
            "defined value missing from all REQUIRED IN targets",
            false))
        .anySatisfy(item -> assertCase(
            item,
            "value exists in REQUIRED IN target 1",
            true))
        .anySatisfy(item -> assertCase(
            item,
            "REQUIRED IN target contains only a different value",
            false));
  }

  @Test
  void existenceOrGeneratesWitnessForEveryRequiredInAlternative() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        MODEL,
        "CodeExistsOr",
        null);

    assertVerifiedExistence(result);
    assertThat(maps(result.get("generatedCases")))
        .anySatisfy(item -> assertCase(item, "value exists in REQUIRED IN target 1", true))
        .anySatisfy(item -> assertCase(item, "value exists in REQUIRED IN target 2", true));
  }

  @Test
  void optionalUndefinedRestrictedValueIsVerifiedAsValid() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        MODEL,
        "OptionalExists",
        null);

    assertVerifiedExistence(result);
    assertThat(maps(result.get("generatedCases")))
        .anySatisfy(item -> assertCase(item, "undefined restricted value", true));
  }

  @Test
  void textExistenceUsesScalarTextDomain() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        MODEL,
        "TextExists",
        null);

    assertVerifiedExistence(result);
    assertThat(maps(result.get("generatedCases")))
        .anySatisfy(item -> assertCase(item, "value exists in REQUIRED IN target 1", true));
  }

  @Test
  void existenceGenerationUsesExactlyOneRealCompilerRun() {
    CountingCompiler compiler = new CountingCompiler();

    Map<String, Object> result = tools(compiler).generateIliConstraintCases(
        MODEL,
        "CodeExistsOr",
        null);

    assertThat(result.get("generationVerified")).isEqualTo(true);
    assertThat(compiler.calls).isEqualTo(1);
  }

  private ConstraintCaseGenerationTools tools(IliCompilerService compiler) {
    return new ConstraintCaseGenerationTools(
        compiler,
        new ConstraintContextService(compiler));
  }

  private void assertVerifiedExistence(Map<String, Object> result) {
    assertThat(result.get("automaticCasesGenerated")).isEqualTo(true);
    assertThat(result.get("automaticCasesAvailable")).isEqualTo(true);
    assertThat(result.get("generationVerified")).isEqualTo(true);
    assertThat(result.get("pattern")).isEqualTo("EXISTENCE_SEMANTIC_PROOF");
    assertThat(map(result.get("constraint")).get("kind")).isEqualTo("EXISTENCE_CONSTRAINT");
    assertThat(map(result.get("verification")).get("allPassed")).isEqualTo(true);
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
