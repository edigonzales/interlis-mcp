package ch.so.agi.mcp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
          .containsAll(expectation.required())
          .containsAll(expectation.optional());

      expectation.optional().forEach(optionalProperty ->
          assertThat(required)
              .as("optional property %s for %s must not be required", optionalProperty, toolName)
              .doesNotContain(optionalProperty));
    });
  }

  @Test
  void createModelSnippetHasExpectedDescriptionsAndBehavior() throws Exception {
    SyncToolSpecification createModelSnippet = specsByName().get("createModelSnippet");
    var inputSchema = createModelSnippet.tool().inputSchema();

    Map<String, Object> properties = schemaProperties(inputSchema);
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

    var response = createModelSnippet.callHandler().apply(null,
        new McpSchema.CallToolRequest("createModelSnippet", Map.of(
            "name", "TestModel",
            "lang", "de",
            "uri", "https://example.org/test",
            "version", "2024-01-31",
            "iliVersion", "2.3",
            "imports", List.of("INTERLIS", "GeometryCHLV95_V1"),
            "includeSolothurnHeader", false)));

    Map<String, Object> structured = extractStructuredContent(response);
    assertThat(structured.get("iliSnippet"))
        .isEqualTo(
            "INTERLIS 2.3;\n\n"
                + "MODEL TestModel (de) AT \"https://example.org/test\" VERSION \"2024-01-31\" =\n"
                + "  IMPORTS INTERLIS;\n"
                + "  IMPORTS GeometryCHLV95_V1;\n\n"
                + "END TestModel.\n");
  }

  @Test
  void ensureGeometryDependenciesProducesExpectedGeometryPayload() throws Exception {
    SyncToolSpecification ensureGeometryDependencies = specsByName().get("ensureGeometryDependencies");

    var response = ensureGeometryDependencies.callHandler().apply(null,
        new McpSchema.CallToolRequest("ensureGeometryDependencies", Map.of(
            "attributeName", "Perimeter",
            "arcs", true)));

    Map<String, Object> structured = extractStructuredContent(response);
    assertThat(structured)
        .containsKeys("importLinesToAdd", "domainsToAdd", "attributeLine", "notes");
    assertThat(structured.get("importLinesToAdd")).asList().contains("IMPORTS INTERLIS;");
    assertThat(structured.get("domainsToAdd")).asList().isNotEmpty();
    assertThat(structured.get("attributeLine").toString())
        .contains("Perimeter : SURFACE WITH (STRAIGHTS, ARCS)")
        .contains("VERTEX Coord2");
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
  void listMathFunctionsDefaultsToInterlis24AndReturnsFunctions() throws Exception {
    SyncToolSpecification listMathFunctions = specsByName().get("listMathFunctions");

    var response = listMathFunctions.callHandler().apply(null,
        new McpSchema.CallToolRequest("listMathFunctions", Map.of()));

    Map<String, Object> structured = extractStructuredContent(response);
    assertThat(structured.get("iliVersion")).isEqualTo("2.4");
    assertThat(structured.get("functions")).asList().isNotEmpty();
  }

  @Test
  void applyIliModelChangeDeserializesTypedAddAttributeRequest() throws Exception {
    SyncToolSpecification applyIliModelChange = specsByName().get("applyIliModelChange");

    String modelText = "INTERLIS 2.4;\n\n"
        + "MODEL DemoModel (de) AT \"https://example.org/demo\" VERSION \"2026-08-19\" =\n"
        + "  TOPIC Data =\n"
        + "    CLASS Building =\n"
        + "      name : TEXT*20;\n"
        + "    END Building;\n"
        + "  END Data;\n"
        + "END DemoModel.\n";

    var response = applyIliModelChange.callHandler().apply(null,
        new McpSchema.CallToolRequest("applyIliModelChange", Map.of(
            "modelText", modelText,
            "request", Map.of(
                "operation", "ADD_ATTRIBUTE",
                "addAttribute", Map.of(
                    "containerFqn", "DemoModel.Data.Building",
                    "attribute", Map.of(
                        "name", "egid",
                        "mandatory", true,
                        "typeSpec", Map.of(
                            "baseType", Map.of(
                                "kind", "TEXT",
                                "length", 14))))))));

    Map<String, Object> structured = extractStructuredContent(response);
    assertThat(structured.get("status")).isEqualTo("APPLIED");
    assertThat(structured.get("targetFqn")).isEqualTo("DemoModel.Data.Building.egid");
    assertThat(structured.get("updatedModelText").toString())
        .contains("egid : MANDATORY TEXT*14;");
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
            "context", "ExistDemo.Data.Source",
            "constraintName", "CodeExists",
            "restrictedPath", "code",
            "requiredIn", List.of(
                Map.of("viewableFqn", "ExistDemo.Data.TargetA", "attributePath", "code"),
                Map.of("viewableFqn", "ExistDemo.Data.TargetB", "attributePath", "code")))));

    Map<String, Object> structured = extractStructuredContent(response);
    assertThat(structured.get("generated")).isEqualTo(true);
    assertThat(structured.get("proofVerified")).isEqualTo(true);
    assertThat(structured.get("updatedModelText").toString())
        .contains("EXISTENCE CONSTRAINT")
        .contains("ExistDemo.Data.TargetA : code")
        .contains("OR ExistDemo.Data.TargetB : code");
    assertThat(structured.get("requiredIn")).asList().hasSize(2);
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

    var response = author.callHandler().apply(null,
        new McpSchema.CallToolRequest("authorIliPlausibilityConstraint", Map.of(
            "modelText", modelText,
            "context", "PlausDemo.Data.Item",
            "constraintName", "UsuallyHigh",
            "direction", "AT_LEAST",
            "percentage", 80,
            "rootNodeId", "root",
            "nodes", List.of(
                Map.of("id", "value", "kind", "ATTRIBUTE", "name", "value"),
                Map.of("id", "threshold", "kind", "NUMERIC", "value", 10),
                Map.of("id", "root", "kind", "COMPARE", "operator", ">=",
                    "children", List.of("value", "threshold"))))));

    Map<String, Object> structured = extractStructuredContent(response);
    assertThat(structured.get("generated")).isEqualTo(true);
    assertThat(structured.get("proofVerified")).isEqualTo(true);
    assertThat(structured.get("direction")).isEqualTo("AT_LEAST");
    assertThat(structured.get("percentage")).isEqualTo("80");
    assertThat(structured.get("updatedModelText").toString())
        .contains("!!@ name = \"UsuallyHigh\"")
        .contains(">= 80%")
        .contains("value >= 10");
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

    expectations.put("createAssociationSnippet", schema(Set.of("roles"), Set.of("name", "attrLines", "iliDoc", "metaAttributes")));
    expectations.put("createAttributeLine", schema(Set.of("req"), Set.of()));
    expectations.put("createClassSnippet", schema(Set.of("name"), Set.of("isAbstract", "extendsFqn", "oidDecl", "attrLines", "iliDoc", "metaAttributes")));
    expectations.put("createCoordDomainSnippet", schema(Set.of("name"), Set.of("dimension", "decimals", "iliDoc", "metaAttributes")));
    expectations.put("createEnumDomainSnippet", schema(Set.of("name"), Set.of("items", "itemSpecs", "iliDoc", "metaAttributes")));
    expectations.put("createEnumTreeDomainSnippet", schema(Set.of("name", "items"), Set.of("iliDoc", "metaAttributes")));
    expectations.put("createExistenceConstraint", schema(Set.of("refAttr", "classFqns"), Set.of("iliDoc", "metaAttributes")));
    expectations.put("createImportLine", schema(Set.of("modelName"), Set.of("qualified")));
    expectations.put("createMandatoryConstraint", schema(Set.of("expr"), Set.of("iliDoc", "metaAttributes")));
    expectations.put("createMetaAttributeBlock", schema(Set.of("metaAttributes"), Set.of()));
    expectations.put("createModelSnippet", schema(Set.of("name"), Set.of("lang", "uri", "version", "iliVersion", "imports", "includeSolothurnHeader", "iliDoc", "metaAttributes")));
    expectations.put("createNumericDomainSnippet", schema(Set.of("name", "min", "max"), Set.of("unitFqn", "iliDoc", "metaAttributes")));
    expectations.put("createPresentIfConstraint", schema(Set.of("attr", "cond"), Set.of("iliDoc", "metaAttributes")));
    expectations.put("createSetConstraint", schema(Set.of("expr"), Set.of("iliDoc", "metaAttributes")));
    expectations.put("createStructureAttributeLine", schema(Set.of("name", "structureFqn"), Set.of("mandatory", "collection", "iliDoc", "metaAttributes")));
    expectations.put("createStructureSnippet", schema(Set.of("name"), Set.of("isAbstract", "extendsFqn", "attrLines", "iliDoc", "metaAttributes")));
    expectations.put("createTopicSnippet", schema(Set.of("name"), Set.of("oidType", "isAbstract", "iliDoc", "metaAttributes")));
    expectations.put("createUniqueConstraint", schema(Set.of("attrs"), Set.of("iliDoc", "metaAttributes")));
    expectations.put("createUnitSnippet", schema(Set.of("name", "factor", "base"), Set.of("iliDoc", "metaAttributes")));
    expectations.put("createValueRangeConstraint", schema(Set.of("attr", "range"), Set.of("iliDoc", "metaAttributes")));
    expectations.put("ensureGeometryDependencies", schema(Set.of("attributeName"),
        Set.of("dimension", "arcs", "overlapMm", "chbase", "iliVersion", "geometryType", "directed", "mandatory", "collection")));
    expectations.put("formatIliModel", schema(Set.of("modelText"), Set.of("modelRepositories")));
    expectations.put("analyzeIliModel", schema(Set.of("modelText"), Set.of("modelRepositories", "modelPurpose")));
    expectations.put("checkModelingRules", schema(Set.of("modelText"), Set.of("modelPurpose", "modelRepositories", "ruleIds", "profile")));
    expectations.put("reviewIliModel", schema(Set.of("modelText"), Set.of("modelPurpose", "ruleProfile", "modelRepositories")));
    expectations.put("reviewIliChange", schema(Set.of("beforeModelText", "afterModelText"), Set.of("modelRepositories")));
    expectations.put("applyIliModelChange", schema(Set.of("modelText", "request"), Set.of("modelRepositories", "modelPurpose", "ruleProfile")));
    expectations.put("reviewIliConstraint", schema(Set.of("modelText", "constraint"), Set.of("modelRepositories")));
    expectations.put("generateIliConstraintCases", schema(Set.of("modelText", "constraint"), Set.of("modelRepositories")));
    expectations.put("generateIliConstraintFromDecisionTable", schema(Set.of("modelText", "context", "constraintName", "rows"), Set.of("modelRepositories")));
    expectations.put("authorIliMandatoryConstraint", schema(Set.of("modelText", "context", "constraintName", "rootNodeId", "nodes"), Set.of("modelRepositories")));
    expectations.put("authorIliPlausibilityConstraint", schema(Set.of("modelText", "context", "constraintName", "direction", "percentage", "rootNodeId", "nodes"), Set.of("modelRepositories")));
    expectations.put("authorIliExistenceConstraint", schema(Set.of("modelText", "context", "constraintName", "restrictedPath", "requiredIn"), Set.of("modelRepositories")));
    expectations.put("testIliConstraint", schema(Set.of("modelText", "constraint", "cases"), Set.of("modelRepositories")));
    expectations.put("findSimilarModels", schema(Set.of(), Set.of("query", "modelText", "modelPurpose", "limit")));
    expectations.put("indexConfiguredModels", schema(Set.of(), Set.of()));
    expectations.put("readModelExample", schema(Set.of("path"), Set.of()));
    expectations.put("listConstraintFunctions", schema(Set.of(), Set.of("iliVersion")));
    expectations.put("listGeometryTypes", schema(Set.of(), Set.of("iliVersion")));
    expectations.put("listMathFunctions", schema(Set.of(), Set.of("iliVersion")));
    expectations.put("listModelingRules", schema(Set.of(), Set.of("profile")));
    expectations.put("listTextFunctions", schema(Set.of(), Set.of("iliVersion")));
    expectations.put("resolveConstraintPath", schema(Set.of("modelText", "context", "path"), Set.of("modelRepositories")));
    expectations.put("renameModelElement", schema(Set.of("modelText", "elementFqn", "newName"), Set.of("expectedKind", "modelRepositories")));
    expectations.put("sanitizeIdentifier", schema(Set.of("value"), Set.of()));
    expectations.put("validateFqn", schema(Set.of("fqn"), Set.of()));
    expectations.put("validateIdentifier", schema(Set.of("value"), Set.of()));
    expectations.put("validateIliModel", schema(Set.of("modelText"), Set.of("modelRepositories")));
    expectations.put("generateExampleXtf", schema(Set.of("modelText"), Set.of("modelRepositories", "maxObjectsPerClass")));
    expectations.put("validateXtf", schema(Set.of("modelText", "xtfText"), Set.of("modelRepositories")));

    return expectations;
  }

  private static SchemaExpectation schema(Set<String> required, Set<String> optional) {
    return new SchemaExpectation(required, optional);
  }

  private record SchemaExpectation(Set<String> required, Set<String> optional) {
  }
}