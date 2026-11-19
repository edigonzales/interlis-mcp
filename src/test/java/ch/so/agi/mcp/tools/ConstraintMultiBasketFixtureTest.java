package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.service.IliCompilerService;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

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

  private static final String ASSOCIATION_MODEL = """
      INTERLIS 2.4;
      MODEL BasketFixture (en) AT "https://example.org" VERSION "2026-08-19" =
        TOPIC Data =
          CLASS Item =
            code : MANDATORY TEXT*20;
            MANDATORY CONSTRAINT ValidCode: code == "A";
          END Item;
          CLASS Target =
            OID AS INTERLIS.UUIDOID;
          END Target;
          ASSOCIATION Relation %s =
            %s
            item -- {0..*} Item;
            target -- {0..*} Target;
          END Relation;
        END Data;
      END BasketFixture.
      """;

  @ParameterizedTest
  @CsvSource({"false,", "true,", "true, ac02bc31-50c3-4ddf-8c87-700448b29a8c"})
  void validatesStandaloneAssociationAndCountsItsTransferType(boolean identifiable, String suppliedOid) throws Exception {
    String model = ASSOCIATION_MODEL.formatted(
        identifiable ? "(OID)" : "", identifiable ? "OID AS INTERLIS.UUIDOID;" : "");
    var testCase = associationCase(suppliedOid);
    var result = tools.testIliConstraint(model, "ValidCode", List.of(testCase), true);
    assertThat(result).as(result.toString()).containsEntry("allPassed", true);
    var fixture = caseResult(result);
    assertThat(fixture).containsEntry("fixtureValid", true)
        .containsEntry("validatorValid", true)
        .containsEntry("constraintExercised", true)
        .containsEntry("baskets", List.of(Map.of("topic", "BasketFixture.Data", "basketId", "b1",
            "objectCount", 2L, "associationObjectCount", 1L)));

    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var xtf = factory.newDocumentBuilder().parse(new InputSource(new StringReader((String) fixture.get("xtfText"))));
    assertThat(xtf.getElementsByTagNameNS("*", "Item").getLength()).isEqualTo(1);
    assertThat(xtf.getElementsByTagNameNS("*", "Target").getLength()).isEqualTo(1);
    var relations = xtf.getElementsByTagNameNS("*", "Relation");
    assertThat(relations.getLength()).isEqualTo(1);
    var relation = (Element) relations.item(0);
    String namespace = relation.lookupNamespaceURI("ili");
    String tid = relation.getAttributeNS(namespace, "tid");
    if (suppliedOid != null) {
      assertThat(tid).isEqualTo(suppliedOid);
    } else if (identifiable) {
      assertThat(tid).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    } else {
      assertThat(tid).isEmpty();
    }
    for (var role : testCase.links.getFirst().roles.entrySet()) {
      var ref = (Element) relation.getElementsByTagNameNS("*", role.getKey()).item(0);
      assertThat(ref.getAttributeNS(namespace, "ref")).isEqualTo(role.getValue());
    }
  }

  @ParameterizedTest
  @CsvSource({
      "invalid-uuid, ASSOCIATION_OID_INVALID",
      "b0770cf2-9a57-442a-91eb-8ac231667e93, ASSOCIATION_OID_DUPLICATE"
  })
  void rejectsInvalidAssociationOidsBeforeWritingTransfer(String oid, String code) {
    String model = ASSOCIATION_MODEL.formatted("(OID)", "OID AS INTERLIS.UUIDOID;");
    var result = tools.testIliConstraint(model, "ValidCode", List.of(associationCase(oid)), true);
    assertThat(result).containsEntry("compilerValid", true).containsEntry("allPassed", false);
    assertThat(caseResult(result)).containsEntry("fixtureValid", false).containsEntry("constraintExercised", false)
        .containsEntry("fixturePreparationReasonCode", code).doesNotContainKey("xtfText");
  }

  @Test
  void generatesAnOidForMandatoryAssociationAndReusesExistingTarget() {
    String model = ASSOCIATION_MODEL.formatted("(OID)", "OID AS INTERLIS.UUIDOID;")
        .replace("target -- {0..*}", "target -- {1..*}");
    var testCase = associationCase(null);
    testCase.links = null;
    var result = tools.testIliConstraint(model, "ValidCode", List.of(testCase), true);
    assertThat(result).as(result.toString()).containsEntry("allPassed", true);
    assertThat(caseResult(result)).containsEntry("baskets", List.of(Map.of("topic", "BasketFixture.Data",
        "basketId", "b1", "objectCount", 2L, "associationObjectCount", 1L)));
    assertThat(caseResult(result).get("xtfText").toString()).contains("<BasketFixture:Relation ili:tid=");
  }

  private ConstraintTestTools.TestCase associationCase(String oid) {
    var target = new ConstraintTestTools.TestObject();
    target.classFqn = "BasketFixture.Data.Target";
    target.oid = "b0770cf2-9a57-442a-91eb-8ac231667e93";
    var testCase = testCase("standalone relation", true, object("item1", "A", null), target);
    var link = new ConstraintTestTools.TestLink();
    link.associationFqn = "BasketFixture.Data.Relation";
    link.oid = oid;
    link.roles = Map.of("item", "item1", "target", target.oid);
    testCase.links = List.of(link);
    return testCase;
  }

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
        List.of(testCase), true);

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
        List.of(testCase), true);

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
        List.of(testCase), true);

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
        List.of(testCase), true);

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
