package ch.so.agi.mcp.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelChangeTools;
import ch.so.agi.mcp.change.IliModelChangeTools;
import ch.so.agi.mcp.tools.ConstraintAuthoringTools;
import ch.so.agi.mcp.tools.ConstraintCaseGenerationTools;
import ch.so.agi.mcp.tools.ConstraintDecisionTableTools;
import ch.so.agi.mcp.tools.ConstraintReviewTools;
import ch.so.agi.mcp.tools.ConstraintTestTools;
import ch.so.agi.mcp.tools.ExistenceConstraintAuthoringTools;
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
  void semanticChangeToolAdvertisesSupportedScopeAndFinalGate() {
    assertThat(description(IliModelChangeTools.class, "applyIliModelChange"))
        .contains("Standard-Tool", "source-preserving", "ADD_ATTRIBUTE", "CLASS", "STRUCTURE")
        .contains("afterReview", "APPLIED", "reviewIliChange");
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
  void automaticConstraintCasesAdvertiseMandatoryUniqueExistenceAndPlausibilityScope() {
    assertThat(description(ConstraintCaseGenerationTools.class, "generateIliConstraintCases"))
        .contains("Mandatory", "UNIQUE", "EXISTENCE", "PLAUSIBILITY")
        .contains("Witness", "Counterexample", "Boundary-/Kategoriefaelle")
        .contains("semantische IR", "Solver", "Object-Graph-Synthese")
        .contains("einmal kompilierten Constraint-Kontext", "Validator-Fixtures")
        .contains("WHERE", "(BASKET)", "LOCAL", "STRUCTURE", "COMPOSITION", "ilivalidator")
        .contains("REFERENCE", "COORD", "Safety-Reason-Codes")
        .contains("Populationen", "Prozentgrenze");
  }

  @Test
  void mandatoryAuthoringAdvertisesTypedRoundTripSourcePreservationAndValidatorProof() {
    assertThat(description(ConstraintAuthoringTools.class, "authorIliMandatoryConstraint"))
        .contains("Mandatory Constraint", "semantischen Node-Liste")
        .contains("source-preserving", "Before und After kompiliert")
        .contains("AST->IR", "Coverage", "Solver", "Object-Graph", "ilivalidator")
        .contains("ATTRIBUTE", "PATH", "FUNCTION", "COMPARE");
  }

  @Test
  void plausibilityAuthoringAdvertisesPopulationSemanticsAndTwoCompileRoundTrip() {
    assertThat(description(ConstraintAuthoringTools.class, "authorIliPlausibilityConstraint"))
        .contains("PLAUSIBILITY", "direction", "percentage", "semantischen Node-Liste")
        .contains("source-preserving", "Before/After je genau einmal")
        .contains("constraint-level IR", "Mehrfachobjekt", "ilivalidator");
  }

  @Test
  void existenceAuthoringRequiresExplicitTargetsAndVerifiedRoundTrip() {
    assertThat(description(ExistenceConstraintAuthoringTools.class, "authorIliExistenceConstraint"))
        .contains("EXISTENCE CONSTRAINT", "restrictedPath", "viewableFqn", "attributePath")
        .contains("source-preserving", "Before/After je genau einmal")
        .contains("AST->constraint-level-IR", "NUMERIC", "TEXT", "ilivalidator");
  }

  @Test
  void decisionTableToolAdvertisesConstraintBoundaryAndCategoryProof() {
    assertThat(description(ConstraintDecisionTableTools.class, "generateIliConstraintFromDecisionTable"))
        .contains("Entscheidungstabelle", "Mandatory Constraint", "Boundary-/Kategoriefaelle")
        .contains("testIliConstraint", "ilivalidator")
        .contains("BOOLEAN", "ENUM", "SUM", "Association-Pfad")
        .contains("DEFINED", "SUM plus direktes NUMERIC-Attribut");
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
        .contains("applyIliModelChange")
        .contains("ADD_ATTRIBUTE")
        .contains("UNEXPECTED_SEMANTIC_CHANGE")
        .contains("reviewIliChange")
        .contains("afterReview")
        .contains("STRUCTURE")
        .contains("EXISTENCE_REFERENCE_VALUE_PROOF_UNSAFE")
        .contains("authorIliPlausibilityConstraint")
        .contains("UNDEFINED_COUNTS_AS_SUCCESS")
        .contains("SET")
        .contains("Constraint");
  }

  private String description(Class<?> type, String methodName) {
    for (Method method : type.getDeclaredMethods()) {
      if (!method.getName().equals(methodName)) {
        continue;
      }
      McpTool annotation = method.getAnnotation(McpTool.class);
      if (annotation == null) {
        throw new AssertionError("Missing @McpTool on " + type.getSimpleName() + "." + methodName);
      }
      return annotation.description();
    }
    throw new AssertionError("Method not found: " + type.getSimpleName() + "." + methodName);
  }
}
