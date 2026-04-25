package ch.so.agi.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class XtfServiceTest {

  private final XtfService service = new XtfService(new IliCompilerService());

  @Test
  void generateExampleXtfCreatesMinimalValidTransfer() {
    XtfService.GenerateExampleResult generated = service.generateExampleXtf(minimalMandatoryTextModel(), null, 1);

    assertThat(generated.generated()).isTrue();
    assertThat(generated.xtfText()).isNotBlank();
    assertThat(generated.basketCount()).isGreaterThanOrEqualTo(1);
    assertThat(generated.objectCount()).isGreaterThanOrEqualTo(1);
    assertThat(generated.objectsByClass()).isNotEmpty();
    assertThat(generated.skippedClasses()).isEmpty();

    XtfService.ValidationResult validated = service.validateXtf(minimalMandatoryTextModel(), generated.xtfText(), null);
    assertThat(validated.valid()).isTrue();
    assertThat(validated.errorCount()).isZero();
  }

  @Test
  void generateExampleXtfSkipsClassWithUnsupportedMandatoryAttributeType() {
    XtfService.GenerateExampleResult generated = service.generateExampleXtf(modelWithMandatoryStructureAttribute(), null, 1);

    assertThat(generated.generated()).isFalse();
    assertThat(generated.objectCount()).isZero();
    assertThat(generated.skippedClasses()).isNotEmpty();
    assertThat(generated.skippedClasses().getFirst())
        .containsEntry("classFqn", "Demo.Topic.Building")
        .containsKey("reason");
    assertThat(String.valueOf(generated.skippedClasses().getFirst().get("reason")))
        .contains("unsupported mandatory attribute 'details'");
  }

  @Test
  void validateXtfReturnsInvalidForBrokenMandatoryPayload() {
    XtfService.GenerateExampleResult generated = service.generateExampleXtf(minimalMandatoryTextModel(), null, 1);
    assertThat(generated.generated()).isTrue();
    assertThat(generated.xtfText()).isNotBlank();

    String brokenXtf = "<TRANSFER>";
    XtfService.ValidationResult validated = service.validateXtf(minimalMandatoryTextModel(), brokenXtf, null);

    assertThat(validated.valid()).isFalse();
    assertThat(validated.errorCount()).isGreaterThan(0);
    assertThat(validated.messages()).isNotEmpty();
  }

  private static String minimalMandatoryTextModel() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          TOPIC Topic =
            CLASS Building =
              name : MANDATORY TEXT*20;
            END Building;
          END Topic;
        END Demo.
        """;
  }

  private static String modelWithMandatoryStructureAttribute() {
    return """
        INTERLIS 2.4;

        MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
          TOPIC Topic =
            STRUCTURE BuildingDetails =
              note : TEXT*20;
            END BuildingDetails;

            CLASS Building =
              details : MANDATORY BuildingDetails;
            END Building;
          END Topic;
        END Demo.
        """;
  }
}
