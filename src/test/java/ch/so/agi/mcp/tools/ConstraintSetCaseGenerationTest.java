package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintSetCaseGenerationTest {

  private static final String MODEL = """
      INTERLIS 2.4;

      MODEL SetProof (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Item =
            value : MANDATORY 0..10;
            SET CONSTRAINT GlobalAtLeastTwo:
              INTERLIS.objectCount(ALL) >= 2;
            SET CONSTRAINT (BASKET) BasketAtLeastTwo:
              INTERLIS.objectCount(ALL) >= 2;
            SET CONSTRAINT FilteredAtLeastTwo: WHERE value >= 5:
              INTERLIS.objectCount(ALL) >= 2;
          END Item;
        END Data;
      END SetProof.
      """;

  private static final String NAVIGATED_MODEL = """
      INTERLIS 2.4;

      MODEL SetPathProof (en) AT "https://example.org" VERSION "2026-08-25" =
        TOPIC Data =
          CLASS Detail =
            value : MANDATORY 0..10;
          END Detail;

          CLASS Main =
            code : MANDATORY 0..10;
          END Main;

          ASSOCIATION MainDetail =
            MainRole -- {1} Main;
            Secondary -- {0..3} Detail;
          END MainDetail;

          CONSTRAINTS OF Main =
            SET CONSTRAINT LinkedAtLeastTwo:
              INTERLIS.objectCount(Secondary) >= 2;
            SET CONSTRAINT PositiveCode:
              code > 0;
          END;
        END Data;
      END SetPathProof.
      """;

  private static final String POLYMORPHIC_MODEL = """
      INTERLIS 2.4;

      MODEL SetPolymorphicProof (en) AT "https://example.org" VERSION "2026-08-25" =
        TOPIC Data =
          CLASS Main =
          END Main;

          CLASS AbstractTarget (ABSTRACT) =
            value : MANDATORY 0..10;
          END AbstractTarget;

          CLASS TargetA EXTENDS AbstractTarget =
          END TargetA;

          CLASS TargetB EXTENDS AbstractTarget =
          END TargetB;

          ASSOCIATION MainTarget =
            MainRole -- {1} Main;
            TargetRole -- {0..*} AbstractTarget;
          END MainTarget;

          CONSTRAINTS OF Main =
            SET CONSTRAINT LinkedAtLeastOne:
              INTERLIS.objectCount(TargetRole) >= 1;
          END;
        END Data;
      END SetPolymorphicProof.
      """;

  @Test
  void provesGlobalObjectCountBoundaryAndCrossBasketScope() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        MODEL,
        "GlobalAtLeastTwo");

    assertThat(result.get("generationVerified")).as(result.toString()).isEqualTo(true);
    assertThat(result.get("pattern")).isEqualTo("SET_OBJECT_COUNT_PROOF");
    assertThat(result.get("coverageComplete")).isEqualTo(true);

    List<Map<String, Object>> cases = generatedCases(result);
    assertThat(cases).anySatisfy(candidate -> {
      assertThat(candidate.get("boundary")).isEqualTo("INVALID_BOUNDARY");
      assertThat(candidate.get("selectedCount")).isEqualTo(1);
      assertThat(candidate.get("expectedConstraintValid")).isEqualTo(false);
    });
    assertThat(cases).anySatisfy(candidate -> {
      assertThat(candidate.get("boundary")).isEqualTo("VALID_BOUNDARY");
      assertThat(candidate.get("selectedCount")).isEqualTo(2);
      assertThat(candidate.get("expectedConstraintValid")).isEqualTo(true);
    });
    assertThat(cases).anySatisfy(candidate -> {
      assertThat(candidate.get("boundary")).isEqualTo("BASKET_SCOPE");
      assertThat(candidate.get("basketASelectedCount")).isEqualTo(1);
      assertThat(candidate.get("basketBSelectedCount")).isEqualTo(1);
      assertThat(candidate.get("globalConstraintValid")).isEqualTo(true);
      assertThat(candidate.get("perBasketConstraintValid")).isEqualTo(false);
      assertThat(candidate.get("expectedConstraintValid")).isEqualTo(true);
    });
    assertThat(((Map<?, ?>) result.get("verification")).get("allPassed")).isEqualTo(true);
  }

  @Test
  void basketScopedConstraintFailsWhenEachBasketIsBelowThreshold() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        MODEL,
        "BasketAtLeastTwo");

    assertThat(result.get("generationVerified")).as(result.toString()).isEqualTo(true);
    Map<String, Object> scope = generatedCases(result).stream()
        .filter(candidate -> "BASKET_SCOPE".equals(candidate.get("boundary")))
        .findFirst()
        .orElseThrow();
    assertThat(scope.get("declaredPerBasket")).isEqualTo(true);
    assertThat(scope.get("globalConstraintValid")).isEqualTo(true);
    assertThat(scope.get("perBasketConstraintValid")).isEqualTo(false);
    assertThat(scope.get("expectedConstraintValid")).isEqualTo(false);
  }

  @Test
  void whereExcludesObjectsFromAllBeforeObjectCount() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        MODEL,
        "FilteredAtLeastTwo");

    assertThat(result.get("generationVerified")).as(result.toString()).isEqualTo(true);
    assertThat(result.get("coverageComplete")).isEqualTo(true);
    assertThat(generatedCases(result)).anySatisfy(candidate -> {
      assertThat(candidate.get("boundary")).isEqualTo("VALID_BOUNDARY");
      assertThat(candidate.get("selectedCount")).isEqualTo(2);
      assertThat(candidate.get("excludedByWhereCount")).isEqualTo(1);
      assertThat(candidate.get("whereIncludedValues")).isEqualTo(Map.of("value", "5"));
      assertThat(candidate.get("whereExcludedValues")).isEqualTo(Map.of("value", "4"));
    });
  }

  @Test
  void setGenerationCompilesModelExactlyOnce() {
    CountingCompiler compiler = new CountingCompiler();
    Map<String, Object> result = tools(compiler).generateIliConstraintCases(
        MODEL,
        "GlobalAtLeastTwo");

    assertThat(result.get("generationVerified")).as(result.toString()).isEqualTo(true);
    assertThat(compiler.calls).isEqualTo(1);
  }

  @Test
  void provesNavigatedObjectCountWithMaterializedAssociationLinks() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        NAVIGATED_MODEL,
        "LinkedAtLeastTwo");

    assertThat(result.get("generationVerified")).as(result.toString()).isEqualTo(true);
    assertThat(result.get("coverageComplete")).isEqualTo(true);
    assertThat(generatedCases(result)).anySatisfy(candidate -> {
      assertThat(candidate.get("boundary")).isEqualTo("INVALID_BOUNDARY");
      assertThat(candidate.get("selectedCount")).isEqualTo(1);
      assertThat(candidate.get("associationLinkCount")).isEqualTo(1);
      assertThat(candidate.get("expectedConstraintValid")).isEqualTo(false);
    }).anySatisfy(candidate -> {
      assertThat(candidate.get("boundary")).isEqualTo("VALID_BOUNDARY");
      assertThat(candidate.get("selectedCount")).isEqualTo(2);
      assertThat(candidate.get("associationLinkCount")).isEqualTo(2);
      assertThat(candidate.get("expectedConstraintValid")).isEqualTo(true);
    });
  }

  @Test
  void provesBooleanSetExpressionWithSharedExpressionPipeline() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        NAVIGATED_MODEL,
        "PositiveCode");

    assertThat(result.get("generationVerified")).as(result.toString()).isEqualTo(true);
    assertThat(result.get("coverageComplete")).isEqualTo(true);
    assertThat(generatedCases(result))
        .anySatisfy(candidate -> assertThat(candidate.get("expectedConstraintValid")).isEqualTo(true))
        .anySatisfy(candidate -> assertThat(candidate.get("expectedConstraintValid")).isEqualTo(false));
  }

  @Test
  void provesEveryConcretePolymorphicNavigationRoute() {
    Map<String, Object> result = tools(new IliCompilerService()).generateIliConstraintCases(
        POLYMORPHIC_MODEL,
        "LinkedAtLeastOne");

    assertThat(result.get("generationVerified")).as(result.toString()).isEqualTo(true);
    assertThat(result.get("coverageComplete")).isEqualTo(true);
    assertThat(generatedCases(result))
        .extracting(candidate -> candidate.get("routeTargetFqn"))
        .contains("SetPolymorphicProof.Data.TargetA", "SetPolymorphicProof.Data.TargetB");
    assertThat(generatedCases(result)).filteredOn(candidate ->
        Boolean.TRUE.equals(candidate.get("expectedConstraintValid")))
        .hasSize(2);
  }

  private ConstraintCaseGenerationTools tools(IliCompilerService compiler) {
    return new ConstraintCaseGenerationTools(
        new ch.so.agi.mcp.constraint.ConstraintContextService(compiler),
        new ConstraintTestTools(compiler));
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> generatedCases(Map<String, Object> result) {
    return (List<Map<String, Object>>) result.get("generatedCases");
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
