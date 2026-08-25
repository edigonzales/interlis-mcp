package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.so.agi.mcp.model.GeometryTypeSpec;
import ch.so.agi.mcp.model.BaseType;
import ch.so.agi.mcp.service.IliCompilerService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class GeometryTypeRendererTest {

  private final GeometryTypeRenderer renderer = new GeometryTypeRenderer();

  @Test
  void baseTypeNoLongerAdvertisesInvalidGeometryVariants() {
    assertThat(java.util.Arrays.stream(BaseType.Kind.values()).map(Enum::name))
        .doesNotContain("COORD", "POLYLINE", "SURFACE_SIMPLE");
  }

  @Test
  void rejectsInterlisGeometryWithoutApplicableExplicitParameters() {
    GeometryTypeSpec geometry = interlis(GeometryTypeSpec.InterlisKind.POLYLINE);

    assertThatThrownBy(() -> renderer.render(geometry, "2.4"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("arcs and overlapMm");

    geometry.setArcs(true);
    geometry.setOverlapMm(new BigDecimal("1"));
    assertThatThrownBy(() -> renderer.render(geometry, "2.4"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("directed");
  }

  @Test
  void rejectsInterlis24MultiGeometryForInterlis23() {
    GeometryTypeSpec geometry = interlis(GeometryTypeSpec.InterlisKind.MULTISURFACE);
    geometry.setArcs(false);
    geometry.setOverlapMm(new BigDecimal("1"));

    assertThatThrownBy(() -> renderer.render(geometry, "2.3"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires INTERLIS 2.4");
  }

  @Test
  void rejectsDirectedForSurfaceAndChBaseFieldsFromWrongVersion() {
    GeometryTypeSpec surface = interlis(GeometryTypeSpec.InterlisKind.SURFACE);
    surface.setArcs(true);
    surface.setOverlapMm(new BigDecimal("1"));
    surface.setDirected(false);
    assertThatThrownBy(() -> renderer.render(surface, "2.4"))
        .hasMessageContaining("not applicable");

    GeometryTypeSpec chBase = new GeometryTypeSpec();
    chBase.setProvider(GeometryTypeSpec.Provider.CHBASE);
    chBase.setChBaseType("GeometryCHLV95_V2.Coord2");
    assertThatThrownBy(() -> renderer.render(chBase, "2.3"))
        .hasMessageContaining("GeometryCHLV95_V1");

    chBase.setChBaseType("SurfaceWithoutArcs");
    assertThatThrownBy(() -> renderer.render(chBase, "2.3"))
        .hasMessageContaining("Unsupported CHBASE geometry type");
  }

  @Test
  void rendersOnlyKnownChBaseTypeForActualVersion() {
    GeometryTypeSpec chBase = new GeometryTypeSpec();
    chBase.setProvider(GeometryTypeSpec.Provider.CHBASE);
    chBase.setChBaseType("Coord2");

    GeometryTypeRenderer.RenderedGeometry rendered = renderer.render(chBase, "2.4");

    assertThat(rendered.typeText()).isEqualTo("GeometryCHLV95_V2.Coord2");
    assertThat(rendered.requiredImports()).containsExactly("GeometryCHLV95_V2");
  }

  @Test
  void renderedInterlisSurfaceCompilesInACompleteModel() {
    GeometryTypeSpec surface = interlis(GeometryTypeSpec.InterlisKind.SURFACE);
    surface.setArcs(true);
    surface.setOverlapMm(BigDecimal.ONE);
    GeometryTypeRenderer.RenderedGeometry rendered = renderer.render(surface, "2.4");
    String model = """
        INTERLIS 2.4;
        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-24" =
          DOMAIN
            Coord2 = COORD
              2600000.0 .. 2610000.0 [INTERLIS.m],
              1200000.0 .. 1210000.0 [INTERLIS.m];
          TOPIC Data =
            CLASS Parcel =
              geometry : %s;
            END Parcel;
          END Data;
        END Demo.
        """.formatted(rendered.typeText());

    var compilation = new IliCompilerService().compile(model, null);

    assertThat(compilation.valid()).as("%s\n%s", compilation.messages(), model).isTrue();
    assertThat(rendered.requiredImports()).isEmpty();
  }

  private GeometryTypeSpec interlis(GeometryTypeSpec.InterlisKind kind) {
    GeometryTypeSpec geometry = new GeometryTypeSpec();
    geometry.setProvider(GeometryTypeSpec.Provider.INTERLIS);
    geometry.setInterlisKind(kind);
    geometry.setCoordDomainFqn("Demo.Coord2");
    return geometry;
  }
}
