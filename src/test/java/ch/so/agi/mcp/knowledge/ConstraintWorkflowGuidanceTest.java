package ch.so.agi.mcp.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.tools.ConstraintTools;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

class ConstraintWorkflowGuidanceTest {

  @Test
  void uniqueSnippetHelperDocumentsTheProofAndReviewFallback() {
    assertThat(description("unique"))
        .contains("Low-Level-Snippet-Helper")
        .contains("kein typed High-Level-Authoring")
        .contains("generateIliConstraintCases")
        .contains("reviewIliChange")
        .contains("WHERE/(BASKET)/LOCAL");
  }

  private String description(String methodName) {
    for (Method method : ConstraintTools.class.getDeclaredMethods()) {
      if (!method.getName().equals(methodName)) {
        continue;
      }
      McpTool annotation = method.getAnnotation(McpTool.class);
      if (annotation == null) {
        throw new AssertionError("Missing @McpTool on ConstraintTools." + methodName);
      }
      return annotation.description();
    }
    throw new AssertionError("Method not found: ConstraintTools." + methodName);
  }
}
