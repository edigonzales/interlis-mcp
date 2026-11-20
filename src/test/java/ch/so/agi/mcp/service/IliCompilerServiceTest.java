package ch.so.agi.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.so.agi.mcp.constraint.ConstraintPathAnalysis;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IliCompilerServiceTest {

  private static final int PARALLEL_ROUNDS = 50;
  private static final String PATH_CONTEXT = "PathModel.Data.Item";
  private static final String MISSING_PATH = "missingFromPathModel";
  private static final String BROKEN_DOMAIN = "MissingFromBrokenModel";
  private static final String PATH_MODEL = """
      INTERLIS 2.3;
      MODEL PathModel (de) AT "https://example.org" VERSION "2024-01-31" =
        TOPIC Data =
          CLASS Item =
            label : TEXT*20;
          END Item;
        END Data;
      END PathModel.
      """;

  private final IliCompilerService service = new IliCompilerService();

  @Test
  void isolatesValidCompilationsFromConcurrentInvalidPaths() throws Exception {
    var pathModel = service.compile(PATH_MODEL, ".").transferDescription();
    assertThat(pathModel).isNotNull();
    var start = new CyclicBarrier(2);
    var executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
    try {
      var compilations = executor.submit(repeatTogether(start, () -> service.compile(minimalValidModel(), ".")));
      var paths = executor.submit(repeatTogether(start, () -> checkInvalidPaths(pathModel)));

      paths.get(20, TimeUnit.SECONDS);
      assertThat(compilations.get(20, TimeUnit.SECONDS)).allSatisfy(IliCompilerServiceTest::assertValidCompilation);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void keepsDiagnosticsLocalAcrossConcurrentValidAndInvalidCompilationsAndPaths() throws Exception {
    var pathModel = service.compile(PATH_MODEL.replace("PathModel", "LookupModel"), ".").transferDescription();
    var invalidPathModel = service.compile(PATH_MODEL, ".").transferDescription();
    assertThat(pathModel).isNotNull();
    assertThat(invalidPathModel).isNotNull();
    String brokenModel = PATH_MODEL.replace("PathModel", "BrokenModel").replace("TEXT*20", BROKEN_DOMAIN);
    var start = new CyclicBarrier(4);
    var executor = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().factory());
    try {
      var validModels = executor.submit(repeatTogether(start, () -> service.compile(minimalValidModel(), ".")));
      var invalidModels = executor.submit(repeatTogether(start, () -> service.compile(brokenModel, ".")));
      var validPaths = executor.submit(repeatTogether(start, () ->
          ConstraintPathAnalysis.resolveCompiledPath(pathModel, "LookupModel.Data.Item", "label")));
      var invalidPaths = executor.submit(repeatTogether(start, () -> checkInvalidPaths(invalidPathModel)));

      assertThat(validPaths.get(20, TimeUnit.SECONDS)).allSatisfy(result -> {
        assertThat(result).containsEntry("valid", true).containsEntry("path", "label")
            .containsEntry("context", "LookupModel.Data.Item").doesNotContainKeys("message", "failedSegment", "candidates");
        assertThat(result.toString()).doesNotContain("Demo", "BrokenModel", "PathModel", MISSING_PATH);
      });
      invalidPaths.get(20, TimeUnit.SECONDS);
      assertThat(invalidModels.get(20, TimeUnit.SECONDS)).allSatisfy(result -> {
        assertThat(result.valid()).isFalse();
        assertThat(result.transferDescription()).isNull();
        assertThat(result.messages()).anySatisfy(message -> {
          assertThat(message).containsEntry("severity", "ERROR").containsEntry("file", "<submitted-model>");
          assertThat(message.get("message").toString()).contains(BROKEN_DOMAIN);
        });
        assertThat(result.messages().toString()).doesNotContain("Demo", "PathModel", "LookupModel", MISSING_PATH);
      });
      assertThat(validModels.get(20, TimeUnit.SECONDS)).allSatisfy(IliCompilerServiceTest::assertValidCompilation);
    } finally {
      executor.shutdownNow();
    }
  }

  private static <T> Callable<List<T>> repeatTogether(CyclicBarrier start, Callable<T> action) {
    return () -> {
      List<T> results = new ArrayList<>();
      for (int round = 0; round < PARALLEL_ROUNDS; round++) {
        start.await(10, TimeUnit.SECONDS);
        results.add(action.call());
      }
      return results;
    };
  }

  private static Void checkInvalidPaths(TransferDescription model) {
    // Path resolution is faster than compilation. Keep it active while the compiler opens its source file.
    for (int attempt = 0; attempt < 8; attempt++) {
      assertInvalidPath(ConstraintPathAnalysis.resolveCompiledPath(model, PATH_CONTEXT, MISSING_PATH));
    }
    return null;
  }

  private static void assertInvalidPath(Map<String, Object> result) {
    assertThat(result).containsEntry("valid", false).containsEntry("context", PATH_CONTEXT)
        .containsEntry("path", MISSING_PATH).containsEntry("failedSegment", MISSING_PATH)
        .containsEntry("failedSegmentIndex", 0)
        .containsEntry("candidates", List.of(Map.of("name", "label", "kind", "ATTRIBUTE")))
        .containsKey("message");
    assertThat(result.get("message").toString()).isNotBlank();
    assertThat(result.toString()).doesNotContain("Demo", "BrokenModel", "LookupModel");
  }

  private static void assertValidCompilation(IliCompilerService.CompilationResult result) {
    assertThat(result.messages()).isEmpty();
    assertThat(result.valid()).isTrue();
    assertThat(result.transferDescription()).isNotNull();
  }

  @Test
  void compilesMinimalValidModel() {
    IliCompilerService.CompilationResult result = service.compile(minimalValidModel(), null);

    assertThat(result.valid()).isTrue();
    assertThat(result.messages()).isEmpty();
    assertThat(result.transferDescription()).isNotNull();
  }

  @Test
  void returnsMessagesForInvalidModelWithoutThrowing() {
    IliCompilerService.CompilationResult result = service.compile("INTERLIS 2.4;\nMODEL Broken =\n", null);

    assertThat(result.valid()).isFalse();
    assertThat(result.transferDescription()).isNull();
    assertThat(result.messages()).isNotEmpty();
  }

  @Test
  void addsSourceExcerptAroundCompilerDiagnostic() {
    String modelText = """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          TOPIC Topic =
            CLASS Thing =
              name TEXT*20;
            END Thing;
          END Topic;
        END Demo.
        """;

    IliCompilerService.CompilationResult result = service.compile(modelText, null);

    assertThat(result.valid()).isFalse();
    Map<String, Object> diagnostic = result.messages().stream()
        .filter(message -> message.containsKey("sourceExcerpt"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected a compiler diagnostic with sourceExcerpt: " + result.messages()));

    assertThat(diagnostic.get("line")).isInstanceOf(Number.class);
    assertThat(diagnostic.get("sourceExcerpt")).isInstanceOf(Map.class);
    assertThat(diagnostic.get("file")).isEqualTo("<submitted-model>");
    @SuppressWarnings("unchecked")
    Map<String, Object> excerpt = (Map<String, Object>) diagnostic.get("sourceExcerpt");
    int line = ((Number) diagnostic.get("line")).intValue();
    assertThat(((Number) excerpt.get("startLine")).intValue()).isLessThanOrEqualTo(line);
    assertThat(((Number) excerpt.get("endLine")).intValue()).isGreaterThanOrEqualTo(line);
    assertThat(excerpt.get("text").toString())
        .contains("CLASS Thing =")
        .contains("name TEXT*20;")
        .contains("END Thing;");
  }

  @Test
  void rejectsOversizedModelBeforeCreatingCompilerState() {
    String oversized = "x".repeat(2 * 1024 * 1024 + 1);

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> service.compile(oversized, null));
  }

  static String minimalValidModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
        END Demo.
        """;
  }
}
