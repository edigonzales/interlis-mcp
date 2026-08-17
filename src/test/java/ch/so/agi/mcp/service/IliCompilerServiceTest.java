package ch.so.agi.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class IliCompilerServiceTest {

  private final IliCompilerService service = new IliCompilerService();

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

  static String minimalValidModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
        END Demo.
        """;
  }
}
