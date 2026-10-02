package ch.so.agi.mcp.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** A deterministic, request-local contract error; paths are RFC 6901 JSON pointers. */
public final class SpecValidationException extends IllegalArgumentException {
  private final IliAuthoringResult.SpecDiagnostic diagnostic;

  public SpecValidationException(String code, String path, String message, @Nullable String hint) {
    super(path + ": " + message + (hint == null ? "" : " " + hint));
    diagnostic = new IliAuthoringResult.SpecDiagnostic(code, path, message, hint);
  }

  public IliAuthoringResult.SpecDiagnostic diagnostic() { return diagnostic; }

  public static IliAuthoringResult attach(IliAuthoringResult result, IllegalArgumentException error) {
    if (error instanceof SpecValidationException specError) {
      result.specDiagnostics = List.of(specError.diagnostic());
    }
    return result;
  }
}
