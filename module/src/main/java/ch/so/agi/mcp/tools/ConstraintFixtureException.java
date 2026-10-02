package ch.so.agi.mcp.tools;

/** A known fixture boundary, returned as proof diagnostics rather than an MCP call error. */
final class ConstraintFixtureException extends IllegalArgumentException {
  private final String reasonCode;

  ConstraintFixtureException(String reasonCode, String message) {
    super(message);
    this.reasonCode = reasonCode;
  }

  String reasonCode() { return reasonCode; }
}
