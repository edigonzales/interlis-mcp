package ch.so.agi.mcp.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentPromptsTest {

  private final AgentPrompts prompts = new AgentPrompts();

  @Test
  void modelingAgentPrefersHighLevelReviewTools() {
    String prompt = prompts.interlisModelingAgent().toString();

    assertThat(prompt)
        .contains("reviewIliModel")
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
  void extensionPromptUsesChangeReviewAsFinalGate() {
    String prompt = prompts.extendInterlisModel("CAPTURE").toString();

    assertThat(prompt)
        .contains("reviewIliChange")
        .contains("potentiallyBreakingChanges")
        .contains("impact")
        .contains("afterReview")
        .contains("nicht noch `reviewIliModel`")
        .contains("readModelExample")
        .doesNotContain("finalen Modelltext mit `reviewIliModel`");
  }

  @Test
  void agentWorkflowResourceUsesTheSameHighLevelLoop() {
    String resource = new KnowledgeResources(null, null).agentWorkflow().toString();

    assertThat(resource)
        .contains("findSimilarModels")
        .contains("readModelExample")
        .contains("reviewIliChange")
        .contains("afterReview")
        .contains("nicht zusaetzlich `reviewIliModel`")
        .contains("nicht als Standard-Dreierfolge");
  }
}
