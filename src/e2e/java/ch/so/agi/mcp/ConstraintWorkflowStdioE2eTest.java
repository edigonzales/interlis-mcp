package ch.so.agi.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("e2e")
class ConstraintWorkflowStdioE2eTest {

  private Process process;
  private BufferedWriter toServer;
  private Thread stdoutPump;
  private Thread stderrPump;
  private final LinkedBlockingQueue<String> stdoutLines = new LinkedBlockingQueue<>();

  @BeforeEach
  void startServer() throws Exception {
    process = new ProcessBuilder(currentJavaBinary(), "-jar", "build/libs/interlis-mcp.jar")
        .redirectErrorStream(false)
        .start();
    toServer = new BufferedWriter(new OutputStreamWriter(
        process.getOutputStream(), StandardCharsets.UTF_8));

    BufferedReader stdout = new BufferedReader(new InputStreamReader(
        process.getInputStream(), StandardCharsets.UTF_8));
    BufferedReader stderr = new BufferedReader(new InputStreamReader(
        process.getErrorStream(), StandardCharsets.UTF_8));

    stdoutPump = new Thread(() -> pumpStdout(stdout), "constraint-workflow-stdout");
    stdoutPump.setDaemon(true);
    stdoutPump.start();

    stderrPump = new Thread(() -> pumpStderr(stderr), "constraint-workflow-stderr");
    stderrPump.setDaemon(true);
    stderrPump.start();
  }

