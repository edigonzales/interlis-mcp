package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintMultiBasketFixtureTest {

  private static final String MODEL = """
      INTERLIS 2.4;

      MODEL BasketFixture (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Item =
            code : MANDATORY TEXT*20;
            UNIQUE (BASKET) UniqueCodePerBasket: code;
            UNIQUE UniqueCodeGlobal: code;
          END Item;
        END Data;
      END BasketFixture.
      """;

  private static final String ITEM = "BasketFixture.Data.Item";

  private final ConstraintTestTools tools = new ConstraintTestTools(new IliCompilerService());

  @Test
  void perBasketUniqueRejectsDuplicateInsideOneBasket() {
    ConstraintTestTools.TestCase testCase = testCase(
        "duplicate in one basket",
        false,
        object("one", "SAME", "basketA"),
        object("two", "SAME", "basketA"));

    Map<String, Object> result = tools.testIliConstraint(
        MODEL,
        "UniqueCodePerBasket",
        List.of(testCase));

    assertThat(result.get("allPassed")).isEqualTo(true);
    Map<String, Object> caseResult = caseResult(result);
    assertThat(caseResult.get("basketCount")).isEqualTo(1);
    assertThat(caseResult.get("actualConstraintValid")).isEqualTo(false);
    assertThat(((Number) caseResult.get("targetViolationCount")).intValue()).isGreaterThan(0);
    assertThat(caseResult.get("xtfText").toString()).contains("ili:bid=\"basketA\"");
  }

  @Test
  void perBasketUniqueAllowsSameValueInDifferentBaskets() {
    ConstraintTestTools.TestCase testCase = testCase(
        "duplicate across baskets",
        true,
        object("one", "SAME", "basketA"),
        object("two", "SAME", "basketB"));

    Map<String, Object> result = tools.testIliConstraint(
        MODEL,
        "UniqueCodePerBasket",
        List.of(testCase));

    assertThat(result.get("allPassed")).isEqualTo(true);
    Map<String, Object> caseResult = caseResult(result);
    assertThat(caseResult.get("basketCount")).isEqualTo(2);
    assertThat(caseResult.get("actualConstraintValid")).isEqualTo(true);
    assertThat(caseResult.get("xtfText").toString())
        .contains("ili:bid=\"basketA\"")
        .contains("ili:bid=\"basketB\"");
    assertThat(caseResult.get("baskets")).asList().hasSize(2);
  }

  @Test
  void globalUniqueStillSeesDuplicateAcrossBaskets() {
    ConstraintTestTools.TestCase testCase = testCase(
        "global duplicate across baskets",
        false,
        object("one", "SAME", "basketA"),
        object("two", "SAME", "basketB"));

    Map<String, Object> result = tools.testIliConstraint(
        MODEL,
        "UniqueCodeGlobal",
        List.of(testCase));

    assertThat(result.get("allPassed")).isEqualTo(true);
    Map<String, Object> caseResult = caseResult(result);
    assertThat(caseResult.get("basketCount")).isEqualTo(2);
    assertThat(caseResult.get("actualConstraintValid")).isEqualTo(false);
  }

  @Test
  void omittedBasketIdKeepsSingleImplicitBasketBehavior() {
    ConstraintTestTools.TestObject first = object("one", "A", null);
    ConstraintTestTools.TestObject second = object("two", "B", null);
    ConstraintTestTools.TestCase testCase = testCase(
        "implicit basket",
        true,
        first,
        second);

    Map<String, Object> result = tools.testIliConstraint(
        MODEL,
        "UniqueCodePerBasket",
        List.of(testCase));

    assertThat(result.get("allPassed")).isEqualTo(true);
    Map<String, Object> caseResult = caseResult(result);
    assertThat(caseResult.get("basketCount")).isEqualTo(1);
    assertThat(caseResult.get("xtfText").toString()).contains("ili:bid=\"b1\"");
  }

  private ConstraintTestTools.TestCase testCase(
      String name,
      boolean expected,
      ConstraintTestTools.TestObject... objects) {
    ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
    testCase.name = name;
    testCase.expectedConstraintValid = expected;
    testCase.objects = List.of(objects);
    testCase.links = List.of();
    return testCase;
  }

  private ConstraintTestTools.TestObject object(
      String oid,
      String code,
      String basketId) {
    ConstraintTestTools.TestObject object = new ConstraintTestTools.TestObject();
    object.classFqn = ITEM;
    object.oid = oid;
    object.basketId = basketId;
    object.values = Map.of("code", code);
    return object;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> caseResult(Map<String, Object> result) {
    return ((List<Map<String, Object>>) result.get("cases")).getFirst();
  }
}
