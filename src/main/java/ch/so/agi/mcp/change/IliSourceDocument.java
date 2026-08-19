package ch.so.agi.mcp.change;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
