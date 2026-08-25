package ch.so.agi.mcp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
class ToolRegistrationContractTest {

  private static final Map<String, SchemaExpectation> EXPECTED_SCHEMAS = expectedSchemas();

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired
  @Qualifier("toolSpecs")
  List<SyncToolSpecification> toolSpecifications;

  @Test
  void allRegisteredToolsMatchExpectedSchemaContract() {
    Map<String, SyncToolSpecification> specsByName = specsByName();

    assertThat(specsByName.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_SCHEMAS.keySet());

    EXPECTED_SCHEMAS.forEach((toolName, expectation) -> {
      SyncToolSpecification spec = specsByName.get(toolName);
      assertThat(spec).as("tool specification for %s", toolName).isNotNull();

      var inputSchema = spec.tool().inputSchema();
      List<String> required = requiredProperties(spec);
      assertThat(required)
          .as("required properties for %s", toolName)
          .containsExactlyInAnyOrderElementsOf(expectation.required());

      Map<String, Object> properties = schemaProperties(inputSchema);
      assertThat(properties.keySet())
          .as("schema properties for %s", toolName)
          .containsExactlyInAnyOrderElementsOf(Stream.concat(
              expectation.required().stream(), expectation.optional().stream()).collect(Collectors.toSet()));

      expectation.optional().forEach(optionalProperty ->
          assertThat(required)
              .as("optional property %s for %s must not be required", optionalProperty, toolName)
              .doesNotContain(optionalProperty));
    });
  }

  @Test
  void publicToolSurfaceStaysSmallEnoughForAgentContext() throws Exception {
    assertThat(toolSpecifications).hasSize(27);
    for (SyncToolSpecification specification : toolSpecifications) {
      assertThat(mapper.writeValueAsBytes(specification.tool()).length)
          .as("serialized MCP declaration for %s", specification.tool().name())
          .isLessThan(50_000);
    }
  }

  @Test
  void authorIliModelDeserializesTypedCompleteSpec() throws Exception {
    SyncToolSpecification author = specsByName().get("authorIliModel");
    var response = author.callHandler().apply(null,
        new McpSchema.CallToolRequest("authorIliModel", Map.of(
            "spec", Map.of(
                "name", "TestModel",
                "language", "de",
                "uri", "https://example.org/test",
                "version", "2024-01-31",
                "iliVersion", "2.4",
                "topics", List.of(Map.of(
                    "name", "Data",
                    "classes", List.of(Map.of(
                        "name", "Thing",
                        "attributes", List.of(Map.of(
                            "name", "code",
                            "typeSpec", Map.of("baseType", Map.of(
                                "kind", "TEXT", "length", 20))))))))),
            "modelPurpose", "CAPTURE")));

    Map<String, Object> structured = extractStructuredContent(response);
    assertThat(structured.get("status")).isEqualTo("GENERATED");
    assertThat(structured.get("complete")).isEqualTo(true);
    assertThat(structured.get("updatedModelText").toString())
        .contains("MODEL TestModel (de)")
        .contains("code : TEXT*20;");
    assertThat(structured).containsKeys("derivedImports", "afterReview", "constraintProofs");
  }

  @Test
  void validateIliModelAcceptsMinimalValidModel() throws Exception {
    SyncToolSpecification validateIliModel = specsByName().get("validateIliModel");

    var response = validateIliModel.callHandler().apply(null,
        new McpSchema.CallToolRequest("validateIliModel", Map.of(
            "modelText",
            "INTERLIS 2.4;\n\n"
                + "MODEL DemoModel (de) AT \"https://example.org/demo\" VERSION \"2024-01-31\" =\n\n"
                + "END DemoModel.\n")));

    Map<String, Object> structured = extractStructuredContent(response);
    assertThat(structured.get("valid")).isEqualTo(true);
    assertThat(structured.get("messages")).asList().isEmpty();
  }

  @Test
  void generateExampleXtfReturnsStructuredPayload() throws Exception {
    SyncToolSpecification generateExampleXtf = specsByName().get("generateExampleXtf");

    var response = generateExampleXtf.callHandler().apply(null,
        new McpSchema.CallToolRequest("generateExampleXtf", Map.of(
            "modelText",
            "INTERLIS 2.4;\n\n"
                + "MODEL DemoModel (de) AT \"https://example.org/demo\" VERSION \"2024-01-31\" =\n"
                + "  TOPIC Data =\n"
                + "    CLASS Building =\n"
                + "      name : MANDATORY TEXT*20;\n"
                + "    END Building;\n"
                + "  END Data;\n"
                + "END DemoModel.\n")));

    Map<String, Object> structured = extractStructuredContent(response);
    assertThat(structured.get("generated")).isEqualTo(true);
    assertThat(structured.get("xtfText")).isNotNull();
    assertThat(((Number) structured.get("basketCount")).intValue()).isGreaterThanOrEqualTo(1);
    assertThat(((Number) structured.get("objectCount")).intValue()).isGreaterThanOrEqualTo(1);
    assertThat(structured).containsKeys("objectsByClass", "skippedClasses", "messages");
  }