  @AfterEach
  void stopServer() throws Exception {
    if (toServer != null) {
      try {
        toServer.flush();
      } catch (Exception ignored) {
      }
      try {
        process.getOutputStream().close();
      } catch (Exception ignored) {
      }
    }
    if (process != null) {
      process.waitFor(1, TimeUnit.SECONDS);
      if (process.isAlive()) {
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(2, TimeUnit.SECONDS);
        }
      }
    }
  }

  @Test
  void constraintWorkflowResourceAndPromptAreExposedOverStdio() throws Exception {
    initializeSession();

    String resource = readResource(2, "interlis://knowledge/constraint-workflow");
    assertContainsAll(
        resource,
        "authorIliMandatoryConstraint",
        "authorIliUniqueConstraint",
        "authorIliExistenceConstraint",
        "authorIliPlausibilityConstraint",
        "authorIliSetConstraint",
        "generateIliConstraintCases",
        "afterReview",
        "proofVerified",
        "generationVerified");

    String prompt = getPrompt(
        3,
        "author-interlis-constraint",
        "{\"constraintKind\":\"SET\"}");
    assertContainsAll(
        prompt,
        "SET",
        "authorIliSetConstraint",
        "authorIliUniqueConstraint",
        "generateIliConstraintCases",
        "proofVerified=true",
        "afterReview");
  }

  @Test
  void typedSetAuthoringRunsSourcePreservingRoundTripAndValidatorProofOverStdio() throws Exception {
    initializeSession();

    String modelText = """
        INTERLIS 2.4;

        MODEL SetWorkflowE2e (en) AT "https://example.org" VERSION "2026-08-19" =
          TOPIC Data =
            CLASS Item =
              value : MANDATORY 0 .. 10;
            END Item;
          END Data;
        END SetWorkflowE2e.
        """;

    String arguments = "{"
        + "\"modelText\":" + jsonString(modelText) + ","
        + "\"contextFqn\":\"SetWorkflowE2e.Data.Item\","
        + "\"spec\":{"
        + "\"kind\":\"SET\","
        + "\"name\":\"AtLeastTwoHigh\","
        + "\"scope\":\"GLOBAL\","
        + "\"where\":{"
        + "\"kind\":\"COMPARE\","
        + "\"operator\":\">=\","
        + "\"children\":["
        + "{\"kind\":\"ATTRIBUTE\",\"name\":\"value\"},"
        + "{\"kind\":\"NUMERIC\",\"value\":5}"
        + "]},"
        + "\"condition\":{"
        + "\"kind\":\"OBJECT_COUNT\","
        + "\"objects\":{\"kind\":\"ALL\"},"
        + "\"operator\":\">=\","
        + "\"threshold\":2}"
        + "}"
        + "}";

    String response = callTool(2, "authorIliSetConstraint", arguments);
    assertFalse(response.contains("\"error\""), response);
    assertFalse(response.contains("\"isError\":true"), response);
    assertContainsAll(
        response,
        "\\\"generated\\\":true",
        "\\\"proofVerified\\\":true",
        "\\\"generationVerified\\\":true",
        "\\\"updatedModelText\\\"",
        "\\\"semanticDiff\\\"",
        "\\\"afterReview\\\"",
        "SET CONSTRAINT WHERE (value >= 5):",
        "INTERLIS.objectCount(ALL)",
        "AtLeastTwoHigh");
  }

  @Test
  void authoringContractGuidanceAndDiagnosticsSurviveStdio() throws Exception {
    initializeSession();
    send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
    String catalog=waitForResponseWithId(2,15_000);
    assertNotNull(catalog);
    assertContainsAll(catalog,"COLLECTION_SUM","Regel42","specDiagnostics","oneOf","threshold");
    String arguments="""
        {"modelText":"INTERLIS 2.3;","contextFqn":"Demo.Data.Item","spec":{"kind":"MANDATORY",
        "name":"Rule","condition":{"kind":"FUNCTION","name":"Math.sum","functionOrigin":"STANDARD","children":[]}}}
        """.replace("\n", "");
    String response=callTool(3,"authorIliMandatoryConstraint",arguments);
    assertFalse(response.contains("\"isError\":true"),response);
    var mapper=new tools.jackson.databind.ObjectMapper();
    var envelope=mapper.readTree(response).get("result");
    var result=envelope.get("structuredContent");
    if (result==null) result=mapper.readTree(envelope.get("content").get(0).get("text").asText());
    assertTrue(result.get("status").asText().equals("INVALID_SPEC"),response);
    var diagnostic=result.get("specDiagnostics").get(0);
    assertTrue(diagnostic.get("code").asText().equals("UNKNOWN_STANDARD_FUNCTION"),response);
    assertTrue(diagnostic.get("path").asText().equals("/spec/condition/name"),response);
    assertTrue(diagnostic.get("hint").asText().contains("COLLECTION_SUM"),response);
  }

  @Test
  void malformedScalarInputsAreSmallSchemaErrorsBeforeTheHandler() throws Exception {
    initializeSession();
    var mapper=new tools.jackson.databind.ObjectMapper();
    int id=2;
    var number=java.util.Map.of("kind","NUMERIC","value",7);
    var attribute=java.util.Map.of("kind","ATTRIBUTE","name","value");
    var conditions=java.util.List.of(
        java.util.Map.of("kind","COMPARE","operator",">=","children",java.util.List.of(attribute,java.util.Map.of("kind","NUMBER","value",7))),
        java.util.Map.of("kind","COMPARE","operator","=","children",java.util.List.of(attribute,number)),
        java.util.Map.of("kind","COMPARE","operator",">=","children",java.util.List.of(attribute,number)));
    var paths=java.util.List.of("/condition/children/1/kind", "/condition/operator", "/name");
    for(int i=0;i<conditions.size();i++) {
      var spec=java.util.Map.of("kind","MANDATORY","name",i==2?"1Rule":"Rule","condition",conditions.get(i));
      for(String tool:java.util.List.of("authorIliMandatoryConstraint","applyIliModelChanges")) {
        var args=new java.util.LinkedHashMap<String,Object>();args.put("modelText","INTERLIS 2.4;");
        if(tool.equals("authorIliMandatoryConstraint")){args.put("contextFqn","Example.Data.Item");args.put("spec",spec);}
        else args.put("request",java.util.Map.of("changes",java.util.List.of(java.util.Map.of("operation","ADD_CONSTRAINT","addConstraint",java.util.Map.of("containerFqn","Example.Data.Item","constraint",spec)))));
        String response=callTool(id++,tool,mapper.writeValueAsString(args));
        assertTrue(response.getBytes(StandardCharsets.UTF_8).length<8192,response);
        var envelope=mapper.readTree(response).get("result");
        assertTrue(envelope.get("isError").asBoolean(),response);
        assertContainsAll(response,"input validation failed",paths.get(i));
        assertFalse(response.contains("INVALID_SPEC"),response);
      }
    }
  }

  @Test
  void publishedMandatoryExampleAndExpectedDefinednessSurviveStdio() throws Exception {
    initializeSession();
    var mapper = new tools.jackson.databind.ObjectMapper();
    send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
    var catalog = mapper.readTree(waitForResponseWithId(2, 15_000)).get("result").get("tools");
    String example = ch.so.agi.mcp.model.ConstraintAuthoringGuidance.MANDATORY_SPEC_EXAMPLE;
    boolean found = false;
    for (var tool : catalog) {
      if (tool.get("name").asText().equals("authorIliMandatoryConstraint")) {
        assertContainsAll(tool.get("description").asText(), "spec.condition", "children", example);
        found = true;
      }
    }
    assertTrue(found);
    String prompt = getPrompt(3, "author-interlis-constraint", "{\"constraintKind\":\"MANDATORY\"}");
    assertContainsAll(mapper.readTree(prompt).toString(), "requiresUserDecision=true", "Abschlussantwort");
    String source = "INTERLIS 2.4; MODEL Example (en) AT \"https://example.org\" VERSION \"1\" = "
        + "TOPIC Data = CLASS Item = value : 0..10; END Item; END Data; END Example.";
    var cases = java.util.List.of(
        java.util.Map.of("name", "missing", "expectedConstraintValid", false, "objects", java.util.List.of(
            java.util.Map.of("classFqn", "Example.Data.Item", "oid", "i", "values", java.util.Map.of()))),
        java.util.Map.of("name", "present", "expectedConstraintValid", true, "objects", java.util.List.of(
            java.util.Map.of("classFqn", "Example.Data.Item", "oid", "i", "values", java.util.Map.of("value", 4)))));
    var authorEnvelope = mapper.readTree(callTool(4, "authorIliMandatoryConstraint", mapper.writeValueAsString(
        java.util.Map.of("modelText", source, "contextFqn", "Example.Data.Item", "spec", mapper.readTree(example),
            "includeSuccessfulTestXtf", false)))).get("result");
    var author = authorEnvelope.get("structuredContent");
    if (author == null) author = mapper.readTree(authorEnvelope.get("content").get(0).get("text").asText());
    assertTrue(author.get("status").asText().equals("GENERATED"));
    assertTrue(author.get("requiresUserDecision").asBoolean());
    var testEnvelope = mapper.readTree(callTool(5, "testIliConstraint", mapper.writeValueAsString(java.util.Map.of(
        "modelText", author.get("updatedModelText").asText(), "constraint", "Example.Data.Item.ValueRequired",
        "cases", cases, "includeSuccessfulTestXtf", false)))).get("result");
    var tested = testEnvelope.get("structuredContent");
    if (tested == null) tested = mapper.readTree(testEnvelope.get("content").get(0).get("text").asText());
    assertTrue(tested.get("allPassed").asBoolean());
    assertTrue(tested.get("modelHashes").get("model").asText().equals(author.get("modelHashes").get("after").asText()));
    assertTrue(author.get("modelHashes").get("before").asText().equals(ch.so.agi.mcp.model.ModelHashes.sha256(source)));
    for (var testCase : tested.get("cases")) {
      assertTrue(testCase.get("fixtureValid").asBoolean());
      assertTrue(testCase.get("constraintExercised").asBoolean());
    }
  }

  @Test
  void proseContextAuthoringAndIndependentExpectationsRoundTrip() throws Exception {
    initializeSession();
    var mapper = new tools.jackson.databind.ObjectMapper();
    String source = "INTERLIS 2.4; MODEL Prose (en) AT \"https://example.org\" VERSION \"1\" = TOPIC Data = CLASS Person = age : MANDATORY 0..100; END Person; END Data; END Prose.";
    String context = callTool(2, "analyzeIliModel", mapper.writeValueAsString(java.util.Map.of("modelText", source, "contextFqn", "Prose.Data.Person")));
    assertContainsAll(context, "authoringContext", "AVAILABLE", "minimum", "maximum", "modelHashes");
    String payload = "{\"modelText\":" + jsonString(source) + ",\"contextFqn\":\"Prose.Data.Person\",\"spec\":{\"kind\":\"MANDATORY\",\"name\":\"Adult\",\"condition\":{\"kind\":\"COMPARE\",\"operator\":\">=\",\"children\":[{\"kind\":\"ATTRIBUTE\",\"name\":\"age\"},{\"kind\":\"NUMERIC\",\"value\":18}]}}}";
    payload = payload.substring(0, payload.length()-1) + ",\"includeSuccessfulTestXtf\":false}";
    String authoring = callTool(3, "authorIliMandatoryConstraint", payload);
    var envelope = mapper.readTree(authoring).get("result");
    var result = envelope.get("structuredContent");
    if (result == null) result = mapper.readTree(envelope.get("content").get(0).get("text").asText());
    assertTrue(result.get("status").asText().equals("GENERATED"), authoring);
    assertTrue(result.get("constraintProofs").get(0).get("explanation").get("description").asText().contains("mindestens 18"), authoring);
    assertTrue(result.get("modelHashes").get("before").asText().equals(ch.so.agi.mcp.model.ModelHashes.sha256(source)), authoring);
    assertTrue(result.get("omittedSuccessfulTestXtfCount").asInt() > 0, authoring);
    var cases = new java.util.ArrayList<java.util.Map<String,Object>>();
    for (int age : new int[]{17,18,19}) cases.add(java.util.Map.of("name", "age" + age,
        "expectationSource", "USER_PROVIDED", "expectedConstraintValid", age >= 18,
        "objects", java.util.List.of(java.util.Map.of("classFqn", "Prose.Data.Person", "oid", "p", "values", java.util.Map.of("age", age)))));
    String tested = callTool(4, "testIliConstraint", mapper.writeValueAsString(java.util.Map.of("modelText", result.get("updatedModelText").asText(), "constraint", "Prose.Data.Person.Adult", "cases", cases, "includeSuccessfulTestXtf", false)));
    assertContainsAll(tested, "USER_PROVIDED", "CALLER_SUPPLIED_EXPECTATIONS", "allPassed");
    var testEnvelope = mapper.readTree(tested).get("result");
    var testResult = testEnvelope.get("structuredContent");
    if (testResult == null) testResult = mapper.readTree(testEnvelope.get("content").get(0).get("text").asText());
    assertTrue(testResult.get("allPassed").asBoolean(), tested);
    assertTrue(testResult.get("modelHashes").get("model").asText().equals(result.get("modelHashes").get("after").asText()), tested);
    assertTrue(testResult.get("omittedSuccessfulTestXtfCount").asInt() == 3, tested);
    assertFalse(testResult.get("cases").get(0).has("xtfText"), tested);
  }

  @Test
  void guardedTextPresenceProofAndExplicitExpectationsShareModelHash() throws Exception {
    initializeSession();
    var mapper = new tools.jackson.databind.ObjectMapper();
    String source = """
        INTERLIS 2.4;
        MODEL TextWorkflow (en) AT "https://example.org" VERSION "1" =
          TOPIC Data =
            CLASS Item =
              state : (active, inactive);
              code : TEXT*20;
            END Item;
          END Data;
        END TextWorkflow.
        """;
    var spec = mapper.readTree("""
        {"kind":"MANDATORY","name":"Presence","condition":{"kind":"IMPLIES","children":[
          {"kind":"AND","children":[{"kind":"DEFINED","children":[{"kind":"ATTRIBUTE","name":"state"}]},
            {"kind":"COMPARE","operator":"==","children":[{"kind":"ATTRIBUTE","name":"state"},{"kind":"ENUM","value":"inactive"}]}]},
          {"kind":"DEFINED","children":[{"kind":"ATTRIBUTE","name":"code"}]}]}}
        """);
    var cases = new java.util.ArrayList<java.util.Map<String, Object>>();
    for (String state : java.util.List.of("active", "inactive", "missing")) {
      for (boolean present : java.util.List.of(false, true)) {
        var values = new java.util.LinkedHashMap<String, Object>();
        if (!state.equals("missing")) values.put("state", state);
        if (present) values.put("code", "Abc");
        cases.add(java.util.Map.of("name", state + "_" + present, "expectedConstraintValid", !state.equals("inactive") || present,
            "objects", java.util.List.of(java.util.Map.of("classFqn", "TextWorkflow.Data.Item", "oid", "i", "values", values))));
      }
    }
    var envelope = mapper.readTree(callTool(2, "authorIliMandatoryConstraint", mapper.writeValueAsString(java.util.Map.of(
        "modelText", source, "contextFqn", "TextWorkflow.Data.Item", "spec", spec, "includeSuccessfulTestXtf", false)))).get("result");
    var author = envelope.get("structuredContent");
    if (author == null) author = mapper.readTree(envelope.get("content").get(0).get("text").asText());
    assertTrue(author.get("status").asText().equals("GENERATED"), author.toString());
    assertTrue(author.get("proofVerified").asBoolean());
    var proof = author.get("constraintProofs").get(0);
    assertTrue(proof.get("coverageComplete").asBoolean());
    assertTrue(proof.get("coverageGaps").isEmpty());
    boolean excluded = false;
    for (var goal : proof.get("coverageExcludedGoals")) {
      if (goal.get("reason").asText().equals("OR result undefined")) {
        assertTrue(goal.get("reasonCode").asText().equals("PROVEN_UNREACHABLE"));
        assertTrue(goal.get("justification").asText().contains("presence only"));
        excluded = true;
      }
    }
    assertTrue(excluded);
    var testedEnvelope = mapper.readTree(callTool(3, "testIliConstraint", mapper.writeValueAsString(java.util.Map.of(
        "modelText", author.get("updatedModelText").asText(), "constraint", "TextWorkflow.Data.Item.Presence",
        "cases", cases, "includeSuccessfulTestXtf", false)))).get("result");
    var tested = testedEnvelope.get("structuredContent");
    if (tested == null) tested = mapper.readTree(testedEnvelope.get("content").get(0).get("text").asText());
    assertTrue(tested.get("allPassed").asBoolean(), tested.toString());
    assertTrue(tested.get("modelHashes").get("model").asText().equals(author.get("modelHashes").get("after").asText()));
    assertTrue(author.get("modelHashes").get("before").asText().equals(ch.so.agi.mcp.model.ModelHashes.sha256(source)));
    for (var item : tested.get("cases")) {
      assertTrue(item.get("fixtureValid").asBoolean());
      assertTrue(item.get("constraintExercised").asBoolean());
    }
  }

  private void initializeSession() throws Exception {
    send("{"
        + "\"jsonrpc\":\"2.0\","
        + "\"id\":1,"
        + "\"method\":\"initialize\","
        + "\"params\":{"
        + "\"protocolVersion\":\"2025-06-18\","
        + "\"capabilities\":{\"roots\":{\"listChanged\":true},\"sampling\":{}},"
        + "\"clientInfo\":{\"name\":\"ConstraintWorkflowE2e\",\"version\":\"1.0.0\"}"
        + "}"
        + "}");
    String response = waitForResponseWithId(1, 15_000);
    assertNotNull(response, "Did not receive initialize response");
    assertContainsAll(response, "serverInfo", "\"tools\"", "\"resources\"", "\"prompts\"");
    send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
  }

  private String readResource(int id, String uri) throws Exception {
    send("{"
        + "\"jsonrpc\":\"2.0\","
        + "\"id\":" + id + ","
        + "\"method\":\"resources/read\","
        + "\"params\":{\"uri\":\"" + uri + "\"}"
        + "}");
    String response = waitForResponseWithId(id, 15_000);
    assertNotNull(response, "Did not receive resources/read response");
    return response;
  }

  private String getPrompt(int id, String name, String argumentsJson) throws Exception {
    send("{"
        + "\"jsonrpc\":\"2.0\","
        + "\"id\":" + id + ","
        + "\"method\":\"prompts/get\","
        + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + argumentsJson + "}"
        + "}");
    String response = waitForResponseWithId(id, 15_000);
    assertNotNull(response, "Did not receive prompts/get response");
    return response;
  }

  private String callTool(int id, String name, String argumentsJson) throws Exception {
    send("{"
        + "\"jsonrpc\":\"2.0\","
        + "\"id\":" + id + ","
        + "\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + argumentsJson + "}"
        + "}");
    String response = waitForResponseWithId(id, 30_000);
    assertNotNull(response, "Did not receive tools/call response for " + name);
    return response;
  }

  private void send(String line) throws IOException {
    if (line.contains("\n")) {
      throw new IllegalArgumentException("MCP stdio requires single-line JSON.");
    }
    toServer.write(line);
    toServer.newLine();
    toServer.flush();
  }

  private String waitForResponseWithId(int id, long timeoutMillis) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    String idToken = "\"id\":" + id;
    while (System.currentTimeMillis() < deadline) {
      long remaining = Math.max(1, deadline - System.currentTimeMillis());
      String line = stdoutLines.poll(remaining, TimeUnit.MILLISECONDS);
      if (line == null) {
        continue;
      }
      if (line.contains("\"jsonrpc\":\"2.0\"")
          && line.contains(idToken)
          && (line.contains("\"result\"") || line.contains("\"error\""))) {
        return line;
      }
    }
    return null;
  }

  private String jsonString(String value) {
    return "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n") + "\"";
  }

  private void assertContainsAll(String text, String... fragments) {
    for (String fragment : fragments) {
      assertTrue(text.contains(fragment), "Expected fragment '" + fragment + "' in: " + text);
    }
  }

  private String currentJavaBinary() {
    String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
        ? "java.exe"
        : "java";
    return Path.of(System.getProperty("java.home"), "bin", executable).toString();
  }

  private void pumpStdout(BufferedReader stdout) {
    try {
      for (String line; (line = stdout.readLine()) != null;) {
        stdoutLines.offer(line);
      }
    } catch (IOException ignored) {
    }
  }

  private void pumpStderr(BufferedReader stderr) {
    try {
      for (String line; (line = stderr.readLine()) != null;) {
        System.err.println("[constraint-workflow server stderr] " + line);
      }
    } catch (IOException ignored) {
    }
  }
}
