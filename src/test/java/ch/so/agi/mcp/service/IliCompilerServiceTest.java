package ch.so.agi.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

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

  static String minimalValidModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
        END Demo.
        """;
  }
}