  @Test
  void validateXtfReturnsErrorsForInvalidXtf() throws Exception {
    SyncToolSpecification validateXtf = specsByName().get("validateXtf");

    var response = validateXtf.callHandler().apply(null,
        new McpSchema.CallToolRequest("validateXtf", Map.of(
            "modelText",
            "INTERLIS 2.4;\n\n"
                + "MODEL DemoModel (de) AT \"https://example.org/demo\" VERSION \"2024-01-31\" =\n"
                + "  TOPIC Data =\n"
                + "    CLASS Building =\n"
                + "      name : MANDATORY TEXT*20;\n"
                + "    END Building;\n"
                + "  END Data;\n"
                + "END DemoModel.\n",
            "xtfText", "<TRANSFER>")));

    Map<String, Object> structured = extractStructuredContent(response);
    assertThat(structured.get("valid")).isEqualTo(false);
    assertThat(((Number) structured.get("errorCount")).intValue()).isGreaterThan(0);
    assertThat(structured.get("messages")).asList().isNotEmpty();
  }

  @Test
  void applyIliModelChangesDeserializesTypedAtomicBatch() throws Exception {
    SyncToolSpecification applyIliModelChanges = specsByName().get("applyIliModelChanges");

    String modelText = "INTERLIS 2.4;\n\n"
        + "MODEL DemoModel (de) AT \"https://example.org/demo\" VERSION \"2026-08-19\" =\n"
        + "  TOPIC Data =\n"
        + "    CLASS Building =\n"
        + "      name : TEXT*20;\n"
        + "    END Building;\n"
        + "  END Data;\n"
        + "END DemoModel.\n";

    var response = applyIliModelChanges.callHandler().apply(null,
        new McpSchema.CallToolRequest("applyIliModelChanges", Map.of(
            "modelText", modelText,
            "request", Map.of(
                "allowPotentiallyBreaking", true,
                "changes", List.of(Map.of(
                    "operation", "ADD_ATTRIBUTE",
                    "addAttribute", Map.of(
                        "containerFqn", "DemoModel.Data.Building",
                        "attribute", Map.of(
                            "name", "egid",
                            "mandatory", true,
                            "typeSpec", Map.of(
                                "baseType", Map.of(
                                    "kind", "TEXT",
                                    "length", 14))))))))));

    Map<String, Object> structured = extractStructuredContent(response);
    assertThat(structured.get("status")).isEqualTo("APPLIED");
    assertThat(structured.get("updatedModelText").toString())
        .contains("egid : MANDATORY TEXT*14;");
    assertThat(structured).containsKeys("sourceEdits", "afterReview", "potentiallyBreakingChanges");
  }

  @Test
  void allPublicToolsHaveSafeExplicitAnnotations() {
    for (SyncToolSpecification specification : toolSpecifications) {
      var annotations = specification.tool().annotations();
      assertThat(annotations.destructiveHint())
          .as(specification.tool().name())
          .isFalse();
      if (!"indexConfiguredModels".equals(specification.tool().name())) {
        assertThat(annotations.readOnlyHint()).as(specification.tool().name()).isTrue();
      }
      assertThat(annotations.idempotentHint()).as(specification.tool().name()).isTrue();
    }
  }

