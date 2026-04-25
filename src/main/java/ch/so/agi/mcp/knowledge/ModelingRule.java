package ch.so.agi.mcp.knowledge;

import ch.so.agi.mcp.analysis.ModelPurpose;

public record ModelingRule(
    String id,
    String title,
    Severity severity,
    ModelPurpose appliesTo,
    CheckKind checkKind,
    String sourceUrl,
    String sourceSection,
    String rationale,
    String recommendation) {

  public enum Severity {
    INFO,
    WARNING,
    ERROR
  }

  public enum CheckKind {
    AUTOMATED,
    MANUAL
  }
}

