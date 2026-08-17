package ch.so.agi.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintKnowledgeToolsTest {

  private static final String MODEL = """
      INTERLIS 2.4;

      MODEL ConstraintPathTest AT "https://example.org" VERSION "2026-08-17" =
        TOPIC Data =
          CLASS Haupt =
            Gewichtung : 0 .. 100;
          END Haupt;

          CLASS Neben =
            Gewichtung : 0 .. 100;
            Bemerkung : TEXT*40;
          END Neben;

          ASSOCIATION Haupt_Neben =
            Haupt -- {1} Haupt;
            Nebenauspraegung -- {0..3} Neben;
          END Haupt_Neben;
        END Data;
      END ConstraintPathTest.
      """;

  private final ConstraintKnowledgeTools tools = new ConstraintKnowledgeTools(
      new MathTools(), new TextTools(), new IliCompilerService());

  @Test
  void catalogExplainsAttributePathFunctionsAndOrigin() {
    Map<String, Object> result = tools.listConstraintFunctions("2.4");
    List<Map<String, Object>> functions = castList(result.get("functions"));

    Map<String, Object> sum = functions.stream()
        .filter(function -> "Math_V2.sum".equals(function.get("name")))
        .findFirst()
        .orElseThrow();

    assertEquals("STANDARD_FUNCTION_MODEL", sum.get("origin"));
    assertEquals("Math_V2", sum.get("sourceModel"));
    assertEquals("ILI23_OBJECT_OR_ATTRIBUTE_PATH", sum.get("pathSemantics"));
    List<Map<String, Object>> parameters = castList(sum.get("parameters"));
    assertEquals("ATTRIBUTE_PATH", parameters.getFirst().get("semanticType"));
  }

  @Test
  void resolvesAfuStyleRoleAttributePathWithIli2cParser() {
    Map<String, Object> result = tools.resolveConstraintPath(
        MODEL,
        "ConstraintPathTest.Data.Haupt",
        "\"Nebenauspraegung->Gewichtung\"",
        null);

    assertEquals(true, result.get("valid"));
    assertEquals("Nebenauspraegung->Gewichtung", result.get("path"));
    assertEquals(true, result.get("attributePath"));
    assertEquals(true, result.get("collection"));

    List<Map<String, Object>> steps = castList(result.get("steps"));
    assertEquals(2, steps.size());
    assertEquals("ROLE", steps.get(0).get("kind"));
    assertEquals("Nebenauspraegung", steps.get(0).get("name"));
    assertEquals("{0..3}", steps.get(0).get("cardinality"));
    assertEquals("ConstraintPathTest.Data.Neben", steps.get(0).get("target"));
    assertEquals("ATTRIBUTE", steps.get(1).get("kind"));
    assertEquals("Gewichtung", steps.get(1).get("name"));

    Map<String, Object> valueType = castMap(result.get("result"));
    assertEquals("NUMERIC", valueType.get("kind"));
    assertEquals("0..100", valueType.get("typeText"));
  }

  @Test
  void invalidPathReturnsCandidatesAtFailurePoint() {
    Map<String, Object> result = tools.resolveConstraintPath(
        MODEL,
        "ConstraintPathTest.Data.Haupt",
        "Nebenauspraegung->Gewicht",
        null);

    assertEquals(false, result.get("valid"));
    assertEquals("Gewicht", result.get("failedSegment"));
    assertEquals(1, result.get("failedSegmentIndex"));
    List<Map<String, Object>> candidates = castList(result.get("candidates"));
    assertTrue(candidates.stream().anyMatch(candidate -> "Gewichtung".equals(candidate.get("name"))));
    assertTrue(candidates.stream().anyMatch(candidate -> "Bemerkung".equals(candidate.get("name"))));
  }

  @Test
  void scalarAttributePathIsNotACollection() {
    Map<String, Object> result = tools.resolveConstraintPath(
        MODEL,
        "ConstraintPathTest.Data.Haupt",
        "Gewichtung",
        null);

    assertEquals(true, result.get("valid"));
    assertEquals(false, result.get("collection"));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> castList(Object value) {
    return (List<Map<String, Object>>) value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object value) {
    return (Map<String, Object>) value;
  }
}
