package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.service.XtfService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class XtfTools {

  private final XtfService xtfService;

  public XtfTools() {
    this(new XtfService());
  }

  @Autowired
  public XtfTools(XtfService xtfService) {
    this.xtfService = xtfService;
  }

  @McpTool(
      name = "generateExampleXtf",
      description = "Erzeugt aus einem INTERLIS-Modell ein deterministisches Minimal-XTF. Rückgabe: {generated,xtfText?,messages,basketCount,objectCount,objectsByClass,skippedClasses}."
  )
  public Map<String, Object> generateExampleXtf(
      @McpToolParam(description = "INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition, z. B. 'https://models.interlis.ch;https://geo.so.ch/models'", required = false)
      @Nullable String modelRepositories,
      @McpToolParam(description = "Maximale Anzahl Objekte pro Klasse (Default 1)", required = false)
      @Nullable Integer maxObjectsPerClass) {
    XtfService.GenerateExampleResult result = xtfService.generateExampleXtf(modelText, modelRepositories, maxObjectsPerClass);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("generated", result.generated());
    if (result.xtfText() != null) {
      response.put("xtfText", result.xtfText());
    }
    response.put("messages", result.messages());
    response.put("basketCount", result.basketCount());
    response.put("objectCount", result.objectCount());
    response.put("objectsByClass", result.objectsByClass());
    response.put("skippedClasses", result.skippedClasses());
    return response;
  }

  @McpTool(
      name = "validateXtf",
      description = "Validiert XTF gegen INTERLIS-Modell via ilivalidator. Rückgabe: {valid,messages,errorCount,warningCount}."
  )
  public Map<String, Object> validateXtf(
      @McpToolParam(description = "INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "XTF-Inhalt als Text", required = true) String xtfText,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition, z. B. 'https://models.interlis.ch;https://geo.so.ch/models'", required = false)
      @Nullable String modelRepositories) {
    XtfService.ValidationResult result = xtfService.validateXtf(modelText, xtfText, modelRepositories);

    return Map.of(
        "valid", result.valid(),
        "messages", result.messages(),
        "errorCount", result.errorCount(),
        "warningCount", result.warningCount());
  }
}
