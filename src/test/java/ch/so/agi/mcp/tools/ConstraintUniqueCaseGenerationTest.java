package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintUniqueCaseGenerationTest {

  private static final String MODEL = """
      INTERLIS 2.4;

      MODEL UniqueProof (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          STRUCTURE Part =
            code : TEXT*20;
          END Part;

          CLASS Item =
            key : MANDATORY TEXT*20;
            secondKey : MANDATORY TEXT*20;
            optionalKey : TEXT*20;
            leftValue : MANDATORY 0..10;
            rightValue : MANDATORY 0..10;
            parts : BAG {0..*} OF Part;

            !!@ name = "GlobalKey"
            UNIQUE key;

            !!@ name = "CompositeKey"
            UNIQUE key, secondKey;

            !!@ name = "BasketKey"
            UNIQUE (BASKET) key;

            !!@ name = "FilteredKey"
            UNIQUE WHERE leftValue > rightValue : key;

            !!@ name = "OptionalKey"
            UNIQUE optionalKey;

            !!@ name = "LocalPartCode"
            UNIQUE (LOCAL) parts : code;
          END Item;
        END Data;
      END UniqueProof.
      """;

  @Test
  void globalUniqueGeneratesVerifiedSameBasketAndCrossBasketCounterexamples() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        MODEL,
        "GlobalKey");

    assertVerifiedUnique(result);
    assertThat(result.get("coverageComplete")).isEqualTo(true);
    List<Map<String, Object>> generated = maps(result.get("generatedCases"));
    assertThat(generated)
        .anySatisfy(item -> assertCase(item, "duplicate UNIQUE key in one basket", false))
        .anySatisfy(item -> assertCase(item, "global duplicate UNIQUE key across baskets", false));
    Map<String, Object> verification = map(result.get("verification"));
    assertThat(verification.get("allPassed")).isEqualTo(true);
    assertThat(maps(verification.get("cases")))
        .anySatisfy(item -> {
          assertThat(item.get("name")).isEqualTo("global duplicate UNIQUE key across baskets");
          assertThat(item.get("basketCount")).isEqualTo(2);
          assertThat(item.get("actualConstraintValid")).isEqualTo(false);
        });
  }

  @Test
  void compositeUniqueDuplicatesAllKeyComponentsTogether() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        MODEL,
        "CompositeKey");

    assertVerifiedUnique(result);
    assertThat(result.get("coverageComplete")).isEqualTo(true);
    assertThat(maps(result.get("generatedCases")))
        .anySatisfy(item -> {
          assertCase(item, "duplicate UNIQUE key in one basket", false);
          Map<String, Object> values = map(item.get("values"));
          assertThat(values).containsKeys("key", "secondKey");
        });
  }

  @Test
  void basketUniqueAllowsSameKeyAcrossBasketsButRejectsSameBasketDuplicate() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        MODEL,
        "BasketKey");

    assertVerifiedUnique(result);
    List<Map<String, Object>> generated = maps(result.get("generatedCases"));
    assertThat(generated)
        .anySatisfy(item -> assertCase(item, "duplicate UNIQUE key in one basket", false))
        .anySatisfy(item -> assertCase(item, "same UNIQUE key in different baskets", true));
    assertThat(maps(map(result.get("verification")).get("cases")))
        .anySatisfy(item -> {
          assertThat(item.get("name")).isEqualTo("same UNIQUE key in different baskets");
          assertThat(item.get("basketCount")).isEqualTo(2);
          assertThat(item.get("actualConstraintValid")).isEqualTo(true);
        });
  }

  @Test
  void whereUniqueExercisesParticipatingAndExcludedBranches() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        MODEL,
        "FilteredKey");

    assertVerifiedUnique(result);
    List<Map<String, Object>> generated = maps(result.get("generatedCases"));
    assertThat(generated)
        .anySatisfy(item -> assertCase(item, "duplicate UNIQUE key in one basket", false))
        .anySatisfy(item -> assertThat(item.get("name").toString()).contains("WHERE"));
    assertThat(map(result.get("verification")).get("allPassed")).isEqualTo(true);
  }

  @Test
  void localUniqueProvesParentScopeAndDuplicateCounterexample() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        MODEL,
        "LocalPartCode");

    assertVerifiedUnique(result);
    assertThat(result.get("coverageComplete")).isEqualTo(true);
    List<Map<String, Object>> generated = maps(result.get("generatedCases"));
    assertThat(generated)
        .anySatisfy(item -> assertCase(item, "duplicate LOCAL key in one parent", false))
        .anySatisfy(item -> assertCase(item, "same LOCAL key in different parents", true));
  }

  @Test
  void optionalUniqueKeyProducesVerifiedUndefinedWitness() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        MODEL,
        "OptionalKey");

    assertVerifiedUnique(result);
    assertThat(maps(result.get("generatedCases")))
        .anySatisfy(item -> {
          assertThat(item.get("name").toString()).contains("undefined UNIQUE key component");
          assertThat(item.get("expectedConstraintValid")).isEqualTo(true);
        });
  }

  @Test
  void uniqueGenerationUsesExactlyOneRealCompilerRun() {
    CountingCompiler compiler = new CountingCompiler();

    Map<String, Object> result = tools(compiler).generateIliConstraintCases(
        MODEL,
        "BasketKey");

    assertThat(result.get("generationVerified")).isEqualTo(true);
    assertThat(compiler.calls).isEqualTo(1);
  }

  private ConstraintCaseGenerationTools tools(IliCompilerService compiler) {
    return new ConstraintCaseGenerationTools(
        new ConstraintContextService(compiler),
        new ConstraintTestTools(compiler));
  }

  private void assertVerifiedUnique(Map<String, Object> result) {
    assertThat(result.get("automaticCasesGenerated")).isEqualTo(true);
    assertThat(result.get("automaticCasesAvailable")).isEqualTo(true);
    assertThat(result.get("generationVerified")).isEqualTo(true);
    assertThat(result.get("pattern")).isEqualTo("UNIQUE_SEMANTIC_PROOF");
    assertThat(map(result.get("constraint")).get("kind")).isEqualTo("UNIQUENESS_CONSTRAINT");
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
