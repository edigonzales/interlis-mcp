package ch.so.agi.mcp.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class IliPatchApplierTest {

  @Test
  void appliesMultiplePatchesAgainstOriginalOffsetsWithoutChangingLineEndings() {
    IliSourceDocument document = IliSourceDocument.of("A\r\nB\r\nC\r\n");
    IliTextPatch replaceB = IliTextPatch.replace(document.span(3, 4), "Bee", "replace B");
    IliTextPatch replaceC = IliTextPatch.replace(document.span(6, 7), "See", "replace C");

    String updated = IliPatchApplier.apply(document, List.of(replaceB, replaceC));

    assertThat(updated).isEqualTo("A\r\nBee\r\nSee\r\n");
    assertThat(updated).contains("\r\n");
    assertThat(updated).doesNotContain("\nBee\n");
  }

  @Test
  void supportsZeroWidthInsertionPoints() {
    IliSourceDocument document = IliSourceDocument.of("AB");

    String updated = IliPatchApplier.apply(
        document,
        List.of(IliTextPatch.insert(document, 1, "-", "separator")));

    assertThat(updated).isEqualTo("A-B");
  }

  @Test
  void rejectsOverlappingOrAmbiguousPatches() {
    IliSourceDocument document = IliSourceDocument.of("abcdef");

    assertThatThrownBy(() -> IliPatchApplier.apply(document, List.of(
        IliTextPatch.replace(document.span(1, 4), "x", "first"),
        IliTextPatch.replace(document.span(3, 5), "y", "second"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("overlap");

    assertThatThrownBy(() -> IliPatchApplier.apply(document, List.of(
        IliTextPatch.insert(document, 2, "x", "first"),
        IliTextPatch.insert(document, 2, "y", "second"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ambiguous");
  }
}