  @Test
  void highLevelAuthoringToolsPublishOutputSchemas() {
    Map<String, SyncToolSpecification> specifications = specsByName();
    Set<String> authoringTools = Set.of(
        "authorIliModel",
        "applyIliModelChanges",
        "authorIliMandatoryConstraint",
        "authorIliUniqueConstraint",
        "authorIliExistenceConstraint",
        "authorIliPlausibilityConstraint",
        "authorIliSetConstraint",
        "generateIliConstraintFromDecisionTable");
    for (String toolName : authoringTools) {
      assertThat(specifications.get(toolName).tool().outputSchema())
          .as("output schema for %s", toolName)
          .isNotNull()
          .isNotEmpty();
    }
    for (String toolName : Set.of(
        "authorIliModel", "applyIliModelChanges", "authorIliMandatoryConstraint",
        "authorIliUniqueConstraint", "authorIliExistenceConstraint",
        "authorIliPlausibilityConstraint", "authorIliSetConstraint")) {
      Map<String, Object> properties = schemaProperties(
          specifications.get(toolName).tool().outputSchema());
      assertThat(properties).as("typed output properties for %s", toolName)
          .containsKeys("status", "complete", "updatedModelText", "candidateModelText",
              "compilerDiagnostics", "sourceEdits", "derivedImports", "semanticDiff",
              "afterReview", "constraintProofs", "openQuestions", "requiresUserDecision");
    }
  }

  @Test
  void modelAndBatchSchemasExposeClosedConstraintDiscriminators() throws Exception {
    for (String toolName : Set.of("authorIliModel", "applyIliModelChanges")) {
      String schema = mapper.writeValueAsString(specsByName().get(toolName).tool().inputSchema());
      assertThat(schema).as(toolName)
          .contains("oneOf", "MANDATORY", "UNIQUE", "EXISTENCE", "PLAUSIBILITY", "SET")
          .contains("OBJECT_COUNT", "BOOLEAN_EXPRESSION", "ALL", "PATH");
    }
  }

  @Test
  void outputSchemasUseTypedNestedAuthoringObjects() throws Exception {
    String schema = mapper.writeValueAsString(
        specsByName().get("authorIliUniqueConstraint").tool().outputSchema());
    assertThat(schema)
        .contains("beforeDiagnostics", "afterDiagnostics", "sourceEdits", "semanticDiff")
        .contains("afterReview", "constraintProofs", "coverageGaps", "generatedCases")
        .contains("openQuestions", "CompilerDiagnostic", "startOffset", "constraintFqn")
        .contains(
            "GENERATED",
            "APPLIED",
            "BREAKING_CHANGE_REQUIRES_CONFIRMATION",
            "NEEDS_INPUT",
            "INVALID_SPEC",
            "BEFORE_MODEL_INVALID",
            "CANDIDATE_MODEL_INVALID",
            "AST_ROUND_TRIP_FAILED",
            "PROOF_INCOMPLETE",
            "PROOF_FAILED",
            "EXTERNAL_FUNCTION_SEMANTICS_REQUIRED",
            "UNEXPECTED_SEMANTIC_CHANGE");
  }

  @Test
  void oldFlatConstraintPayloadsAreRejected() {
    Map<String, Map<String, Object>> oldArguments = Map.of(
        "authorIliMandatoryConstraint", Map.of("constraintName", "C", "condition", "x > 0"),
        "authorIliUniqueConstraint", Map.of("constraintName", "C", "uniqueScope", "GLOBAL"),
        "authorIliExistenceConstraint", Map.of("constraintName", "C", "restrictedPath", "x"),
        "authorIliPlausibilityConstraint", Map.of("constraintName", "C", "percentage", 50),
        "authorIliSetConstraint", Map.of("constraintName", "C", "condition", "x"));
    oldArguments.forEach((toolName, flat) -> {
      Map<String, Object> arguments = new LinkedHashMap<>();
      arguments.put("modelText", "INTERLIS 2.4;");
      arguments.put("contextFqn", "Demo.Data.Item");
      arguments.putAll(flat);
      McpSchema.CallToolResult response = specsByName().get(toolName).callHandler().apply(
          null, new McpSchema.CallToolRequest(toolName, arguments));
      if (!response.isError()) {
        try {
          assertThat(extractStructuredContent(response).get("status"))
              .as(toolName).isEqualTo("INVALID_SPEC");
        } catch (Exception ex) {
          throw new AssertionError("Unable to inspect rejected payload for " + toolName, ex);
        }
      }
    });
  }

