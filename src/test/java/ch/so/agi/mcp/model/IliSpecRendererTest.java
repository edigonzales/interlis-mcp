package ch.so.agi.mcp.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.AttributeTools;
import ch.so.agi.mcp.tools.DomainTools;
import java.util.List;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class IliSpecRendererTest {

  @Test
  void rendersACompleteMinimalModelThatCompiles() {
    IliModelSpec spec = new IliModelSpec();
    spec.name = "Demo";
    spec.uri = "https://example.org/demo";
    spec.version = "2026-08-24";
    spec.iliVersion = "2.4";
    spec.language = "de";

    BaseType text = new BaseType();
    text.setKind(BaseType.Kind.TEXT);
    text.setLength(20);
    TypeSpec type = new TypeSpec();
    type.setBaseType(text);
    AttributeLineRequest attribute = new AttributeLineRequest();
    attribute.setName("name");
    attribute.setTypeSpec(type);

    IliModelSpec.ClassSpec clazz = new IliModelSpec.ClassSpec();
    clazz.name = "Thing";
    clazz.attributes = List.of(attribute);
    IliModelSpec.TopicSpec topic = new IliModelSpec.TopicSpec();
    topic.name = "Data";
    topic.classes = List.of(clazz);
    spec.topics = List.of(topic);

    IliSpecRenderer.RenderedModel rendered = renderer().renderModel(spec);
    var compilation = new IliCompilerService().compile(rendered.modelText(), null);

    assertThat(compilation.valid()).as("%s", compilation.messages()).isTrue();
    assertThat(rendered.modelText()).contains("name : TEXT*20;");
    assertThat(rendered.derivedImports()).isEmpty();
  }

  @Test
  void rendersAllFiveConstraintKindsAndChBaseGeometry() {
    IliModelSpec spec = new IliModelSpec();
    spec.name = "CompleteDemo";
    spec.uri = "https://example.org/complete";
    spec.version = "2026-08-24";
    spec.iliVersion = "2.4";

    IliModelSpec.ClassSpec target = new IliModelSpec.ClassSpec();
    target.name = "Target";
    target.attributes = List.of(textAttribute("code", true));

    AttributeLineRequest code = textAttribute("code", false);
    AttributeLineRequest other = textAttribute("other", false);
    GeometryTypeSpec geometry = new GeometryTypeSpec();
    geometry.setProvider(GeometryTypeSpec.Provider.CHBASE);
    geometry.setChBaseType("Coord2");
    TypeSpec geometryType = new TypeSpec();
    geometryType.setGeometryType(geometry);
    AttributeLineRequest position = new AttributeLineRequest();
    position.setName("position");
    position.setTypeSpec(geometryType);

    IliModelSpec.ClassSpec item = new IliModelSpec.ClassSpec();
    item.name = "Item";
    item.attributes = List.of(code, other, position);
    item.constraints = List.of(
        unique("UniqueCode"),
        mandatory("CodeDefined"),
        existence("CodeExists"),
        plausibility("UsuallyDefined"),
        set("AtLeastNone"));

    IliModelSpec.TopicSpec topic = new IliModelSpec.TopicSpec();
    topic.name = "Data";
    topic.classes = List.of(target, item);
    spec.topics = List.of(topic);

    IliSpecRenderer.RenderedModel rendered = renderer().renderModel(spec);
    var compilation = new IliCompilerService().compile(rendered.modelText(), null);

    assertThat(compilation.valid()).as("%s\n%s", compilation.messages(), rendered.modelText()).isTrue();
    assertThat(rendered.derivedImports()).containsExactly("GeometryCHLV95_V2");
    assertThat(rendered.modelText())
        .contains("UNIQUE code;")
        .contains("MANDATORY CONSTRAINT")
        .contains("EXISTENCE CONSTRAINT")
        .contains("CONSTRAINT >= 0%")
        .contains("SET CONSTRAINT");
  }

  @Test
  void derivesImportFromExplicitNamedAttributeType() {
    TypeSpec type = new TypeSpec();
    type.setDomainFqn("ExternalModel.Status");
    AttributeLineRequest attribute = new AttributeLineRequest();
    attribute.setName("status");
    attribute.setTypeSpec(type);

    IliSpecRenderer.RenderedAttribute rendered =
        renderer().renderAttribute(attribute, "2.4", "Demo");

    assertThat(rendered.derivedImports()).containsExactly("ExternalModel");
  }

  @Test
  void rejectsMissingModelMetadataInsteadOfInventingIt() {
    IliModelSpec spec = new IliModelSpec();
    spec.name = "Demo";

    assertThatThrownBy(() -> renderer().renderModel(spec))
        .hasMessageContaining("spec.iliVersion is required");

    spec.iliVersion = "2.4";
    assertThatThrownBy(() -> renderer().renderModel(spec))
        .hasMessageContaining("spec.uri is required");

    spec.uri = "https://example.org/demo";
    assertThatThrownBy(() -> renderer().renderModel(spec))
        .hasMessageContaining("spec.version is required");
  }

  private AttributeLineRequest textAttribute(String name, boolean mandatory) {
    BaseType text = new BaseType();
    text.setKind(BaseType.Kind.TEXT);
    text.setLength(20);
    TypeSpec type = new TypeSpec();
    type.setBaseType(text);
    AttributeLineRequest attribute = new AttributeLineRequest();
    attribute.setName(name);
    attribute.setMandatory(mandatory);
    attribute.setTypeSpec(type);
    return attribute;
  }

  private IliConstraintSpec.Unique unique(String name) {
    IliConstraintSpec.Unique spec = new IliConstraintSpec.Unique();
    spec.name = name;
    spec.scope = IliConstraintSpec.UniqueScope.GLOBAL;
    spec.keyPaths = List.of("code");
    return spec;
  }

  private IliConstraintSpec.Mandatory mandatory(String name) {
    IliConstraintSpec.Mandatory spec = new IliConstraintSpec.Mandatory();
    spec.name = name;
    spec.condition = defined("code");
    return spec;
  }

  private IliConstraintSpec.Existence existence(String name) {
    IliConstraintSpec.Existence spec = new IliConstraintSpec.Existence();
    spec.name = name;
    spec.restrictedPath = "code";
    IliConstraintSpec.ExistenceTargetSpec target = new IliConstraintSpec.ExistenceTargetSpec();
    target.viewableFqn = "CompleteDemo.Data.Target";
    target.attributePath = "code";
    spec.requiredIn = List.of(target);
    return spec;
  }

  private IliConstraintSpec.Plausibility plausibility(String name) {
    IliConstraintSpec.Plausibility spec = new IliConstraintSpec.Plausibility();
    spec.name = name;
    spec.direction = IliConstraintSpec.PlausibilityDirection.AT_LEAST;
    spec.percentage = BigDecimal.ZERO;
    spec.condition = defined("code");
    return spec;
  }

  private IliConstraintSpec.Set set(String name) {
    IliConstraintSpec.Set spec = new IliConstraintSpec.Set();
    spec.name = name;
    spec.scope = IliConstraintSpec.SetScope.GLOBAL;
    IliConstraintSpec.ObjectCountSetConditionSpec condition =
        new IliConstraintSpec.ObjectCountSetConditionSpec();
    condition.objects = new IliConstraintSpec.AllObjectsSpec();
    condition.operator = ">=";
    condition.threshold = BigDecimal.ZERO;
    spec.condition = condition;
    return spec;
  }

  private IliConstraintSpec.ExpressionSpec defined(String attribute) {
    IliConstraintSpec.ExpressionSpec value = new IliConstraintSpec.ExpressionSpec();
    value.kind = IliConstraintSpec.ExpressionKind.ATTRIBUTE;
    value.name = attribute;
    IliConstraintSpec.ExpressionSpec defined = new IliConstraintSpec.ExpressionSpec();
    defined.kind = IliConstraintSpec.ExpressionKind.DEFINED;
    defined.children = List.of(value);
    return defined;
  }

  private IliSpecRenderer renderer() {
    return new IliSpecRenderer(new AttributeTools(), new DomainTools());
  }
}
