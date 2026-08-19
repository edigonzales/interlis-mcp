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
  void reviewPromptUsesReviewIliModelAsMandatoryStep() {
    String prompt = prompts.reviewInterlisModel("PUBLICATION").toString();

    assertThat(prompt)
        .contains("1. Fuehre `reviewIliModel`")
        .contains("PUBLICATION")
        .contains("compilerDiagnostics")
        .contains("manualChecks")
        .contains("openQuestions")
        .doesNotContain("1. Fuehre `analyzeIliModel`");
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
        .contains("readModelExample");
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
}
