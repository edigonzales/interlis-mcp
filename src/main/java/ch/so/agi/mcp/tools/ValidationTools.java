package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.service.IliCompilerService;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ValidationTools {

  private final IliCompilerService compilerService;

  public ValidationTools(IliCompilerService compilerService) {
    this.compilerService = compilerService;
  }

  @McpTool(
      name = "validateIliModel",
      description = "Prüft Modelltext mit ili2c und liefert Compilerdiagnosen mit Quelltextausschnitten.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true)
  )
  public Map<String, Object> validateIliModel(
      @McpToolParam(description = "INTERLIS-2 Modelltext", required = true) String modelText
  ) {
    IliCompilerService.CompilationResult result = compilerService.compile(modelText, null);

    return Map.of(
        "valid", result.valid(),
        "messages", result.messages()
    );
  }
}
