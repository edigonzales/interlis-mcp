package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.service.IliCompilerService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConstraintTestToolsTest {

  private static final String AFU_GOLDEN_MODEL = """
      INTERLIS 2.3;

      CONTRACTED TYPE MODEL Math (en) AT "http://www.interlis.ch/models"
      VERSION "2018-11-19" =
        FUNCTION add(a: NUMERIC; b: NUMERIC): NUMERIC;
        FUNCTION sum(attributePath: TEXT): NUMERIC;
      END Math.

      MODEL SO_AFU_Bodeneinheiten_20251210 (de)
      AT "http://geo.so.ch/models/AFU"
      VERSION "2025-08-21" =
        IMPORTS Math;

        TOPIC Bodeneinheiten =
          CLASS Auspraegung =
            Gewichtung : MANDATORY 0 .. 100;
          END Auspraegung;

          CLASS BodeneinheitHauptauspraegung_Wald
          EXTENDS Auspraegung =
          END BodeneinheitHauptauspraegung_Wald;

          CLASS Nebenauspraegung_Wald
          EXTENDS Auspraegung =
          END Nebenauspraegung_Wald;

          ASSOCIATION Bodeneinheit_Nebenauspraegungen_Wald =
            Bodeneinheit -- {1} BodeneinheitHauptauspraegung_Wald;
            Nebenauspraegung -- {0..3} Nebenauspraegung_Wald;
          END Bodeneinheit_Nebenauspraegungen_Wald;

          CONSTRAINTS OF SO_AFU_Bodeneinheiten_20251210.Bodeneinheiten.BodeneinheitHauptauspraegung_Wald =
            !!@ name = "GewichtungSumme100_Wald"
            !!@ ilivalid.msg = "Die summierten Gewichtungen (Haupt + Neben) ergeben nicht 100% (Wald)."
            MANDATORY CONSTRAINT
              (
                DEFINED(Math.sum("Nebenauspraegung->Gewichtung"))
                AND Math.add(Math.sum("Nebenauspraegung->Gewichtung"), Gewichtung) == 100
              )
              OR
              (
                Gewichtung == 100
                AND NOT(DEFINED(Math.sum("Nebenauspraegung->Gewichtung")))
              );
          END;
        END Bodeneinheiten;
      END SO_AFU_Bodeneinheiten_20251210.
      """;

  private static final String MANDATORY_BOOLEAN_MODEL = """
      INTERLIS 2.3;

      MODEL ConstraintFixtureModel (en)
      AT "https://example.org"
      VERSION "2026-08-18" =
        TOPIC Data =
          CLASS Item =
            value : MANDATORY 0 .. 100;
            enabled : MANDATORY BOOLEAN;
            !!@ name = "ValueAtLeast10"
            MANDATORY CONSTRAINT value >= 10;
          END Item;
        END Data;
      END ConstraintFixtureModel.
      """;

  private static final String MAIN =
      "SO_AFU_Bodeneinheiten_20251210.Bodeneinheiten.BodeneinheitHauptauspraegung_Wald";
  private static final String SECONDARY =
      "SO_AFU_Bodeneinheiten_20251210.Bodeneinheiten.Nebenauspraegung_Wald";
  private static final String ASSOCIATION =
      "SO_AFU_Bodeneinheiten_20251210.Bodeneinheiten.Bodeneinheit_Nebenauspraegungen_Wald";

  private final ConstraintTestTools tools = new ConstraintTestTools(new IliCompilerService());

  @Test
  void keepsValidatorPathDiagnosticsOutOfConcurrentCompilations() throws Exception {
    String missingPath = "missingFromValidatorPath";
    String model = AFU_GOLDEN_MODEL.replace("Nebenauspraegung->Gewichtung", missingPath);
    var fixture = testCase("runtime path lookup", false, List.of(object(MAIN, "main60", 60)), List.of());
    var compiler = new IliCompilerService(".");
    var validator = new ConstraintTestTools(compiler);
    int rounds = 30;
    var start = new CyclicBarrier(2);
    var executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
    try {
      var validations = executor.submit(() -> {
        var results = new ArrayList<Map<String, Object>>();
        for (int round = 0; round < rounds; round++) {
          start.await(10, TimeUnit.SECONDS);
          results.add(validator.testIliConstraint(model, "GewichtungSumme100_Wald", List.of(fixture)));
        }
        return results;
      });
      var compilations = executor.submit(() -> {
        var results = new ArrayList<IliCompilerService.CompilationResult>();
        for (int round = 0; round < rounds; round++) {
          start.await(10, TimeUnit.SECONDS);
          // Keep compiling while the other request writes its XTF and evaluates Math.sum.
          for (int attempt = 0; attempt < 15; attempt++) {
            results.add(compiler.compile(MANDATORY_BOOLEAN_MODEL, null));
          }
        }
        return results;
      });

      assertThat(compilations.get(25, TimeUnit.SECONDS)).allSatisfy(result -> {
        assertThat(result.messages()).isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(result.transferDescription()).isNotNull();
      });
      assertThat(validations.get(25, TimeUnit.SECONDS)).allSatisfy(result ->
          assertThat(result).containsEntry("compilerValid", true).containsEntry("tested", true));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void validatesExplicitAfuWitnessAndCounterexampleCases() {
    ConstraintTestTools.TestCase noSecondary = testCase(
        "100 without secondary",
        true,
        List.of(object(MAIN, "main100", 100)),
        List.of());

    ConstraintTestTools.TestCase sumToHundred = testCase(
        "60 plus 20 plus 20",
        true,
        List.of(
            object(MAIN, "main60", 60),
            object(SECONDARY, "secondary20a", 20),
            object(SECONDARY, "secondary20b", 20)),
        List.of(
            link("main60", "secondary20a"),
            link("main60", "secondary20b")));

    ConstraintTestTools.TestCase wrongSum = testCase(
        "60 plus 20",
        false,
        List.of(
            object(MAIN, "mainInvalid", 60),
            object(SECONDARY, "secondaryInvalid", 20)),
        List.of(link("mainInvalid", "secondaryInvalid")));

    Map<String, Object> result = tools.testIliConstraint(
        AFU_GOLDEN_MODEL,
        "GewichtungSumme100_Wald",
        List.of(noSecondary, sumToHundred, wrongSum), true);

    assertEquals(true, result.get("tested"));
    assertEquals(true, result.get("compilerValid"));
    assertEquals(3, result.get("caseCount"));
    assertEquals(3, result.get("passedCount"));
    assertEquals(true, result.get("allPassed"));
    assertEquals(false, result.get("automaticCasesGenerated"));

    List<Map<String, Object>> cases = castList(result.get("cases"));
    assertEquals(true, cases.get(0).get("actualConstraintValid"));
    assertEquals(true, cases.get(1).get("actualConstraintValid"));
    assertEquals(false, cases.get(2).get("actualConstraintValid"));
    assertEquals(1, ((Number) cases.get(2).get("targetViolationCount")).intValue());
    assertEquals(true, cases.get(2).get("fixtureValid"));

    String linkedXtf = String.valueOf(cases.get(1).get("xtfText"));
    assertTrue(linkedXtf.contains("<Bodeneinheit REF=\"main60\""));
    assertFalse(linkedXtf.contains("<" + ASSOCIATION));
  }

  @Test
  void rejectsReversedExpectationsForEmptySecondaryAssociation() {
    var wrongAt100 = testCase("100 falsely expected invalid", false,
        List.of(object(MAIN, "main100", 100)), List.of());
    var wrongAt99 = testCase("99 falsely expected valid", true,
        List.of(object(MAIN, "main99", 99)), List.of());
    var result = tools.testIliConstraint(AFU_GOLDEN_MODEL, "GewichtungSumme100_Wald",
        List.of(wrongAt100, wrongAt99));
    assertEquals(false, result.get("allPassed"));
    var cases = castList(result.get("cases"));
    for (var fixture : cases) {
      assertEquals(false, fixture.get("passed"));
      assertEquals(true, fixture.get("fixtureValid"));
      assertEquals(true, fixture.get("constraintExercised"));
    }
    assertEquals(true, cases.get(0).get("actualConstraintValid"));
    assertEquals(false, cases.get(1).get("actualConstraintValid"));
    assertEquals(1, cases.get(1).get("targetViolationCount"));
  }

  @Test
  void validatorCannotProveAnExternalFunctionWithoutExecutableSemantics() {
    String model = MANDATORY_BOOLEAN_MODEL
        .replace("MODEL ConstraintFixtureModel", "CONTRACTED MODEL ConstraintFixtureModel")
        .replace("TOPIC Data =", "FUNCTION externalCheck(a: NUMERIC): BOOLEAN;\n  TOPIC Data =")
        .replace("value >= 10", "externalCheck(value)");
    var fixture = testCase("unimplemented external function", true,
        List.of(object("ConstraintFixtureModel.Data.Item", "item1", 10)), List.of());
    fixture.objects.getFirst().values = Map.of("value", 10);
    var result = tools.testIliConstraint(model, "ValueAtLeast10", List.of(fixture));
    assertEquals(true, result.get("compilerValid"), result.toString());
    assertEquals(false, result.get("allPassed"), result.toString());
    assertEquals(false, castList(result.get("cases")).getFirst().get("constraintExercised"));
  }

  @Test
  void autoFillsMandatoryBooleanAliasInFixture() {
    ConstraintTestTools.TestObject object = new ConstraintTestTools.TestObject();
    object.classFqn = "ConstraintFixtureModel.Data.Item";
    object.oid = "item1";
    object.values = Map.of("value", 10);

    ConstraintTestTools.TestCase testCase = testCase(
        "mandatory boolean is auto-filled",
        true,
        List.of(object),
        List.of());

    Map<String, Object> result = tools.testIliConstraint(
        MANDATORY_BOOLEAN_MODEL,
        "ValueAtLeast10",
        List.of(testCase), true);

    assertEquals(true, result.get("allPassed"));
    List<Map<String, Object>> cases = castList(result.get("cases"));
    assertEquals(true, cases.getFirst().get("fixtureValid"));
    assertTrue(String.valueOf(cases.getFirst().get("xtfText")).contains("<enabled>false</enabled>"));
  }

  private ConstraintTestTools.TestCase testCase(
      String name,
      boolean expected,
      List<ConstraintTestTools.TestObject> objects,
      List<ConstraintTestTools.TestLink> links) {
    ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
    testCase.name = name;
    testCase.expectedConstraintValid = expected;
    testCase.objects = objects;
    testCase.links = links;
    return testCase;
  }

  private ConstraintTestTools.TestObject object(String classFqn, String oid, int weight) {
    ConstraintTestTools.TestObject object = new ConstraintTestTools.TestObject();
    object.classFqn = classFqn;
    object.oid = oid;
    object.values = Map.of("Gewichtung", weight);
    return object;
  }

  private ConstraintTestTools.TestLink link(String mainOid, String secondaryOid) {
    ConstraintTestTools.TestLink link = new ConstraintTestTools.TestLink();
    link.associationFqn = ASSOCIATION;
    link.roles = Map.of(
        "Bodeneinheit", mainOid,
        "Nebenauspraegung", secondaryOid);
    return link;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> castList(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
