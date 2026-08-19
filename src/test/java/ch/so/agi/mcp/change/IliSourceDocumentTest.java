package ch.so.agi.mcp.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IliSourceDocumentTest {

  @Test
  void tracksCrLfLinesAndHalfOpenSpans() {
    IliSourceDocument document = IliSourceDocument.of("a\r\nbb\r\nc");

    assertThat(document.lineSeparator()).isEqualTo("\r\n");
    assertThat(document.lineCount()).isEqualTo(3);
    assertThat(document.lineStartOffset(1)).isZero();
    assertThat(document.lineStartOffset(2)).isEqualTo(3);
    assertThat(document.lineStartOffset(3)).isEqualTo(7);
    assertThat(document.lineEndOffset(2)).isEqualTo(5);
    assertThat(document.lineText(2)).isEqualTo("bb");
    assertThat(document.lineOfOffset(3)).isEqualTo(2);
    assertThat(document.lineOfOffset(document.length())).isEqualTo(3);

    IliSourceSpan span = document.span(3, 5);
    assertThat(span.startLine()).isEqualTo(2);
    assertThat(span.endLine()).isEqualTo(2);
    assertThat(document.slice(span)).isEqualTo("bb");
  }

  @Test
  void defaultsToLfWhenDocumentHasNoLineBreak() {
    IliSourceDocument document = IliSourceDocument.of("INTERLIS 2.4;");

    assertThat(document.lineSeparator()).isEqualTo("\n");
    assertThat(document.lineCount()).isEqualTo(1);
  }

  @Test
  void rejectsOutOfRangeSpans() {
    IliSourceDocument document = IliSourceDocument.of("abc");

    assertThatThrownBy(() -> document.span(0, 4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Offset out of range");
  }
}
