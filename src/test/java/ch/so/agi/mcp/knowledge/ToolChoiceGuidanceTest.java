package ch.so.agi.mcp.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelChangeTools;
import ch.so.agi.mcp.tools.ConstraintReviewTools;
import ch.so.agi.mcp.tools.ConstraintTestTools;
import ch.so.agi.mcp.tools.ValidationTools;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

class ToolChoiceGuidanceTest {

  @Test
  void highLevelReviewsAdvertiseDefaultUse() {
    assertThat(description(ModelingRuleTools.class, "reviewIliModel"))
        .contains("Standard-Tool", "Baseline- und Abschlussreview", "Nicht routinemaessig")
        .contains("analyzeIliModel", "checkModelingRules", "validateIliModel");

    assertThat(description(ModelChangeTools.class, "reviewIliChange"))
        .contains("Standard-Tool", "Vorher- und ein Nachher-Stand", "Nicht fuer einen einzelnen Modellstand")
        .contains("reviewIliModel");
  }

  @Test
  void constraintReviewAdvertisesItsScopeBoundary() {
    assertThat(description(ConstraintReviewTools.class, "reviewIliConstraint"))
        .contains("AST", "Kontext", "Funktionen", "String-Pfade", "Typen", "Edge Cases")
        .contains("keine Testdaten", "Witnesses", "Counterexamples");
  }

  @Test
  void constraintTestAdvertisesExplicitCasesAndScopeBoundary() {
    assertThat(description(ConstraintTestTools.class, "testIliConstraint"))
        .contains("explizit", "Testfaellen", "minimales XTF", "ilivalidator")
        .contains("keine Witnesses", "Counterexamples");
  }

  @Test
  void lowLevelToolsPointBackToHighLevelReview() {
    assertThat(description(ModelAnalysisTools.class, "analyzeIliModel"))
        .contains("Low-Level-Tool", "gezielte", "reviewIliModel");
    assertThat(description(ModelingRuleTools.class, "checkModelingRules"))
        .contains("Low-Level-Tool", "ruleIds", "reviewIliModel");
    assertThat(description(ValidationTools.class, "validateIliModel"))
        .contains("Low-Level-Tool", "Compiler", "reviewIliModel");
    assertThat(description(ModelingRuleTools.class, "listModelingRules"))
        .contains("Katalog-Tool", "Nicht zum Pruefen eines Modells");
  }

  @Test
  void exampleToolsEnforceFindThenReadWorkflow() {
    assertThat(description(ModelCorpusTools.class, "findSimilarModels"))
        .contains("Discovery-Tool", "readModelExample", "nicht nur aus Treffer-Metadaten modellieren");
    assertThat(description(ModelCorpusTools.class, "readModelExample"))
        .contains("vollstaendigen Quelltext", "nach findSimilarModels", "Nicht zum Lesen beliebiger Dateien");
    assertThat(description(ModelCorpusTools.class, "indexConfiguredModels"))
        .contains("Inventar-/Admin-Tool", "findSimilarModels");
  }

  @Test
  void toolGuideDocumentsTheDecisionHierarchy() {
    String guide = new KnowledgeResources(null, null).toolGuide().toString();

    assertThat(guide)
        .contains("interlis://knowledge/tool-guide")
        .contains("reviewIliModel")
        .contains("reviewIliChange")
        .contains("afterReview")
        .contains("kein")
        .contains("zusaetzliches `reviewIliModel`")
        .contains("findSimilarModels", "readModelExample")
        .contains("reviewIliConstraint", "testIliConstraint", "resolveConstraintPath", "listConstraintFunctions")
        .contains("expectedConstraintValid", "Fixture-Fehler")
        .contains("erfindet keine Testfaelle", "Witness-/Counterexample-Erzeugung")
        .contains("validateIliModel", "analyzeIliModel", "checkModelingRules")
        .contains("nicht standardmaessig");
  }

  private String description(Class<?> type, String toolName) {
    for (Method method : type.getDeclaredMethods()) {
      McpTool tool = method.getAnnotation(McpTool.class);
      if (tool != null && toolName.equals(tool.name())) {
        return tool.description();
      }
    }
    throw new AssertionError("MCP tool not found: " + toolName);
  }
}
