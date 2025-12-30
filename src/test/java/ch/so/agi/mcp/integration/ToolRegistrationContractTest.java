package ch.so.agi.mcp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
class ToolRegistrationContractTest {

  @Autowired ToolCallbackProvider toolCallbackProvider;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void snippetToolsAreRegisteredWithExpectedSchemasAndBehavior() throws Exception {
    Map<String, ToolCallback> callbacksByName =
        Arrays.stream(toolCallbackProvider.getToolCallbacks())
            .collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), Function.identity()));

    assertThat(callbacksByName).containsKeys("createModelSnippet");

    ToolCallback createModelSnippet = callbacksByName.get("createModelSnippet");
    ToolDefinition snippetDefinition = createModelSnippet.getToolDefinition();
    JsonNode schema = objectMapper.readTree(snippetDefinition.inputSchema());

    List<String> requiredParams =
        schema.path("required").isMissingNode()
            ? List.of()
            : iterableToList(schema.path("required"));

    assertThat(requiredParams).contains("name");

    JsonNode properties = schema.path("properties");
    assertThat(properties.path("name").path("description").asText())
        .isEqualTo("Modellname (Bezeichner ohne Leerzeichen)");
    assertThat(properties.path("lang").path("description").asText())
        .isEqualTo("Sprachcode, z. B. 'de' oder 'en'");
    assertThat(properties.path("uri").path("description").asText())
        .isEqualTo("URI des Modells");
    assertThat(properties.path("version").path("description").asText())
        .isEqualTo("Version im Format YYYY-MM-DD");
    assertThat(properties.path("imports").path("description").asText())
        .isEqualTo("Zusätzliche Imports (z. B. 'GeometryCHLV95_V1')");

    String requestJson = createModelSnippetRequest();
    String responseJson = createModelSnippet.call(requestJson);
    JsonNode response = objectMapper.readTree(responseJson);

    assertThat(response.path("iliSnippet").asText())
        .isEqualTo(
            "MODEL TestModel (de) AT \"https://example.org/test\" VERSION \"2024-01-31\" =\n"
                + "  IMPORTS UNQUALIFIED INTERLIS, GeometryCHLV95_V1;\n\n"
                + "END TestModel.\n");
    assertThat(response.path("cursorHint").path("line").asInt()).isEqualTo(2);
    assertThat(response.path("cursorHint").path("col").asInt()).isEqualTo(0);
  }

  private String createModelSnippetRequest() throws Exception {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("name", "TestModel");
    root.put("lang", "de");
    root.put("uri", "https://example.org/test");
    root.put("version", "2024-01-31");

    ArrayNode imports = root.putArray("imports");
    imports.add("INTERLIS");
    imports.add("GeometryCHLV95_V1");

    return objectMapper.writeValueAsString(root);
  }

  private static List<String> iterableToList(JsonNode arrayNode) {
    return StreamSupport.stream(arrayNode.spliterator(), false)
        .map(JsonNode::asText)
        .collect(Collectors.toList());
  }
}
