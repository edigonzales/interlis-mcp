package ch.so.agi.mcp.change;

import ch.so.agi.mcp.analysis.ModelPurpose;
import ch.so.agi.mcp.knowledge.ModelingRuleProfile;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class IliModelChangeTools {

  private final IliModelChangeService changeService;

  public IliModelChangeTools(IliModelChangeService changeService) {
    this.changeService = changeService;
  }

  @McpTool(
      name = "applyIliModelChange",
      description = "Standard-Tool fuer unterstuetzte semantische Aenderungen an einem bestehenden vollstaendigen INTERLIS-Modell. Fuehrt die Aenderung source-preserving aus, kompiliert Vorher und Nachher hoechstens einmal und liefert den semantischen Diff sowie afterReview als Abschlussgate. Aktuell wird ADD_ATTRIBUTE fuer CLASS und STRUCTURE unterstuetzt. Bei erfolgreichem APPLIED-Resultat nicht routinemaessig noch reviewIliChange fuer denselben unveraenderten Nachher-Stand aufrufen. Fuer noch nicht unterstuetzte Aenderungen den Modelltext direkt bearbeiten und mit reviewIliChange abschliessen."
  )
  public Map<String, Object> applyIliModelChange(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext vor der Aenderung", required = true)
      String modelText,
      @McpToolParam(description = "Typisierte Aenderung. ADD_ATTRIBUTE benoetigt addAttribute.containerFqn und addAttribute.attribute.", required = true)
      IliModelChangeRequest request,
      @McpToolParam(description = "Modellzweck: CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false)
      @Nullable ModelPurpose modelPurpose,
      @McpToolParam(description = "Regelprofil: CORE oder SO (Default CORE)", required = false)
      @Nullable ModelingRuleProfile ruleProfile) {
    return changeService.apply(modelText, request, null, modelPurpose, ruleProfile);
  }
}
