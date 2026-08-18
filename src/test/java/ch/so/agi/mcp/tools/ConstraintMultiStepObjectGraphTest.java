package ch.so.agi.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintMultiStepObjectGraphTest {

  private static final String MODEL = """
      INTERLIS 2.3;

      MODEL RichPathModel (en)
      AT "https://example.org"
      VERSION "2026-08-18" =
        TOPIC Data =
          STRUCTURE Address =
            PLZ : MANDATORY 1000 .. 9999;
            Ort : MANDATORY TEXT*30;
          END Address;

          CLASS Country =
            Code : MANDATORY 1 .. 99;
            Rate : MANDATORY 0 .. 100;
          END Country;

          CLASS Owner =
            Land : MANDATORY REFERENCE TO Country;
            Adresse : MANDATORY Address;
          END Owner;

          CLASS Parcel =
            Dummy : MANDATORY 0 .. 1;
          END Parcel;

          ASSOCIATION ParcelOwner =
            Parzelle -- {1} Parcel;
            Eigentuemer -- {0..1} Owner;
          END ParcelOwner;

          CONSTRAINTS OF RichPathModel.Data.Parcel =
            !!@ name = "SharedReferencePath"
            MANDATORY CONSTRAINT
              Eigentuemer->Land->Code >= 10
              AND Eigentuemer->Land->Rate <= 20;

            !!@ name = "StructurePath"
            MANDATORY CONSTRAINT Eigentuemer->Adresse->PLZ >= 3000;
          END;
        END Data;
      END RichPathModel.
      """;

  private final IliCompilerService compilerService = new IliCompilerService();
  private final ConstraintKnowledgeTools knowledgeTools = new ConstraintKnowledgeTools(
      new MathTools(), new TextTools(), compilerService);
  private final ConstraintReviewTools reviewTools = new ConstraintReviewTools(compilerService, knowledgeTools);
  private final ConstraintTestTools testTools = new ConstraintTestTools(compilerService);
  private final ConstraintCaseGenerationTools tools = new ConstraintCaseGenerationTools(
      reviewTools, testTools, compilerService);

  @Test
  void reusesSharedAssociationAndReferencePrefixesAndValidatesTheGraph() {
    Map<String, Object> result = tools.generateIliConstraintCases(
        MODEL,
        "SharedReferencePath",
        null);

    assertEquals(true, result.get("automaticCasesAvailable"), String.valueOf(result));
    assertEquals(true, result.get("generationVerified"), String.valueOf(result));

    List<Map<String, Object>> generated = list(result.get("generatedCases"));
    Map<String, Object> allTrue = generated.stream()
        .filter(item -> "AND all operands true".equals(item.get("reason")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing AND witness: " + generated));

    assertEquals(3, ((Number) allTrue.get("objectCount")).intValue(), String.valueOf(allTrue));
    assertEquals(1, ((Number) allTrue.get("associationLinkCount")).intValue(), String.valueOf(allTrue));
    Map<String, Object> values = map(allTrue.get("values"));
    assertTrue(values.containsKey("Eigentuemer->Land->Code"), String.valueOf(values));
    assertTrue(values.containsKey("Eigentuemer->Land->Rate"), String.valueOf(values));

    Map<String, Object> verification = map(result.get("verification"));
    assertEquals(true, verification.get("allPassed"), String.valueOf(verification));
    assertTrue(list(verification.get("cases")).stream().anyMatch(item -> {
      String xtf = String.valueOf(item.get("xtfText"));
      return xtf.contains("<Land REF=")
          && count(xtf, "<RichPathModel.Data.Country") == 1;
    }), String.valueOf(verification));
  }

  @Test
  void writesStructureNavigationAsNestedXtfAndValidatesIt() {
    Map<String, Object> result = tools.generateIliConstraintCases(
        MODEL,
        "StructurePath",
        null);

    assertEquals(true, result.get("automaticCasesAvailable"), String.valueOf(result));
    assertEquals(true, result.get("generationVerified"), String.valueOf(result));

    List<Map<String, Object>> generated = list(result.get("generatedCases"));
    Map<String, Object> boundary = generated.stream()
        .filter(item -> "at comparison value".equals(item.get("reason")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing structure boundary witness: " + generated));
    assertEquals(true, boundary.get("expectedConstraintValid"));
    assertEquals(2, ((Number) boundary.get("objectCount")).intValue(), String.valueOf(boundary));
    assertEquals(1, ((Number) boundary.get("associationLinkCount")).intValue(), String.valueOf(boundary));

    Map<String, Object> verification = map(result.get("verification"));
    assertEquals(true, verification.get("allPassed"), String.valueOf(verification));
    assertTrue(list(verification.get("cases")).stream().anyMatch(item -> {
      String xtf = String.valueOf(item.get("xtfText"));
      return xtf.contains("<Adresse>")
          && xtf.contains("<PLZ>3000</PLZ>");
    }), String.valueOf(verification));
  }

  private int count(String text, String needle) {
    int result = 0;
    int offset = 0;
    while ((offset = text.indexOf(needle, offset)) >= 0) {
      result++;
      offset += needle.length();
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
