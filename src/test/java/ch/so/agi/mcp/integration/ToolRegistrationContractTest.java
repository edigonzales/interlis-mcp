package ch.so.agi.mcp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
class ToolRegistrationContractTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired
  @Qualifier("toolSpecs")
  List<SyncToolSpecification> toolSpecifications;

  @Test
  void snippetToolsAreRegisteredWithExpectedSchemasAndBehavior() throws Exception {
    Map<String, SyncToolSpecification> specsByName =
        toolSpecifications.stream().collect(java.util.stream.Collectors.toMap(spec -> spec.tool().name(), spec -> spec));

    assertThat(specsByName).containsKeys("createModelSnippet");

    SyncToolSpecification createModelSnippet = specsByName.get("createModelSnippet");
    var inputSchema = createModelSnippet.tool().inputSchema();

    List<String> requiredParams = inputSchema.required() == null ? List.of() : inputSchema.required();

    assertThat(requiredParams).contains("name");

    Map<String, Object> properties = inputSchema.properties();
    assertThat(propertyDescription(properties, "name"))
        .isEqualTo("Modellname (Bezeichner ohne Leerzeichen)");
    assertThat(propertyDescription(properties, "lang"))
        .isEqualTo("Sprachcode, z. B. 'de' oder 'en'");
    assertThat(propertyDescription(properties, "uri")).isEqualTo("URI des Modells");
    assertThat(propertyDescription(properties, "version"))
        .isEqualTo("Version im Format YYYY-MM-DD");
    assertThat(propertyDescription(properties, "iliVersion"))
        .isEqualTo("INTERLIS Sprachversion (z. B. '2.3' oder '2.4')");
    assertThat(propertyDescription(properties, "imports"))
        .isEqualTo("Zusätzliche Imports (z. B. 'GeometryCHLV95_V1')");
    assertThat(propertyDescription(properties, "includeSolothurnHeader"))
        .isEqualTo("Fügt einen Solothurn-Header oberhalb des Snippets ein");

    var request = new McpSchema.CallToolRequest("createModelSnippet", createModelSnippetRequest());
    var response = createModelSnippet.callHandler().apply(null, request);
    Map<String, Object> structured = extractStructuredContent(response);

    assertThat(structured.get("iliSnippet"))
        .isEqualTo(
            "INTERLIS 2.3;\n\n"
                + "MODEL TestModel (de) AT \"https://example.org/test\" VERSION \"2024-01-31\" =\n"
                + "  IMPORTS UNQUALIFIED INTERLIS, GeometryCHLV95_V1;\n\n"
                + "END TestModel.\n");
    @SuppressWarnings("unchecked")
    Map<String, Object> cursorHint = (Map<String, Object>) structured.get("cursorHint");
    assertThat(cursorHint).containsEntry("line", 4).containsEntry("col", 0);
  }

  private Map<String, Object> createModelSnippetRequest() {
    return Map.of(
        "name", "TestModel",
        "lang", "de",
        "uri", "https://example.org/test",
        "version", "2024-01-31",
        "iliVersion", "2.3",
        "imports", List.of("INTERLIS", "GeometryCHLV95_V1"),
        "includeSolothurnHeader", false);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> extractStructuredContent(McpSchema.CallToolResult response) throws Exception {
    Object structuredContent = response.structuredContent();
    if (structuredContent instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }

    var content = response.content();
    assertThat(content).isNotEmpty();
    var first = content.getFirst();
    if (first instanceof McpSchema.TextContent textContent) {
      return mapper.readValue(textContent.text(), Map.class);
    }

    throw new IllegalStateException("Unsupported content payload: " + first);
  }

  @SuppressWarnings("unchecked")
  private static String propertyDescription(Map<String, Object> properties, String key) {
    Object node = properties.get(key);
    if (node instanceof Map<?, ?> map) {
      Object desc = map.get("description");
      return desc != null ? desc.toString() : "";
    }
    return "";
  }
}
