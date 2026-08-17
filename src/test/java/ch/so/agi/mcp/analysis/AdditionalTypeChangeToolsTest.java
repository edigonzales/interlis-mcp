package ch.so.agi.mcp.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.service.IliCompilerService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdditionalTypeChangeToolsTest {

  @Test
  void detectsBlackboxKindChange() {
    Map<String, Object> response = tools().reviewIliChange(blackboxModel("XML"), blackboxModel("BINARY"), null);

    assertChanged(response, "Demo.Topic.Thing.payload", "BLACKBOX|kind=XML", "BLACKBOX|kind=BINARY");
  }

  @Test
  void detectsCoordinateGenericAndCrsChange() {
    Map<String, Object> response = tools().reviewIliChange(
        coordinateSemanticsModel("", "EPSG:2056"),
        coordinateSemanticsModel(" (GENERIC)", "EPSG:4326"),
        null);

    assertChanged(
        response,
        "Demo.Point",
        "CoordType|dimensions=0.000..100.000,0.000..100.000|crs=EPSG:2056",
        "CoordType|dimensions=0.000..100.000,0.000..100.000|generic=true|crs=EPSG:4326");
  }

  @Test
  void detectsCompositionComponentCardinalityAndOrderChange() {
    Map<String, Object> response = tools().reviewIliChange(
        compositionModel("BAG", "{0..*}", "PartA"),
        compositionModel("LIST", "{1..*}", "PartB"),
        null);

    assertChanged(
        response,
        "Demo.Topic.Thing.items",
        "COMPOSITION|component=Demo.Topic.PartA|cardinality={0..*}",
        "COMPOSITION|component=Demo.Topic.PartB|cardinality={1..*}|ordered=true");
  }

  @Test
  void detectsPolylineDirectionVertexLineFormsAndOverlapChanges() {
    Map<String, Object> response = tools().reviewIliChange(
        lineModel("POLYLINE", "STRAIGHTS", "CoordA", "0.010"),
        lineModel("DIRECTED POLYLINE", "STRAIGHTS, ARCS", "CoordB", "0.020"),
        null);

    assertChanged(
        response,
        "Demo.Topic.Thing.geometry",
        "PolylineType|controlPointDomain=Demo.CoordA|lineForms=STRAIGHTS|maxOverlap=0.010",
        "PolylineType|directed=true|controlPointDomain=Demo.CoordB|lineForms=ARCS,STRAIGHTS|maxOverlap=0.020");
  }

  private ModelChangeTools tools() {
    IliCompilerService compiler = new IliCompilerService();
    return new ModelChangeTools(compiler, new ModelAnalysisTools(compiler));
  }

  private void assertChanged(Map<String, Object> response, String scopedName, String beforeType, String afterType) {
    assertThat(response.get("valid")).isEqualTo(true);
    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("changed")).asList()
        .singleElement()
        .satisfies(change -> assertThat(change.toString())
            .contains(scopedName)
            .contains("typeText")
            .contains(beforeType)
            .contains(afterType));
  }

  private String blackboxModel(String kind) {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          TOPIC Topic =
            CLASS Thing =
              payload : BLACKBOX %s;
            END Thing;
          END Topic;
        END Demo.
        """.formatted(kind);
  }

  private String coordinateSemanticsModel(String properties, String crs) {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          DOMAIN
            Point%s = COORD
              0.000 .. 100.000,
              0.000 .. 100.000
              REFSYS "%s";
        END Demo.
        """.formatted(properties, crs);
  }

  private String compositionModel(String collection, String cardinality, String component) {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          TOPIC Topic =
            STRUCTURE PartA =
              value : TEXT;
            END PartA;
            STRUCTURE PartB =
              value : TEXT;
            END PartB;
            CLASS Thing =
              items : %s %s OF %s;
            END Thing;
          END Topic;
        END Demo.
        """.formatted(collection, cardinality, component);
  }

  private String lineModel(String lineKind, String lineForms, String coordDomain, String overlap) {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          DOMAIN
            CoordA = COORD
              0.000 .. 100.000,
              0.000 .. 100.000;
            CoordB = COORD
              0.000 .. 200.000,
              0.000 .. 200.000;
          TOPIC Topic =
            CLASS Thing =
              geometry : %s WITH (%s) VERTEX Demo.%s WITHOUT OVERLAPS > %s;
            END Thing;
          END Topic;
        END Demo.
        """.formatted(lineKind, lineForms, coordDomain, overlap);
  }
}
