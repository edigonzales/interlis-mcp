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
      description = "Low-Level-Tool fuer reine ili2c-Compilerpruefung und gezielte Syntax-/Compiler-Reparatur. Rueckgabe: {valid:bool, messages:[{severity,file?,line?,message,sourceExcerpt?:{startLine,endLine,text}}]}. Prueft keine Modellierungsregeln und ersetzt keinen vollstaendigen Review; dafuer reviewIliModel."
  )
  public Map<String, Object> validateIliModel(
      @McpToolParam(description = "INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition, z. B. 'https://models.interlis.ch;https://geo.so.ch/models'", required = false)
      @Nullable String modelRepositories
  ) {
    IliCompilerService.CompilationResult result = compilerService.compile(modelText, modelRepositories);

    return Map.of(
        "valid", result.valid(),
        "messages", result.messages()
    );
  }
}
