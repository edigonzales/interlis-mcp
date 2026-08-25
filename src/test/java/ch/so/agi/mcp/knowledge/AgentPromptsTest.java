package ch.so.agi.mcp.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentPromptsTest {

  private final AgentPrompts prompts = new AgentPrompts();

  @Test
  void modelingAgentPrefersSemanticChangeAndHighLevelReviewTools() {
    String prompt = prompts.interlisModelingAgent().toString();

    assertThat(prompt)
        .contains("reviewIliModel")
        .contains("authorIliModel")
        .contains("applyIliModelChanges")
        .contains("Attribut-UPDATE-/REMOVE-")
        .contains("BREAKING_CHANGE_REQUIRES_CONFIRMATION")
        .contains("reviewIliChange")
        .contains("afterReview")
        .contains("findSimilarModels")
        .contains("readModelExample")
        .contains("Low-Level-Tools")
        .contains("MCP-Ausfallvertrag")
        .contains("einzeln und nacheinander")
        .contains("keine `.ili`-Datei schreiben")
        .contains("GeometryTypeSpec");
  }

  @Test
  void modelingAgentCombinesConstraintProofDiffAndModelReview() {
    String prompt = prompts.interlisModelingAgent().toString();

    assertThat(prompt)
        .contains("author-interlis-constraint")
        .contains("authorIliMandatoryConstraint")
        .contains("authorIliUniqueConstraint")
        .contains("authorIliExistenceConstraint")
        .contains("authorIliPlausibilityConstraint")
        .contains("authorIliSetConstraint")
        .contains("proofVerified=true")
        .contains("generationVerified=true")
        .contains("semantischen Diff und `afterReview`")
        .contains("kein redundantes")
        .contains("nicht nochmals")
        .contains("testIliConstraint")
        .contains("validateXtf");
  }

  @Test
  void reviewPromptUsesReviewIliModelAsMandatoryStep() {
    String prompt = prompts.reviewInterlisModel("PUBLICATION").toString();

    assertThat(prompt)
        .contains("1. Führe `reviewIliModel`")
        .contains("PUBLICATION")
        .contains("compilerDiagnostics")
        .contains("manualChecks")
        .contains("openQuestions")
        .doesNotContain("1. Führe `analyzeIliModel`");
  }

  @Test
  void extensionPromptPrefersSemanticChangeAndFallsBackToChangeReview() {
    String prompt = prompts.extendInterlisModel("CAPTURE").toString();

    assertThat(prompt)
        .contains("applyIliModelChanges")
        .contains("semantische Diff")
        .contains("APPLIED")
        .contains("reviewIliChange")
        .contains("potentiallyBreakingChanges")
        .contains("impact")
        .contains("afterReview")
        .contains("kein weiteres")
        .contains("readModelExample")
        .contains("proofVerified=true");
  }

  @Test
  void constraintAuthoringPromptRoutesAllFiveKindsAndDefinesFinalGates() {
    String prompt = prompts.authorInterlisConstraint("SET").toString();

    assertThat(prompt)
        .contains("SET")
        .contains("MANDATORY")
        .contains("UNIQUE")
        .contains("EXISTENCE")
        .contains("PLAUSIBILITY")
        .contains("authorIliMandatoryConstraint")
        .contains("authorIliUniqueConstraint")
        .contains("authorIliExistenceConstraint")
        .contains("authorIliPlausibilityConstraint")
        .contains("authorIliSetConstraint")
        .contains("generateIliConstraintCases")
        .contains("proofVerified=true")
        .contains("generationVerified=true")
        .contains("coverageUnsolved")
        .contains("afterReview")
        .contains("kein weiteres Modell-Level-Review")
        .contains("Fragmentbasierte Constraint-Authoring-Tools sind nicht öffentlich");
  }

  @Test
  void agentWorkflowResourceUsesTheSameHighLevelLoop() {
    String resource = new KnowledgeResources(null, null).agentWorkflow().toString();

    assertThat(resource)
        .contains("findSimilarModels")
        .contains("readModelExample")
        .contains("authorIliModel")
        .contains("applyIliModelChanges")
        .contains("reviewIliChange")
        .contains("afterReview")
        .contains("nicht zusaetzlich `reviewIliChange` oder `reviewIliModel`")
        .contains("nicht als Standard-Dreierfolge")
        .contains("MCP-Sicherheitsvertrag")
        .contains("Keine INTERLIS-Syntax erfinden")
        .contains("authorIliModel.updatedModelText")
        .contains("GeometryTypeSpec");
  }

  @Test
  void constraintWorkflowResourceDocumentsAuthoringProofAndReviewHierarchy() {
    String resource = new KnowledgeResources(null, null).constraintWorkflow().toString();

    assertThat(resource)
        .contains("interlis://knowledge/constraint-workflow")
        .contains("MANDATORY")
        .contains("UNIQUE")
        .contains("EXISTENCE")
        .contains("PLAUSIBILITY")
        .contains("SET")
        .contains("authorIliMandatoryConstraint")
        .contains("authorIliUniqueConstraint")
        .contains("authorIliExistenceConstraint")
        .contains("authorIliPlausibilityConstraint")
        .contains("authorIliSetConstraint")
        .contains("generateIliConstraintCases")
        .contains("proofVerified")
        .contains("generateIliConstraintCases")
        .contains("afterReview")
        .contains("Reason-Codes");
  }
}
