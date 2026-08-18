package ch.so.agi.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
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
        List.of(noSecondary, sumToHundred, wrongSum),
        null);

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
        List.of(testCase),
        null);

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
