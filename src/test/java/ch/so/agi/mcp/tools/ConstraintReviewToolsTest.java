package ch.so.agi.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintReviewToolsTest {

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
        END Bodeneinheiten;
      END SO_AFU_Bodeneinheiten_20251210.
      """;

  private final IliCompilerService compilerService = new IliCompilerService();
  private final ConstraintKnowledgeTools knowledgeTools = new ConstraintKnowledgeTools(
      new MathTools(), new TextTools(), compilerService);
  private final ConstraintReviewTools tools = new ConstraintReviewTools(compilerService, knowledgeTools);

  @Test
  void reviewsAfuWeightingConstraintStructurally() {
    Map<String, Object> result = tools.reviewIliConstraint(
        AFU_GOLDEN_MODEL,
        "GewichtungSumme100_Wald",
        null);

    assertEquals(true, result.get("valid"));
    assertEquals(true, result.get("compilerValid"));
    assertEquals("OK", result.get("reviewStatus"));
    assertEquals(true, result.get("astComplete"));

    Map<String, Object> context = castMap(result.get("context"));
    assertEquals(
        "SO_AFU_Bodeneinheiten_20251210.Bodeneinheiten.BodeneinheitHauptauspraegung_Wald",
        context.get("scopedName"));

    Map<String, Object> ast = castMap(result.get("ast"));
    assertEquals("MANDATORY_CONSTRAINT", ast.get("kind"));
    assertEquals("OR", castMap(ast.get("condition")).get("kind"));

    List<Map<String, Object>> functions = castList(result.get("functions"));
    Map<String, Object> sum = functions.stream()
        .filter(function -> "Math.sum".equals(function.get("name")))
        .findFirst()
        .orElseThrow();
    assertEquals("STANDARD_FUNCTION_MODEL", sum.get("origin"));
    assertEquals(3, ((Number) sum.get("callCount")).intValue());
    assertTrue(functions.stream().anyMatch(function -> "Math.add".equals(function.get("name"))));

    List<Map<String, Object>> paths = castList(result.get("paths"));
    Map<String, Object> aggregatePath = paths.stream()
        .filter(path -> "FUNCTION_ATTRIBUTE_PATH".equals(path.get("source")))
        .filter(path -> "Nebenauspraegung->Gewichtung".equals(path.get("path")))
        .findFirst()
        .orElseThrow();
    assertEquals(true, aggregatePath.get("valid"));
    assertEquals(true, aggregatePath.get("collection"));
    assertEquals(3, ((Number) aggregatePath.get("occurrences")).intValue());

    List<Map<String, Object>> referenced = castList(result.get("referencedElements"));
    assertTrue(referenced.stream().anyMatch(element -> "Gewichtung".equals(element.get("name"))));
    assertTrue(referenced.stream().anyMatch(element -> "Nebenauspraegung".equals(element.get("name"))));

    List<Map<String, Object>> types = castList(result.get("types"));
    assertTrue(types.stream().anyMatch(type -> "NUMERIC".equals(type.get("kind"))));
    assertTrue(types.stream().anyMatch(type -> "BOOLEAN".equals(type.get("kind"))));

    List<Map<String, Object>> edgeCases = castList(result.get("edgeCases"));
    assertTrue(edgeCases.stream().anyMatch(edgeCase -> "OPTIONAL_NAVIGATION".equals(edgeCase.get("kind"))));
    assertTrue(edgeCases.stream().anyMatch(edgeCase -> "MULTIPLE_TARGETS".equals(edgeCase.get("kind"))));

    assertTrue(castList(result.get("findings")).isEmpty());
    @SuppressWarnings("unchecked")
    List<String> limitations = (List<String>) result.get("limitations");
    assertTrue(limitations.stream().anyMatch(text -> text.contains("No test data")));
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
