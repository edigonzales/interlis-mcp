package ch.so.agi.mcp.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class IliSourceLocatorTest {

  private final IliSourceLocator locator = new IliSourceLocator();

  @Test
  void locatesNamedBlocksAndIgnoresKeywordsInCommentsAndStrings() {
    IliSourceDocument document = IliSourceDocument.of(modelWithRepeatedClassNames());

    List<IliSourceLocator.BlockLocation> classes = locator.locateNamedBlocks(
        document, IliSourceLocator.BlockKind.CLASS, "Thing");

    assertThat(classes).hasSize(2);

    IliSourceLocator.BlockLocation first = locator.locateNamedBlock(
        document, IliSourceLocator.BlockKind.CLASS, "Thing", 7);
    assertThat(document.slice(first.headerSpan())).startsWith("CLASS Thing");
    assertThat(document.slice(first.endMarkerSpan())).isEqualTo("END Thing;");
    assertThat(document.slice(first.declarationSpan()))
        .contains("note : TEXT*20;")
        .contains("description :");
    assertThat(first.declarationSpan().endLine()).isEqualTo(12);
  }

  @Test
  void approximateLineDisambiguatesRepeatedSimpleNames() {
    IliSourceDocument document = IliSourceDocument.of(modelWithRepeatedClassNames());

    IliSourceLocator.BlockLocation first = locator.locateNamedBlock(
        document, IliSourceLocator.BlockKind.CLASS, "Thing", 7);
    IliSourceLocator.BlockLocation second = locator.locateNamedBlock(
        document, IliSourceLocator.BlockKind.CLASS, "Thing", 19);

    assertThat(first.declarationSpan().startLine()).isLessThan(second.declarationSpan().startLine());
    assertThat(document.slice(first.declarationSpan())).contains("description");
    assertThat(document.slice(second.declarationSpan())).contains("value : 0 .. 10;");

    assertThatThrownBy(() -> locator.locateNamedBlock(
        document, IliSourceLocator.BlockKind.CLASS, "Thing"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ambiguous");
  }

  @Test
  void locatesMultilineAttributeInsideSelectedContainer() {
    IliSourceDocument document = IliSourceDocument.of(modelWithRepeatedClassNames());
    IliSourceLocator.BlockLocation first = locator.locateNamedBlock(
        document, IliSourceLocator.BlockKind.CLASS, "Thing", 7);

    IliSourceSpan attribute = locator.locateAttribute(document, first, "description", 12);

    assertThat(document.slice(attribute)).isEqualTo("description :\n        TEXT*80;");
    assertThat(attribute.startLine()).isEqualTo(10);
    assertThat(attribute.endLine()).isEqualTo(11);
  }

  @Test
  void handlesNestedBlocksThatReuseTheSameSimpleName() {
    IliSourceDocument document = IliSourceDocument.of("""
        INTERLIS 2.4;
        MODEL Demo (de) AT "https://example.org" VERSION "2026-08-19" =
          TOPIC Same =
            CLASS Same =
              value : TEXT;
            END Same;
          END Same;
        END Demo.
        """);

    IliSourceLocator.BlockLocation topic = locator.locateNamedBlock(
        document, IliSourceLocator.BlockKind.TOPIC, "Same");
    IliSourceLocator.BlockLocation clazz = locator.locateNamedBlock(
        document, IliSourceLocator.BlockKind.CLASS, "Same");

    assertThat(clazz.declarationSpan().startOffset()).isGreaterThan(topic.declarationSpan().startOffset());
    assertThat(clazz.declarationSpan().endOffset()).isLessThan(topic.declarationSpan().endOffset());
  }

  private String modelWithRepeatedClassNames() {
    return """
        INTERLIS 2.4;
        MODEL Demo (de) AT "https://example.org" VERSION "2026-08-19" =
          TOPIC A =
            !! CLASS Thing = END Thing;
            CLASS Thing =
              /** CLASS Fake = END Fake; */
              note : TEXT*20; !! END Thing;
              text : TEXT*80;
              MANDATORY CONSTRAINT text <> "END Thing;";
              description :
                TEXT*80;
            END Thing;
          END A;
          TOPIC B =
            CLASS Thing =
              value : 0 .. 10;
            END Thing;
          END B;
        END Demo.
        """;
  }
}
