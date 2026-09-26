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
