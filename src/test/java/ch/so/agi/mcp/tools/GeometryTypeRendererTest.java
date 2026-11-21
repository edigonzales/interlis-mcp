package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.so.agi.mcp.constraint.ConstraintFixtures;
import ch.so.agi.mcp.model.AttributeLineRequest;
import ch.so.agi.mcp.model.GeometryTypeSpec;
import ch.so.agi.mcp.model.IliSpecRenderer;
import ch.so.agi.mcp.model.TypeSpec;
import ch.so.agi.mcp.model.BaseType;
import ch.so.agi.mcp.service.IliCompilerService;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

  @ParameterizedTest
  @CsvSource({"2.3,GeometryCHLV95_V1", "2.4,GeometryCHLV95_V2"})
  void rendersChBaseAndCollectsTheVersionedModelImport(String version, String chBaseModel) {
    GeometryTypeSpec chBase = new GeometryTypeSpec();
    chBase.setProvider(GeometryTypeSpec.Provider.CHBASE);
    chBase.setChBaseType("Surface");

    Set<String> imports = new LinkedHashSet<>();
    String attribute = renderAttribute(chBase, version, imports);
    assertThat(attribute).isEqualTo("geometry : " + chBaseModel + ".Surface;");
    assertThat(imports).containsExactly(chBaseModel);
    var compilation = new IliCompilerService(ConstraintFixtures.repositories()).compile("""
        INTERLIS %s;
        MODEL Demo AT "https://example.org" VERSION "1" =
          IMPORTS %s;
          TOPIC Data = CLASS Parcel = %s END Parcel; END Data;
        END Demo.
        """.formatted(version, chBaseModel, attribute), null);
    assertThat(compilation.valid()).as("%s", compilation.messages()).isTrue();
  }

  @Test
  void renderedInterlisSurfaceCompilesWithoutSelfImport() {
    GeometryTypeSpec surface = interlis(GeometryTypeSpec.InterlisKind.SURFACE);
    surface.setArcs(true);
    surface.setOverlapMm(BigDecimal.ONE);
    Set<String> imports = new LinkedHashSet<>();
    String attribute = renderAttribute(surface, "2.4", imports);
    String model = """
        INTERLIS 2.4;
        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-24" =
          DOMAIN
            Coord2 = COORD
              2600000.0 .. 2610000.0 [INTERLIS.m],
              1200000.0 .. 1210000.0 [INTERLIS.m];
          TOPIC Data =
            CLASS Parcel =
              %s
            END Parcel;
          END Data;
        END Demo.
        """.formatted(attribute);

    var compilation = new IliCompilerService().compile(model, null);

    assertThat(compilation.valid()).as("%s\n%s", compilation.messages(), model).isTrue();
    assertThat(attribute).contains("WITH (STRAIGHTS, ARCS)", "VERTEX Demo.Coord2");
    assertThat(imports).isEmpty();
  }

  private String renderAttribute(GeometryTypeSpec geometry, String version, Set<String> imports) {
    TypeSpec type = new TypeSpec();
    type.setGeometryType(geometry);
    AttributeLineRequest attribute = new AttributeLineRequest();
    attribute.setName("geometry");
    attribute.setTypeSpec(type);
    return new IliSpecRenderer(new AttributeTools(renderer), new DomainTools())
        .renderAttribute(attribute, version, "Demo", imports);
  }

  private GeometryTypeSpec interlis(GeometryTypeSpec.InterlisKind kind) {
    GeometryTypeSpec geometry = new GeometryTypeSpec();
    geometry.setProvider(GeometryTypeSpec.Provider.INTERLIS);
    geometry.setInterlisKind(kind);
    geometry.setCoordDomainFqn("Demo.Coord2");
    return geometry;
  }
}
