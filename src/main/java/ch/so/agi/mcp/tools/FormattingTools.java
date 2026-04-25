package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.so.agi.mcp.service.IliCompilerService;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class FormattingTools {

  private final IliCompilerService compilerService;

  public FormattingTools() {
    this(new IliCompilerService());
  }

  @Autowired
  public FormattingTools(IliCompilerService compilerService) {
    this.compilerService = compilerService;
  }

  @McpTool(
      name = "formatIliModel",
      description = "Formatiert (pretty print) ein INTERLIS-2 Modell mit dem offiziellen ili2c-Formatter. Rückgabe: vollständig formatiertes Modell als Text."
  )
  public String formatIliModel(
      @McpToolParam(description = "INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition, z. B. 'https://models.interlis.ch;https://geo.so.ch/models'", required = false)
      @Nullable String modelRepositories
  ) {
    TransferDescription td = compilerService.compileOrThrow(modelText, modelRepositories, "formatting");
    return compilerService.generateModelsFromLastFile(td);
  }
}
