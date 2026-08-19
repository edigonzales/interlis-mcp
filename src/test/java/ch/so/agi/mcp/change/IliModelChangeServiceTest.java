package ch.so.agi.mcp.change;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.knowledge.KnowledgeRuleLoader;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IliModelChangeServiceTest {

  @Test
  void unsupportedOperationDoesNotCompile() {
    CountingCompiler compiler = new CountingCompiler();
    IliModelChangeService service = service(compiler);
    IliModelChangeRequest request = new IliModelChangeRequest();
    request.setOperation(IliModelChangeOperation.ADD_ATTRIBUTE);

    Map<String, Object> response = service.apply(validModel(), request, null, null, null);

    assertThat(response.get("applied")).isEqualTo(false);
    assertThat(response.get("status")).isEqualTo("UNSUPPORTED_OPERATION");
    assertThat(response.get("operation")).isEqualTo("ADD_ATTRIBUTE");
    assertThat(compiler.calls).isZero();
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
    assertThat(response.get("sourceEdits")).asList()
        .singleElement()
        .satisfies(edit -> assertThat(edit.toString())
            .contains("TEXT*20")
            .contains("TEXT*50")
            .contains("Widen text length"));
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
