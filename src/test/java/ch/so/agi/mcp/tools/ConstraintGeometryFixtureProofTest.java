package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintGeometryFixtureProofTest {

  @Test
  void existenceProvesEqualAndDifferentLineAndSurfaceValues() {
    Map<String, String> geometries = new LinkedHashMap<>();
    geometries.put("Polyline", "POLYLINE WITH (STRAIGHTS) VERTEX Coord2");
    geometries.put("Surface", "SURFACE WITH (STRAIGHTS) VERTEX Coord2 WITHOUT OVERLAPS > 0.001");
    geometries.put("Area", "AREA WITH (STRAIGHTS) VERTEX Coord2 WITHOUT OVERLAPS > 0.001");

    for (Map.Entry<String, String> geometry : geometries.entrySet()) {
      Map<String, Object> result = tools().generateIliConstraintCases(
          model(geometry.getKey(), geometry.getValue()), "GeometryExists");

      assertThat(result.get("automaticCasesAvailable"))
          .as("%s: %s", geometry.getKey(), result)
          .isEqualTo(true);
      assertThat(result.get("generationVerified")).isEqualTo(true);
      assertThat(result.get("coverageComplete")).isEqualTo(true);
    }
  }

  @Test
  void existenceWithholdsMultigeometryWhenValidatorCannotCompareValues() {
    Map<String, String> geometries = Map.of(
        "MultiPolyline", "MULTIPOLYLINE WITH (STRAIGHTS) VERTEX Coord2",
        "MultiSurface", "MULTISURFACE WITH (STRAIGHTS) VERTEX Coord2 WITHOUT OVERLAPS > 0.001");
    for (Map.Entry<String, String> geometry : geometries.entrySet()) {
      Map<String, Object> result = tools().generateIliConstraintCases(
          model(geometry.getKey(), geometry.getValue()), "GeometryExists");
      assertThat(result.get("automaticCasesAvailable"))
          .as("%s: %s", geometry.getKey(), result)
          .isEqualTo(false);
      assertThat(result.get("generationVerified")).isEqualTo(false);
      assertThat(result.get("reasonCode")).isEqualTo("GEOMETRY_EQUALITY_VALIDATOR_FAILURE");
    }
  }

  @Test
  void uniqueProvesCoordinateAndSurfaceEqualityAndDifference() {
    Map<String, String> geometries = new LinkedHashMap<>();
    geometries.put("Coord", "Coord2");
    geometries.put("Surface", "SURFACE WITH (STRAIGHTS) VERTEX Coord2 WITHOUT OVERLAPS > 0.001");
    geometries.put("MultiPolyline", "MULTIPOLYLINE WITH (STRAIGHTS) VERTEX Coord2");
    geometries.put("MultiSurface", "MULTISURFACE WITH (STRAIGHTS) VERTEX Coord2 WITHOUT OVERLAPS > 0.001");
    for (Map.Entry<String, String> geometry : geometries.entrySet()) {
      String model = """
          INTERLIS 2.4;
          MODEL Unique%sProof (en) AT "https://example.org" VERSION "2026-08-25" =
            DOMAIN
              Coord2 = COORD 0.000 .. 100.000, 0.000 .. 100.000, ROTATION 2 -> 1;
            TOPIC Data =
              CLASS Item =
                value : %s;
                !!@ name = "GeometryUnique"
                UNIQUE value;
              END Item;
            END Data;
          END Unique%sProof.
          """.formatted(geometry.getKey(), geometry.getValue(), geometry.getKey());
      Map<String, Object> result = tools().generateIliConstraintCases(model, "GeometryUnique");
      assertThat(result.get("automaticCasesAvailable"))
          .as("%s: %s", geometry.getKey(), result)
          .isEqualTo(true);
      assertThat(result.get("coverageComplete")).isEqualTo(true);
    }
  }

  @Test
  void uniqueWithholdsAreaWhenDuplicateWouldViolateTopologyFirst() {
    String model = """
        INTERLIS 2.4;
        MODEL UniqueAreaProof (en) AT "https://example.org" VERSION "2026-08-25" =
          DOMAIN Coord2 = COORD 0.000 .. 100.000, 0.000 .. 100.000, ROTATION 2 -> 1;
          TOPIC Data =
            CLASS Item =
              value : MULTIAREA WITH (STRAIGHTS) VERTEX Coord2 WITHOUT OVERLAPS > 0.001;
              !!@ name = "AreaUnique"
              UNIQUE value;
            END Item;
          END Data;
        END UniqueAreaProof.
        """;
    Map<String, Object> result = tools().generateIliConstraintCases(model, "AreaUnique");
    assertThat(result.get("automaticCasesAvailable")).isEqualTo(false);
    assertThat(result.get("reasonCode")).isEqualTo("UNIQUE_AREA_DUPLICATE_NOT_MODEL_VALID");
  }

  @Test
  void uniqueProvesExternalReferenceOidEqualityAcrossBaskets() {
    String model = """
        INTERLIS 2.4;
        MODEL UniqueReferenceProof (en) AT "https://example.org" VERSION "2026-08-25" =
          TOPIC Data =
            CLASS Entity = END Entity;
            CLASS Item =
              value : REFERENCE TO (EXTERNAL) Entity;
              !!@ name = "ReferenceUnique"
              UNIQUE value;
            END Item;
          END Data;
        END UniqueReferenceProof.
        """;

    Map<String, Object> result = tools().generateIliConstraintCases(model, "ReferenceUnique");

    assertThat(result.get("automaticCasesAvailable")).as(result.toString()).isEqualTo(true);
    assertThat(result.get("coverageComplete")).isEqualTo(true);
    assertThat(result.get("generationVerified")).isEqualTo(true);
  }

  @Test
  void uniqueProvesStructureMemberEquality() {
    String model = """
        INTERLIS 2.4;
        MODEL UniqueStructureProof (en) AT "https://example.org" VERSION "2026-08-25" =
          TOPIC Data =
            STRUCTURE Key = code : MANDATORY TEXT*10; END Key;
            CLASS Item =
              value : Key;
              !!@ name = "StructureUnique"
              UNIQUE value;
            END Item;
          END Data;
        END UniqueStructureProof.
        """;

    Map<String, Object> result = tools().generateIliConstraintCases(model, "StructureUnique");

    assertThat(result.get("automaticCasesAvailable")).as(result.toString()).isEqualTo(true);
    assertThat(result.get("coverageComplete")).isEqualTo(true);
  }

  @Test
  void uniqueProvesMultiCoordinateEqualityAndDifference() {
    String model = """
        INTERLIS 2.4;
        MODEL UniqueMultiCoordProof (en) AT "https://example.org" VERSION "2026-08-25" =
          DOMAIN
            MultiPoint = MULTICOORD
              0.000 .. 100.000,
              0.000 .. 100.000,
              ROTATION 2 -> 1;
          TOPIC Data =
            CLASS Item =
              value : MultiPoint;
              !!@ name = "MultiCoordUnique"
              UNIQUE value;
            END Item;
          END Data;
        END UniqueMultiCoordProof.
        """;
    Map<String, Object> result = tools().generateIliConstraintCases(model, "MultiCoordUnique");
    assertThat(result.get("automaticCasesAvailable")).as(result.toString()).isEqualTo(true);
    assertThat(result.get("coverageComplete")).isEqualTo(true);
  }

  private ConstraintCaseGenerationTools tools() {
    IliCompilerService compiler = new IliCompilerService();
    return new ConstraintCaseGenerationTools(
        new ConstraintContextService(compiler), new ConstraintTestTools(compiler));
  }

  private String model(String suffix, String geometry) {
    return """
        INTERLIS 2.4;
        MODEL Existence%sProof (en) AT "https://example.org" VERSION "2026-08-25" =
          DOMAIN
            Coord2 = COORD 0.000 .. 100.000, 0.000 .. 100.000, ROTATION 2 -> 1;
            Shape = %s;
          TOPIC Data =
            CLASS Target = value : Shape; END Target;
            CLASS Source =
              value : Shape;
              !!@ name = "GeometryExists"
              EXISTENCE CONSTRAINT value REQUIRED IN Target:value;
            END Source;
          END Data;
        END Existence%sProof.
        """.formatted(suffix, geometry, suffix);
  }
}
