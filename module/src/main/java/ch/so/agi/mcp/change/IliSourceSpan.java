package ch.so.agi.mcp.change;

public record IliSourceSpan(
    int startOffset,
    int endOffset,
    int startLine,
    int endLine) {

  public IliSourceSpan {
    if (startOffset < 0) {
      throw new IllegalArgumentException("startOffset must be >= 0.");
    }
    if (endOffset < startOffset) {
      throw new IllegalArgumentException("endOffset must be >= startOffset.");
    }
    if (startLine < 1) {
      throw new IllegalArgumentException("startLine must be >= 1.");
    }
    if (endLine < startLine) {
      throw new IllegalArgumentException("endLine must be >= startLine.");
    }
  }

  public int length() {
    return endOffset - startOffset;
  }

  public boolean isEmpty() {
    return startOffset == endOffset;
  }
}
