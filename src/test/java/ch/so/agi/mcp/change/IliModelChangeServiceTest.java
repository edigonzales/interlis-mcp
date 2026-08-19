package ch.so.agi.mcp.change;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.knowledge.KnowledgeRuleLoader;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.model.AttributeLineRequest;
import ch.so.agi.mcp.model.BaseType;
import ch.so.agi.mcp.model.TypeSpec;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IliModelChangeServiceTest {

  @Test
  void addAttributeIsSourcePreservingAndUsesExactlyTwoCompiles() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangeService service = service(compiler);

    Map<String, Object> response = service.apply(
        validModel(),
        addTextAttribute("Demo.Topic.Thing", "egid", true, 14),
        null,
        null,
        null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(response.get("applied")).isEqualTo(true);
    assertThat(response.get("status")).isEqualTo("APPLIED");
    assertThat(response.get("targetFqn")).isEqualTo("Demo.Topic.Thing.egid");
    assertThat(response.get("updatedModelText").toString())
        .contains("name : TEXT*20;\n      egid : MANDATORY TEXT*14;")
        .contains("END Thing;");
    assertThat(response.get("added")).asList()
        .singleElement()
        .satisfies(item -> assertThat(item.toString())
            .contains("ATTRIBUTE")
            .contains("Demo.Topic.Thing.egid"));
    assertThat(response.get("removed")).asList().isEmpty();
    assertThat(response.get("changed")).asList().isEmpty();
    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("afterReview")).isInstanceOf(Map.class);
    assertThat(response.get("sourceEdits")).asList()
        .singleElement()
        .satisfies(edit -> assertThat(edit.toString())
            .contains("before=")
            .contains("egid : MANDATORY TEXT*14;")
            .contains("Add attribute Demo.Topic.Thing.egid"));
  }

  @Test
  void addAttributeKeepsInlineCommentOnExistingAttribute() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangeService service = service(compiler);
    String before = validModel().replace("name : TEXT*20;", "name : TEXT*20; !! keep me");

    Map<String, Object> response = service.apply(
        before,
        addTextAttribute("Demo.Topic.Thing", "code", false, 10),
        null,
        null,
        null);

    assertThat(response.get("status")).isEqualTo("APPLIED");
    assertThat(response.get("updatedModelText").toString())
        .contains("name : TEXT*20; !! keep me\n      code : TEXT*10;");
  }

  @Test
  void addAttributeSupportsEmptyStructure() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangeService service = service(compiler);

    Map<String, Object> response = service.apply(
        emptyStructureModel(),
        addTextAttribute("Demo.Topic.Part", "label", false, 30),
        null,
        null,
        null);

    assertThat(response.get("status")).isEqualTo("APPLIED");
    assertThat(response.get("updatedModelText").toString())
        .contains("STRUCTURE Part =\n      label : TEXT*30;\n    END Part;");
    assertThat(response.get("added")).asList()
        .singleElement()
        .satisfies(item -> assertThat(item.toString()).contains("Demo.Topic.Part.label"));
  }

  @Test
  void addAttributePreservesCrLf() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangeService service = service(compiler);
    String before = validModel().replace("\n", "\r\n");

    Map<String, Object> response = service.apply(
        before,
        addTextAttribute("Demo.Topic.Thing", "code", false, 10),
        null,
        null,
        null);

    String updated = response.get("updatedModelText").toString();
    assertThat(response.get("status")).isEqualTo("APPLIED");
    assertThat(updated).contains("name : TEXT*20;\r\n      code : TEXT*10;\r\n");
    assertThat(updated.replace("\r\n", "")).doesNotContain("\n");
  }

  @Test
  void inheritedAttributeNameIsRejectedBeforeCandidateCompile() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangeService service = service(compiler);

    Map<String, Object> response = service.apply(
        inheritanceModel(),
        addTextAttribute("Demo.Topic.Child", "name", false, 40),
        null,
        null,
        null);

    assertThat(compiler.calls).isEqualTo(1);
    assertThat(response.get("applied")).isEqualTo(false);
    assertThat(response.get("status")).isEqualTo("NAME_ALREADY_EXISTS");
    assertThat(response.get("message").toString()).contains("Demo.Topic.Base.name");
    assertThat(response).doesNotContainKey("updatedModelText");
  }

  @Test
  void wrongContainerKindIsRejectedBeforeCandidateCompile() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangeService service = service(compiler);

    Map<String, Object> response = service.apply(
        validModel(),
        addTextAttribute("Demo.Topic", "code", false, 10),
        null,
        null,
        null);

    assertThat(compiler.calls).isEqualTo(1);
    assertThat(response.get("status")).isEqualTo("WRONG_TARGET_KIND");
  }

  @Test
  void missingContainerIsRejectedBeforeCandidateCompile() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangeService service = service(compiler);

    Map<String, Object> response = service.apply(
        validModel(),
        addTextAttribute("Demo.Topic.Missing", "code", false, 10),
        null,
        null,
        null);

    assertThat(compiler.calls).isEqualTo(1);
    assertThat(response.get("status")).isEqualTo("TARGET_NOT_FOUND");
  }

  @Test
  void unexpectedSemanticChangesAreNotReleasedAsUpdatedModelText() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangeService service = service(compiler);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("applied", true);
    response.put("status", "APPLIED");
    response.put("updatedModelText", "candidate");
    response.put("added", List.of(
        Map.of("kind", "ATTRIBUTE", "scopedName", "Demo.Topic.Thing.egid"),
        Map.of("kind", "ATTRIBUTE", "scopedName", "Demo.Topic.Thing.unexpected")));
    response.put("removed", List.of());
    response.put("changed", List.of());

    Map<String, Object> guarded =
        service.guardAddAttributeChange(response, "Demo.Topic.Thing.egid", null);

    assertThat(guarded.get("applied")).isEqualTo(false);
    assertThat(guarded.get("status")).isEqualTo("UNEXPECTED_SEMANTIC_CHANGE");
    assertThat(guarded).doesNotContainKey("updatedModelText");
    assertThat(guarded.get("candidateModelText")).isEqualTo("candidate");
  }

  @Test
  void preparedPatchReusesReviewWithExactlyTwoCompiles() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangeService service = service(compiler);
    String before = validModel();

    IliCompilerService.CompilationResult beforeCompilation =
        service.compileBefore(before, null);

    IliSourceDocument document = IliSourceDocument.of(before);
    int start = before.indexOf("TEXT*20");
    IliTextPatch patch = IliTextPatch.replace(
        document.span(start, start + "TEXT*20".length()),
        "TEXT*50",
        "Widen text length");

    IliModelChangeService.PreparedChange prepared =
        service.preparePatchedChange(document, List.of(patch));
    Map<String, Object> response = service.finalizePreparedChange(
        before,
        IliModelChangeOperation.ADD_ATTRIBUTE,
        beforeCompilation,
        prepared,
        null,
        null,
        null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(response.get("applied")).isEqualTo(true);
    assertThat(response.get("status")).isEqualTo("APPLIED");
    assertThat(response.get("updatedModelText").toString()).contains("TEXT*50");
    assertThat(response.get("impact")).isEqualTo("POTENTIALLY_BREAKING");
    assertThat(response.get("changed")).asList()
        .singleElement()
        .satisfies(change -> assertThat(change.toString())
            .contains("Demo.Topic.Thing.name")
            .contains("typeText")
            .contains("TEXT*20")
            .contains("TEXT*50"));
  }

  @Test
  void invalidAfterCandidateIsRejected() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangeService service = service(compiler);
    String before = validModel();
    IliCompilerService.CompilationResult beforeCompilation =
        service.compileBefore(before, null);

    IliSourceDocument document = IliSourceDocument.of(before);
    int start = before.indexOf("name : TEXT*20;");
    IliTextPatch patch = IliTextPatch.replace(
        document.span(start, start + "name : TEXT*20;".length()),
        "name TEXT*20;",
        "Break attribute syntax");

    IliModelChangeService.PreparedChange prepared =
        service.preparePatchedChange(document, List.of(patch));
    Map<String, Object> response = service.finalizePreparedChange(
        before,
        IliModelChangeOperation.ADD_ATTRIBUTE,
        beforeCompilation,
        prepared,
        null,
        null,
        null);

    assertThat(compiler.calls).isEqualTo(2);
    assertThat(response.get("applied")).isEqualTo(false);
    assertThat(response.get("status")).isEqualTo("AFTER_MODEL_INVALID");
    assertThat(response).doesNotContainKey("updatedModelText");
    assertThat(response.get("candidateModelText").toString()).contains("name TEXT*20;");
    assertThat(response.get("afterCompilerValid")).isEqualTo(false);
    assertThat(response.get("afterDiagnostics")).asList().isNotEmpty();
  }

  @Test
  void unchangedCandidateSkipsSecondCompile() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangeService service = service(compiler);
    String before = validModel();
    IliCompilerService.CompilationResult beforeCompilation =
        service.compileBefore(before, null);

    IliModelChangeService.PreparedChange prepared =
        service.preparePatchedChange(IliSourceDocument.of(before), List.of());
    Map<String, Object> response = service.finalizePreparedChange(
        before,
        IliModelChangeOperation.ADD_ATTRIBUTE,
        beforeCompilation,
        prepared,
        null,
        null,
        null);

    assertThat(compiler.calls).isEqualTo(1);
    assertThat(response.get("status")).isEqualTo("NO_CHANGE");
    assertThat(response.get("impact")).isEqualTo("NONE");
    assertThat(response.get("hasChanges")).isEqualTo(false);
  }

  @Test
  void invalidBeforeStopsWithoutAfterCompile() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangeService service = service(compiler);
    String before = invalidModel();
    IliCompilerService.CompilationResult beforeCompilation =
        service.compileBefore(before, null);

    IliModelChangeService.PreparedChange prepared =
        new IliModelChangeService.PreparedChange(before + "\n", List.of());
    Map<String, Object> response = service.finalizePreparedChange(
        before,
        IliModelChangeOperation.ADD_ATTRIBUTE,
        beforeCompilation,
        prepared,
        null,
        null,
        null);

    assertThat(compiler.calls).isEqualTo(1);
    assertThat(response.get("status")).isEqualTo("BEFORE_MODEL_INVALID");
    assertThat(response.get("beforeCompilerValid")).isEqualTo(false);
    assertThat(response.get("beforeDiagnostics")).asList().isNotEmpty();
  }

  private IliModelChangeService service(IliCompilerService compiler) {
    ModelAnalysisTools analysis = new ModelAnalysisTools(compiler);
    ModelingRuleTools rules =
        new ModelingRuleTools(new KnowledgeRuleLoader(), analysis, compiler);
    return new IliModelChangeService(
        compiler,
        new ModelChangeReviewService(analysis, rules));
  }

  private IliModelChangeRequest addTextAttribute(
      String containerFqn,
      String name,
      boolean mandatory,
      int length) {
    BaseType baseType = new BaseType();
    baseType.setKind(BaseType.Kind.TEXT);
    baseType.setLength(length);
    TypeSpec typeSpec = new TypeSpec();
    typeSpec.setBaseType(baseType);
    AttributeLineRequest attribute = new AttributeLineRequest();
    attribute.setName(name);
    attribute.setMandatory(mandatory);
    attribute.setTypeSpec(typeSpec);
    AddAttributeChange addAttribute = new AddAttributeChange();
    addAttribute.setContainerFqn(containerFqn);
    addAttribute.setAttribute(attribute);
    IliModelChangeRequest request = new IliModelChangeRequest();
    request.setOperation(IliModelChangeOperation.ADD_ATTRIBUTE);
    request.setAddAttribute(addAttribute);
    return request;
  }

  private String validModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-19" =
          TOPIC Topic =
            CLASS Thing =
              name : TEXT*20;
            END Thing;
          END Topic;
        END Demo.
        """;
  }

  private String emptyStructureModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-19" =
          TOPIC Topic =
            STRUCTURE Part =
            END Part;
          END Topic;
        END Demo.
        """;
  }

  private String inheritanceModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-19" =
          TOPIC Topic =
            CLASS Base =
              name : TEXT*20;
            END Base;
            CLASS Child EXTENDS Base =
            END Child;
          END Topic;
        END Demo.
        """;
  }

  private String invalidModel() {
    return validModel().replace("name : TEXT*20;", "name TEXT*20;");
  }

  private static class CountingCompiler extends IliCompilerService {
    private int calls;

    @Override
    public CompilationResult compile(
        String modelText,
        String modelRepositories,
        String tempPrefix) {
      calls++;
      return super.compile(modelText, modelRepositories, tempPrefix);
    }
  }
}
