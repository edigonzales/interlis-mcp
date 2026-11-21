package ch.so.agi.mcp.change;

import ch.so.agi.mcp.change.IliSourceLocator.BlockLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class IliSourceDocument {

  private final String text;
  private final int[] lineStarts;
  private final String lineSeparator;

  private IliSourceDocument(String text) {
    this.text = Objects.requireNonNull(text, "text");
    this.lineStarts = computeLineStarts(text);
    this.lineSeparator = detectLineSeparator(text);
  }

  public static IliSourceDocument of(String text) {
    return new IliSourceDocument(text);
  }

  public String text() {
    return text;
  }

  public int length() {
    return text.length();
  }

  public int lineCount() {
    return lineStarts.length;
  }

  public String lineSeparator() {
    return lineSeparator;
  }

  public int lineStartOffset(int line) {
    requireLine(line);
    return lineStarts[line - 1];
  }

  public int lineEndOffset(int line) {
    requireLine(line);
    int end = line == lineStarts.length ? text.length() : lineStarts[line];
    while (end > lineStarts[line - 1]) {
      char previous = text.charAt(end - 1);
      if (previous != '\r' && previous != '\n') {
        break;
      }
      end--;
    }
    return end;
  }

  public String lineText(int line) {
    return text.substring(lineStartOffset(line), lineEndOffset(line));
  }

  public int lineOfOffset(int offset) {
    requireOffset(offset);
    int low = 0;
    int high = lineStarts.length - 1;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      if (lineStarts[mid] <= offset) {
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    return high + 1;
  }

  public IliSourceSpan span(int startOffset, int endOffset) {
    requireOffset(startOffset);
    requireOffset(endOffset);
    if (endOffset < startOffset) {
      throw new IllegalArgumentException("endOffset must be >= startOffset.");
    }
    int startLine = lineOfOffset(startOffset);
    int endLine = endOffset == startOffset ? startLine : lineOfOffset(endOffset - 1);
    return new IliSourceSpan(startOffset, endOffset, startLine, endLine);
  }

  public IliSourceSpan insertionPoint(int offset) {
    return span(offset, offset);
  }

  public String slice(IliSourceSpan span) {
    Objects.requireNonNull(span, "span");
    if (span.endOffset() > text.length()) {
      throw new IllegalArgumentException("Span exceeds document length.");
    }
    return text.substring(span.startOffset(), span.endOffset());
  }

  public String leadingIndent(int line) {
    int start = lineStartOffset(line);
    return text.substring(start, skipHorizontalSpace(start));
  }

  public String bodyIndent(BlockLocation block) {
    return leadingIndent(block.headerSpan().startLine()) + "  ";
  }

  public IliTextPatch insertAfter(
      IliSourceSpan span, String fragment, String indent, String description) {
    int offset = skipHorizontalSpace(span.endOffset());
    int line = span.endLine();
    boolean trailingComment = text.startsWith("!!", offset) && !text.startsWith("!!@", offset);
    if (line < lineCount() && (offset == lineEndOffset(line) || trailingComment)) {
      offset = lineStartOffset(line + 1);
    }
    return insertLines(offset, fragment, indent, "", description);
  }

  public IliTextPatch insertBeforeEnd(
      BlockLocation block, String fragment, boolean blankLine, String description) {
    // Trailing annotations must not attach to the inserted declaration.
    int offset = block.endPreludeOffset();
    int lineStart = lineStartOffset(lineOfOffset(offset));
    if (skipHorizontalSpace(lineStart) == offset) {
      return insertLines(lineStart, fragment, bodyIndent(block), blankLine ? lineSeparator : "", description);
    }
    return insertLines(offset, fragment, bodyIndent(block),
        leadingIndent(block.headerSpan().startLine()), description);
  }

  private IliTextPatch insertLines(
      int offset, String fragment, String indent, String suffix, String description) {
    String prefix = offset == lineStartOffset(lineOfOffset(offset)) ? "" : lineSeparator;
    return IliTextPatch.insert(this, offset,
        prefix + indentLines(fragment, indent, lineSeparator) + lineSeparator + suffix, description);
  }

  public static String indentLines(String fragment, String indent, String lineSeparator) {
    return fragment.lines().map(line -> indent + line).collect(Collectors.joining(lineSeparator));
  }

  private int skipHorizontalSpace(int offset) {
    while (offset < text.length() && (text.charAt(offset) == ' ' || text.charAt(offset) == '\t')) offset++;
    return offset;
  }

  private void requireLine(int line) {
    if (line < 1 || line > lineStarts.length) {
      throw new IllegalArgumentException("Line out of range: " + line);
    }
  }

  private void requireOffset(int offset) {
    if (offset < 0 || offset > text.length()) {
      throw new IllegalArgumentException("Offset out of range: " + offset);
    }
  }

  private static int[] computeLineStarts(String text) {
    List<Integer> starts = new ArrayList<>();
    starts.add(0);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\r') {
        if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
        starts.add(i + 1);
      } else if (c == '\n') {
        starts.add(i + 1);
      }
    }
    return starts.stream().mapToInt(Integer::intValue).toArray();
  }

  private static String detectLineSeparator(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\r') {
        return i + 1 < text.length() && text.charAt(i + 1) == '\n' ? "\r\n" : "\r";
      }
      if (c == '\n') {
        return "\n";
      }
    }
    return "\n";
  }
}