  @Test
  void authorIliExistenceConstraintDeserializesExplicitRequiredInTargets() throws Exception {
    SyncToolSpecification author = specsByName().get("authorIliExistenceConstraint");

    String modelText = "INTERLIS 2.4;\n\n"
        + "MODEL ExistDemo (de) AT \"https://example.org/demo\" VERSION \"2026-08-19\" =\n"
        + "  TOPIC Data =\n"
        + "    CLASS TargetA =\n"
        + "      code : 0..10;\n"
        + "    END TargetA;\n"
        + "    CLASS TargetB =\n"
        + "      code : 0..10;\n"
        + "    END TargetB;\n"
        + "    CLASS Source =\n"
        + "      code : MANDATORY 0..10;\n"
        + "    END Source;\n"
        + "  END Data;\n"
        + "END ExistDemo.\n";

    var response = author.callHandler().apply(null,
        new McpSchema.CallToolRequest("authorIliExistenceConstraint", Map.of(
            "modelText", modelText,
            "contextFqn", "ExistDemo.Data.Source",
            "spec", Map.of(
                "kind", "EXISTENCE",
                "name", "CodeExists",
                "restrictedPath", "code",
                "requiredIn", List.of(
                    Map.of("viewableFqn", "ExistDemo.Data.TargetA", "attributePath", "code"),
                    Map.of("viewableFqn", "ExistDemo.Data.TargetB", "attributePath", "code"))))));

    Map<String, Object> structured = extractStructuredContent(response);
    assertThat(structured.get("generated")).isEqualTo(true);
    assertThat(structured.get("proofVerified")).isEqualTo(true);
    assertThat(structured.get("updatedModelText").toString())
        .contains("EXISTENCE CONSTRAINT")
        .contains("ExistDemo.Data.TargetA : code")
        .contains("OR ExistDemo.Data.TargetB : code");
    assertThat(structured.get("constraintProofs")).asList().hasSize(1);
  }

  @Test
  void authorIliPlausibilityConstraintDeserializesPopulationSpec() throws Exception {
    SyncToolSpecification author = specsByName().get("authorIliPlausibilityConstraint");

    String modelText = "INTERLIS 2.4;\n\n"
        + "MODEL PlausDemo (de) AT \"https://example.org/demo\" VERSION \"2026-08-19\" =\n"
        + "  TOPIC Data =\n"
        + "    CLASS Item =\n"
        + "      value : MANDATORY 0..100;\n"
        + "    END Item;\n"
        + "  END Data;\n"
        + "END PlausDemo.\n";

    Map<String, Object> condition = Map.of(
        "kind", "COMPARE",
        "operator", ">=",
        "children", List.of(
            Map.of("kind", "ATTRIBUTE", "name", "value"),
            Map.of("kind", "NUMERIC", "value", 10)));
    Map<String, Object> spec = Map.of(
        "kind", "PLAUSIBILITY",
        "name", "UsuallyHigh",
        "direction", "AT_LEAST",
        "percentage", 80,
        "condition", condition);
    var response = author.callHandler().apply(null,
        new McpSchema.CallToolRequest("authorIliPlausibilityConstraint", Map.of(
            "modelText", modelText,
            "contextFqn", "PlausDemo.Data.Item",
            "spec", spec)));

    Map<String, Object> structured = extractStructuredContent(response);
    assertThat(structured.get("generated")).isEqualTo(true);
    assertThat(structured.get("proofVerified")).isEqualTo(true);
    assertThat(structured.get("updatedModelText").toString())
        .contains("!!@ name=\"UsuallyHigh\"")
        .contains(">= 80%")
        .contains("value >= 10");
  }

  @Test
  void authorIliSetConstraintDeserializesTypedWhereAndVerifiesProof() throws Exception {
    SyncToolSpecification author = specsByName().get("authorIliSetConstraint");

    String modelText = "INTERLIS 2.4;\n\n"
        + "MODEL SetDemo (de) AT \"https://example.org/demo\" VERSION \"2026-08-19\" =\n"
        + "  TOPIC Data =\n"
        + "    CLASS Item =\n"
        + "      value : MANDATORY 0..10;\n"
        + "    END Item;\n"
        + "  END Data;\n"
        + "END SetDemo.\n";

    Map<String, Object> setSpec = Map.of(
        "kind", "SET",
        "name", "AtLeastTwoHigh",
        "scope", "GLOBAL",
        "where", Map.of(
            "kind", "COMPARE",
            "operator", ">=",
            "children", List.of(
                Map.of("kind", "ATTRIBUTE", "name", "value"),
                Map.of("kind", "NUMERIC", "value", 5))),
        "condition", Map.of(
            "kind", "OBJECT_COUNT",
            "objects", Map.of("kind", "ALL"),
            "operator", ">=",
            "threshold", 2));
    var response = author.callHandler().apply(null,
        new McpSchema.CallToolRequest("authorIliSetConstraint", Map.of(
            "modelText", modelText,
            "contextFqn", "SetDemo.Data.Item",
            "spec", setSpec)));

    Map<String, Object> structured = extractStructuredContent(response);
    assertThat(structured.get("generated")).as("%s", structured).isEqualTo(true);
    assertThat(structured.get("proofVerified")).as("%s", structured).isEqualTo(true);
    assertThat(structured.get("updatedModelText").toString())
        .contains("SET CONSTRAINT WHERE (value >= 5):")
        .contains("INTERLIS.objectCount(ALL) >= 2;");
    assertThat(structured.get("constraintProofs")).asList().hasSize(1);
  }

