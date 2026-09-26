package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.ModelHashes;
import ch.so.agi.mcp.constraint.ConstraintAnalysisService;
import ch.so.agi.mcp.constraint.ConstraintExplanation;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ConstraintReviewTools {

  private final IliCompilerService compilerService;

  public ConstraintReviewTools(IliCompilerService compilerService, ConstraintKnowledgeTools knowledgeTools) {
    this.compilerService = compilerService;
  }

  @McpTool(
      name = "reviewIliConstraint",
      description = "Erklaert und prueft einen bestehenden INTERLIS-Constraint aus einem vollstaendigen Modell. Liefert compilerbasierten AST, Kontext, referenzierte Elemente, Funktionen, String-Pfade, Typen und strukturelle Edge Cases. Enthält explanation (COMPLETE/PARTIAL/UNAVAILABLE); Erklärumfang ist keine fachliche Abnahme. Erzeugt keine Testdaten, Witnesses oder Counterexamples.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true)
  )
  public Map<String, Object> reviewIliConstraint(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Constraint-Name oder vollqualifizierter Constraint-Name", required = true) String constraint) {
    return ModelHashes.attach(reviewIliConstraintFull(modelText, constraint), ModelHashes.model(modelText));
  }

  private Map<String, Object> reviewIliConstraintFull(String modelText, String constraint) {
    IliCompilerService.CompilationResult compilation =
        compilerService.compile(modelText, null, "ili2c_constraint_review_");
    if (!compilation.valid() || compilation.transferDescription() == null) {
      return Map.of(
          "valid", false,
          "compilerValid", false,
          "messages", compilation.messages(),
          "reviewStatus", "UNAVAILABLE",
          "reason", "The model must compile before a constraint can be reviewed.",
          "explanation", ConstraintExplanation.unavailable(constraint),
          "limitations", List.of("No compiled constraint available."));
    }

    Map<String, Object> result = new ConstraintAnalysisService()
        .reviewCompiled(compilation.transferDescription(), constraint);
    result.put("messages", compilation.messages());
    return result;
  }
}
