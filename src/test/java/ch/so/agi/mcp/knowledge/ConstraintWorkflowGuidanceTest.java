package ch.so.agi.mcp.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.tools.UniqueConstraintAuthoringTools;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

class ConstraintWorkflowGuidanceTest {

  @Test
  void uniqueHighLevelAuthoringDocumentsScopeProofAndFinalReview() {
    assertThat(description("authorIliUniqueConstraint"))
        .contains("GLOBAL", "BASKET", "LOCAL")
        .contains("Schlüsselpfaden")
        .contains("Proof")
        .contains("afterReview")
        .contains("kein reviewIliChange");
  }

  private String description(String methodName) {
    for (Method method : UniqueConstraintAuthoringTools.class.getDeclaredMethods()) {
      if (!method.getName().equals(methodName)) {
        continue;
      }
      McpTool annotation = method.getAnnotation(McpTool.class);
      if (annotation == null) {
        throw new AssertionError("Missing @McpTool on UniqueConstraintAuthoringTools." + methodName);
      }
      return annotation.description();
    }
    throw new AssertionError("Method not found: UniqueConstraintAuthoringTools." + methodName);
  }
}
