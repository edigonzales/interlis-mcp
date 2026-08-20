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
        .contains("applyIliModelChange")
        .contains("ADD_ATTRIBUTE")
        .contains("reviewIliChange")
        .contains("afterReview")
        .contains("findSimilarModels")
        .contains("readModelExample")
        .contains("Low-Level-Tools");
  }

  @Test
  void modelingAgentSeparatesConstraintProofFromModelChangeReview() {
    String prompt = prompts.interlisModelingAgent().toString();

    assertThat(prompt)
        .contains("author-interlis-constraint")
        .contains("authorIliMandatoryConstraint")
        .contains("authorIliExistenceConstraint")
        .contains("authorIliPlausibilityConstraint")
        .contains("authorIliSetConstraint")
        .contains("createUniqueConstraint")
        .contains("proofVerified=true")
        .contains("generationVerified=true")
        .contains("genau einmal `reviewIliChange`")
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
        .contains("applyIliModelChange")
        .contains("ADD_ATTRIBUTE")
        .contains("APPLIED")
        .contains("reviewIliChange")
        .contains("potentiallyBreakingChanges")
        .contains("impact")
        .contains("afterReview")
        .contains("kein weiteres")
        .contains("readModelExample")
        .contains("proofVerified=true")
        .contains("generateIliConstraintCases");
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
        .contains("authorIliExistenceConstraint")
        .contains("authorIliPlausibilityConstraint")
        .contains("authorIliSetConstraint")
        .contains("createUniqueConstraint")
        .contains("kein typisiertes High-Level-Authoring")
        .contains("generateIliConstraintCases")
        .contains("proofVerified=true")
        .contains("generationVerified=true")
        .contains("coverageUnsolved")
        .contains("reviewIliChange")
        .contains("afterReview")
        .contains("Legacy-/Snippet-Tools");
  }

  @Test
  void agentWorkflowResourceUsesTheSameHighLevelLoop() {
    String resource = new KnowledgeResources(null, null).agentWorkflow().toString();

    assertThat(resource)
        .contains("findSimilarModels")
        .contains("readModelExample")
        .contains("applyIliModelChange")
        .contains("ADD_ATTRIBUTE")
        .contains("reviewIliChange")
        .contains("afterReview")
        .contains("nicht zusaetzlich `reviewIliChange` oder `reviewIliModel`")
        .contains("nicht als Standard-Dreierfolge");
  }

  @Test
  void constraintWorkflowResourceDocumentsAuthoringProofAndReviewHierarchy() {
    String resource = new ConstraintWorkflowResource().constraintWorkflow().toString();

    assertThat(resource)
        .contains("interlis://knowledge/constraint-workflow")
        .contains("MANDATORY")
        .contains("UNIQUE")
        .contains("EXISTENCE")
        .contains("PLAUSIBILITY")
        .contains("SET")
        .contains("authorIliMandatoryConstraint")
        .contains("authorIliExistenceConstraint")
        .contains("authorIliPlausibilityConstraint")
        .contains("authorIliSetConstraint")
        .contains("generateIliConstraintCases")
        .contains("proofVerified")
        .contains("generationVerified")
        .contains("reviewIliChange")
        .contains("Safety-Reason-Code");
  }
}