  private Map<String, SyncToolSpecification> specsByName() {
    return toolSpecifications.stream()
        .collect(Collectors.toMap(spec -> spec.tool().name(), spec -> spec, (left, right) -> left, LinkedHashMap::new));
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

  @SuppressWarnings("unchecked")
  private static Map<String, Object> schemaProperties(Map<String, Object> schema) {
    Object properties = schema.get("properties");
    return properties instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  private static List<String> requiredProperties(SyncToolSpecification specification) {
    Object required = specification.tool().inputSchema().get("required");
    if (!(required instanceof List<?> list)) {
      return List.of();
    }
    return list.stream().map(String::valueOf).toList();
  }

  private static Map<String, SchemaExpectation> expectedSchemas() {
    Map<String, SchemaExpectation> expectations = new LinkedHashMap<>();

    expectations.put("formatIliModel", schema(Set.of("modelText"), Set.of()));
    expectations.put("analyzeIliModel", schema(Set.of("modelText"), Set.of("modelPurpose")));
    expectations.put("checkModelingRules", schema(Set.of("modelText"), Set.of("modelPurpose", "ruleIds", "profile")));
    expectations.put("reviewIliModel", schema(Set.of("modelText"), Set.of("modelPurpose", "ruleProfile")));
    expectations.put("reviewIliChange", schema(Set.of("beforeModelText", "afterModelText"), Set.of("modelPurpose", "ruleProfile")));
    expectations.put("authorIliModel", schema(Set.of("spec"), Set.of("modelPurpose", "ruleProfile")));
    expectations.put("applyIliModelChanges", schema(Set.of("modelText", "request"), Set.of("modelPurpose", "ruleProfile")));
    expectations.put("reviewIliConstraint", schema(Set.of("modelText", "constraint"), Set.of()));
    expectations.put("generateIliConstraintCases", schema(Set.of("modelText", "constraint"), Set.of()));
    expectations.put("generateIliConstraintFromDecisionTable", schema(Set.of("modelText", "context", "constraintName", "rows"), Set.of()));
    expectations.put("authorIliMandatoryConstraint", constraintAuthoringSchema());
    expectations.put("authorIliPlausibilityConstraint", constraintAuthoringSchema());
    expectations.put("authorIliExistenceConstraint", constraintAuthoringSchema());
    expectations.put("authorIliSetConstraint", constraintAuthoringSchema());
    expectations.put("authorIliUniqueConstraint", constraintAuthoringSchema());
    expectations.put("testIliConstraint", schema(Set.of("modelText", "constraint", "cases"), Set.of()));
    expectations.put("findSimilarModels", schema(Set.of(), Set.of("query", "modelText", "modelPurpose", "limit")));
    expectations.put("indexConfiguredModels", schema(Set.of(), Set.of()));
    expectations.put("readModelExample", schema(Set.of("path"), Set.of()));
    expectations.put("listConstraintFunctions", schema(Set.of(), Set.of("iliVersion")));
    expectations.put("listGeometryTypes", schema(Set.of(), Set.of("iliVersion")));
    expectations.put("listModelingRules", schema(Set.of(), Set.of("profile")));
    expectations.put("resolveConstraintPath", schema(Set.of("modelText", "context", "path"), Set.of()));
    expectations.put("renameModelElement", schema(Set.of("modelText", "elementFqn", "newName"), Set.of("expectedKind")));
    expectations.put("validateIliModel", schema(Set.of("modelText"), Set.of()));
    expectations.put("generateExampleXtf", schema(Set.of("modelText"), Set.of("maxObjectsPerClass")));
    expectations.put("validateXtf", schema(Set.of("modelText", "xtfText"), Set.of()));

    return expectations;
  }

  private static SchemaExpectation schema(Set<String> required, Set<String> optional) {
    return new SchemaExpectation(required, optional);
  }

  private static SchemaExpectation constraintAuthoringSchema() {
    return schema(
        Set.of("modelText", "contextFqn", "spec"),
        Set.of("modelPurpose", "ruleProfile"));
  }

  private record SchemaExpectation(Set<String> required, Set<String> optional) {
  }
}
