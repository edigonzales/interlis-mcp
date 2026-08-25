package ch.so.agi.mcp;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("e2e")
public class StdioE2eTest {
    private static final String EXPECTED_PROJECT_VERSION = System.getProperty("expectedProjectVersion", "0.0.LOCALBUILD");

    private Process proc;
    private BufferedWriter toServer;
    private Thread stdoutPump;
    private Thread stderrPump;
    private final LinkedBlockingQueue<String> stdoutLines = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<String> stderrLines = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<Integer, String> pendingResponses = new ConcurrentHashMap<>();
    
    @BeforeEach
    void startServer() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(currentJavaBinary(), "-jar", "build/libs/interlis-mcp.jar");
        pb.redirectErrorStream(false);
        proc = pb.start();

        toServer = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));

        BufferedReader fromServer = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        BufferedReader fromErr = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8));

        stdoutPump = new Thread(() -> {
            try {
                for (String line; (line = fromServer.readLine()) != null; ) {
                    stdoutLines.offer(line);
                    // Uncomment if you want to see the raw lines during the test:
                    // System.out.println("[server stdout] " + line);
                }
            } catch (IOException ignored) {}
        }, "stdout-pump");
        stdoutPump.setDaemon(true);
        stdoutPump.start();

        stderrPump = new Thread(() -> {
            try {
                for (String line; (line = fromErr.readLine()) != null; ) {
                    stderrLines.offer(line);
                    System.err.println("[server stderr] " + line);
                }
            } catch (IOException ignored) {}
        }, "stderr-pump");
        stderrPump.setDaemon(true);
        stderrPump.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (toServer != null) {
            try { toServer.flush(); } catch (Exception ignored) {}
            try { proc.getOutputStream().close(); } catch (Exception ignored) {}
        }
        if (proc != null) {
            proc.waitFor(1, TimeUnit.SECONDS);
            if (proc.isAlive()) proc.destroy();
        }
    }

    @Test
    void initialize_listTools_and_authorAnnotatedModel() throws Exception {
        initializeSession();

        String toolsResp = listTools(2);
        assertContainsAll(toolsResp,
                "\"tools\"",
                "authorIliModel",
                "applyIliModelChanges",
                "authorIliUniqueConstraint",
                "renameModelElement",
                "analyzeIliModel",
                "checkModelingRules",
                "findSimilarModels",
                "generateExampleXtf",
                "validateXtf");
        assertFalse(toolsResp.contains("createModelSnippet"), toolsResp);
        assertFalse(toolsResp.contains("createAttributeLine"), toolsResp);
        assertFalse(toolsResp.contains("createUniqueConstraint"), toolsResp);
        assertFalse(toolsResp.contains("ensureGeometryDependencies"), toolsResp);

        String argsJson = "{"
                + "\"spec\":{"
                + "\"name\":\"DemoModel\","
                + "\"language\":\"de\","
                + "\"uri\":\"https://example.org/DemoModel\","
                + "\"version\":\"2026-08-24\","
                + "\"iliVersion\":\"2.4\","
                + "\"metaAttributes\":[{\"name\":\"title\",\"value\":\"Demo\"}]"
                + "},"
                + "\"modelPurpose\":\"CAPTURE\""
                + "}";

        String createResp = callTool(3, "authorIliModel", argsJson);
        assertSuccessfulToolResponse(
                createResp, "authorIliModel", "GENERATED", "INTERLIS 2.4", "MODEL DemoModel (de)", "!!@ title=", "Demo");
    }

    @Test
    void applyIliModelChanges_addsReferenceAttribute_overStdio() throws Exception {
        initializeSession();

        String modelText = "INTERLIS 2.4;\n\n"
                + "MODEL Demo (de) AT \"https://example.org/demo\" VERSION \"2026-08-24\" =\n"
                + "  TOPIC Topic =\n"
                + "    CLASS Target = END Target;\n"
                + "    CLASS Holder = END Holder;\n"
                + "  END Topic;\n"
                + "END Demo.\n";
        String argsJson = "{"
                + "\"modelText\":" + jsonString(modelText) + ","
                + "\"request\":{\"changes\":[{"
                + "\"operation\":\"ADD_ATTRIBUTE\","
                + "\"addAttribute\":{"
                + "\"containerFqn\":\"Demo.Topic.Holder\","
                + "\"attribute\":{\"name\":\"ziel\",\"typeSpec\":{"
                + "\"referenceType\":{\"targetClassFqn\":\"Demo.Topic.Target\",\"external\":false}"
                + "}}}}]},"
                + "\"modelPurpose\":\"CAPTURE\""
                + "}";

        String response = callTool(3, "applyIliModelChanges", argsJson);
        assertSuccessfulToolResponse(response,
                "applyIliModelChanges",
                "APPLIED",
                "ziel",
                "REFERENCE TO",
                "Demo.Topic.Target");
    }

    @Test
    void concurrentToolCalls_returnEveryResponseWithoutTransportErrors() throws Exception {
        initializeSession();

        send(authorIliModelRequest(2, "ConcurrentModelA"));
        send(authorIliModelRequest(3, "ConcurrentModelB"));

        String responseA = waitForResponseWithId(2, 15_000);
        String responseB = waitForResponseWithId(3, 15_000);
        assertNotNull(responseA, "Did not receive response for concurrent request 2");
        assertNotNull(responseB, "Did not receive response for concurrent request 3");
        assertSuccessfulToolResponse(responseA, "authorIliModel", "GENERATED", "ConcurrentModelA");
        assertSuccessfulToolResponse(responseB, "authorIliModel", "GENERATED", "ConcurrentModelB");
        assertNoTransportErrors();
    }

    @Test
    void concurrentPromptCalls_returnEveryResponseWithoutTransportErrors() throws Exception {
        initializeSession();

        send(promptRequest(2, "review-interlis-model", "PUBLICATION"));
        send(promptRequest(3, "review-interlis-model", "CAPTURE"));
        send(promptRequest(4, "review-interlis-model", "VALIDATION"));
        send(promptRequest(5, "review-interlis-model", "UNKNOWN"));

        for (int id = 2; id <= 5; id++) {
            String response = waitForResponseWithId(id, 15_000);
            assertNotNull(response, "Did not receive prompt response for request " + id);
            assertFalse(response.contains("\"error\""), response);
        }
        assertNoTransportErrors();
    }

    @Test
    void generateExampleXtf_and_validateXtf_overStdio() throws Exception {
        initializeSession();

        String modelText = """
                INTERLIS 2.4;

                MODEL DemoModel (de) AT "https://example.org/demo" VERSION "2024-01-31" =
                  TOPIC Data =
                    CLASS Building =
                      name : MANDATORY TEXT*20;
                    END Building;
                  END Data;
                END DemoModel.
                """;

        String generateArgsJson = "{"
                + "\"modelText\":" + jsonString(modelText) + ","
                + "\"maxObjectsPerClass\":1"
                + "}";

        String generateResponse = callTool(3, "generateExampleXtf", generateArgsJson);
        assertSuccessfulToolResponse(generateResponse,
                "generateExampleXtf",
                "\\\"generated\\\":true",
                "\\\"xtfText\\\"",
                "\\\"basketCount\\\":1",
                "\\\"objectCount\\\":1",
                "\\\"objectsByClass\\\"",
                "\\\"skippedClasses\\\"");

        String validateArgsJson = "{"
                + "\"modelText\":" + jsonString(modelText) + ","
                + "\"xtfText\":\"<TRANSFER>\""
                + "}";

        String validateResponse = callTool(4, "validateXtf", validateArgsJson);
        assertSuccessfulToolResponse(validateResponse,
                "validateXtf",
                "\\\"valid\\\":false",
                "\\\"messages\\\"",
                "\\\"errorCount\\\"",
                "\\\"warningCount\\\"");
    }

    @Test
    void authorIliModel_enumTreeDomain_recursivePayload_overStdio() throws Exception {
        initializeSession();

        String argsJson = "{"
                + "\"spec\":{\"name\":\"EnumTreeModel\",\"uri\":\"https://example.org/enum-tree\","
                + "\"version\":\"2026-08-24\",\"iliVersion\":\"2.4\",\"domains\":[{"
                + "\"name\":\"StatusTree\",\"kind\":\"ENUM_TREE\","
                + "\"enumTreeItems\":["
                + "{"
                + "\"name\":\"A\","
                + "\"iliDoc\":\"Elternwert\","
                + "\"metaAttributes\":[{\"name\":\"ili2db.dispName\",\"value\":\"Eltern\"}],"
                + "\"children\":["
                + "{\"name\":\"B\",\"metaAttributes\":[{\"name\":\"ili2db.dispName\",\"value\":\"Kind B\"}]},"
                + "{\"name\":\"C\"}"
                + "]"
                + "},"
                + "{\"name\":\"D\"}"
                + "]}]},\"modelPurpose\":\"CAPTURE\"}";

        String response = callTool(3, "authorIliModel", argsJson);
        assertSuccessfulToolResponse(response,
                "authorIliModel",
                "GENERATED",
                "StatusTree",
                "/**",
                "!!@ ili2db.dispName=",
                "A",
                "B",
                "D");
    }

    @Test
    void authorIliModel_enumDomain_withDispName_overStdio() throws Exception {
        initializeSession();

        String argsJson = "{"
                + "\"spec\":{\"name\":\"EnumModel\",\"uri\":\"https://example.org/enum\","
                + "\"version\":\"2026-08-24\",\"iliVersion\":\"2.4\",\"domains\":[{"
                + "\"name\":\"GebaeudeArt\",\"kind\":\"ENUM\","
                + "\"enumItems\":["
                + "{"
                + "\"name\":\"Wohnhaus\","
                + "\"iliDoc\":\"Wohngebaeude\","
                + "\"metaAttributes\":[{\"name\":\"ili2db.dispName\",\"value\":\"Wohngebaeude\"}]"
                + "},"
                + "{\"name\":\"Gewerbe\"}"
                + "]}]},\"modelPurpose\":\"CAPTURE\"}";

        String response = callTool(3, "authorIliModel", argsJson);
        assertSuccessfulToolResponse(response,
                "authorIliModel",
                "GENERATED",
                "GebaeudeArt",
                "!!@ ili2db.dispName=",
                "Wohnhaus",
                "Gewerbe");
    }

    @Test
    void authorIliModel_withTypedAssociation_overStdio() throws Exception {
        initializeSession();

        String argsJson = "{"
                + "\"spec\":{\"name\":\"AssociationModel\",\"uri\":\"https://example.org/association\","
                + "\"version\":\"2026-08-24\",\"iliVersion\":\"2.4\",\"topics\":[{\"name\":\"Topic\","
                + "\"classes\":[{\"name\":\"Source\"},{\"name\":\"Target\"}],"
                + "\"associations\":[{\"name\":\"Link\",\"roles\":["
                + "{"
                + "\"name\":\"from\","
                + "\"classFqn\":\"AssociationModel.Topic.Source\","
                + "\"cardinality\":{\"min\":0,\"max\":\"*\"}"
                + "},"
                + "{"
                + "\"name\":\"to\","
                + "\"classFqn\":\"AssociationModel.Topic.Target\","
                + "\"cardinality\":{\"min\":0,\"max\":\"1\"}"
                + "}"
                + "]}]}]},\"modelPurpose\":\"CAPTURE\"}";

        String response = callTool(3, "authorIliModel", argsJson);
        assertSuccessfulToolResponse(response,
                "authorIliModel",
                "GENERATED",
                "ASSOCIATION Link",
                "from -- {0..*} AssociationModel.Topic.Source",
                "to -- {0..1} AssociationModel.Topic.Target");
    }

    @Test
    void authorIliModel_withoutExplicitAssociationNames_returnsInvalidSpec() throws Exception {
        initializeSession();

        String argsJson = "{"
                + "\"spec\":{\"name\":\"BadAssociation\",\"uri\":\"https://example.org/bad\","
                + "\"version\":\"2026-08-24\",\"iliVersion\":\"2.4\",\"topics\":[{\"name\":\"Topic\","
                + "\"classes\":[{\"name\":\"Source\"},{\"name\":\"Target\"}],"
                + "\"associations\":[{\"roles\":["
                + "{\"classFqn\":\"BadAssociation.Topic.Source\",\"cardinality\":{\"min\":0,\"max\":\"*\"}},"
                + "{\"classFqn\":\"BadAssociation.Topic.Target\",\"cardinality\":{\"min\":0,\"max\":\"1\"}}"
                + "]}]}]},\"modelPurpose\":\"CAPTURE\"}";

        String response = callTool(3, "authorIliModel", argsJson);
        assertErrorToolResponse(response,
                "authorIliModel",
                "required property 'name' not found");
    }

    @Test
    void renameModelElement_classRename_overStdio() throws Exception {
        initializeSession();

        String modelText = """
                INTERLIS 2.4;

                MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-01" =
                  TOPIC Topic =
                    CLASS Target =
                      code : TEXT*20;
                    END Target;
                    CLASS Holder =
                      ref : REFERENCE TO Target;
                    END Holder;
                  END Topic;
                END Demo.
                """;

        String argsJson = "{"
                + "\"modelText\":" + jsonString(modelText) + ","
                + "\"elementFqn\":\"Demo.Topic.Target\","
                + "\"newName\":\"TargetRenamed\""
                + "}";

        String response = callTool(3, "renameModelElement", argsJson);
        assertSuccessfulToolResponse(response,
                "renameModelElement",
                "updatedModelText",
                "CLASS TargetRenamed",
                "REFERENCE TO Demo.Topic.TargetRenamed",
                "newElementFqn",
                "Demo.Topic.TargetRenamed");
    }

    @Test
    void renameModelElement_unknownElement_returnsError() throws Exception {
        initializeSession();

        String argsJson = "{"
                + "\"modelText\":" + jsonString(minimalRenameModel()) + ","
                + "\"elementFqn\":\"Demo.Topic.DoesNotExist\","
                + "\"newName\":\"TargetRenamed\""
                + "}";

        String response = callTool(3, "renameModelElement", argsJson);
        assertErrorToolResponse(response, "renameModelElement", "Element not found");
    }

    @Test
    void authorIliModel_duplicateMetaAttributes_returnsInvalidSpec() throws Exception {
        initializeSession();

        String argsJson = "{"
                + "\"spec\":{\"name\":\"Demo\",\"uri\":\"https://example.org/demo\","
                + "\"version\":\"2026-08-24\",\"iliVersion\":\"2.4\","
                + "\"metaAttributes\":["
                + "{\"name\":\"title\",\"value\":\"Demo\"},"
                + "{\"name\":\"title\",\"value\":\"Demo 2\"}"
                + "]},\"modelPurpose\":\"CAPTURE\"}";

        String response = callTool(3, "authorIliModel", argsJson);
        assertSuccessfulToolResponse(response, "authorIliModel", "INVALID_SPEC", "Duplicate meta attribute");
    }

    @Test
    void resources_prompts_and_agenticTools_overStdio() throws Exception {
        initializeSession();

        String resources = listResources(3);
        assertContainsAll(resources,
                "interlis://knowledge/handbook-rules",
                "interlis://knowledge/agent-workflow",
                "interlis://knowledge/model-corpus-index");

        String handbook = readResource(4, "interlis://knowledge/handbook-rules");
        assertContainsAll(handbook, "MDE-010", "MDE-020", "Modellzweck");

        String prompts = listPrompts(5);
        assertContainsAll(prompts,
                "interlis-modeling-agent",
                "review-interlis-model",
                "extend-interlis-model");

        String reviewPrompt = getPrompt(6, "review-interlis-model", "{\"modelPurpose\":\"PUBLICATION\"}");
        assertContainsAll(reviewPrompt, "analyzeIliModel", "checkModelingRules", "validateIliModel", "PUBLICATION");

        String modelText = """
                INTERLIS 2.4;

                MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
                  TOPIC Topic =
                    CLASS Thing =
                      name : TEXT*20;
                    END Thing;
                  END Topic;
                END Demo.
                """;

        String argsJson = "{"
                + "\"modelText\":" + jsonString(modelText) + ","
                + "\"modelPurpose\":\"PUBLICATION\","
                + "\"profile\":\"SO\""
                + "}";

        String analysis = callTool(7, "analyzeIliModel", argsJson);
        assertSuccessfulToolResponse(analysis, "analyzeIliModel", "valid", "true", "classes", "Thing");

        String ruleCheck = callTool(8, "checkModelingRules", argsJson);
        assertSuccessfulToolResponse(ruleCheck,
                "checkModelingRules",
                "SO",
                "validForAutomatedRules",
                "manualChecks",
                "MDE-060");
    }

    @Test
    void checkModelingRules_profileCore_overStdio() throws Exception {
        initializeSession();

        String modelText = """
                INTERLIS 2.4;

                MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-31" =
                  TOPIC Topic =
                    CLASS Thing =
                      name : TEXT*20;
                    END Thing;
                  END Topic;
                END Demo.
                """;

        String argsJson = "{"
                + "\"modelText\":" + jsonString(modelText) + ","
                + "\"modelPurpose\":\"CAPTURE\","
                + "\"profile\":\"CORE\","
                + "\"ruleIds\":[\"MDE-020\"]"
                + "}";

        String response = callTool(3, "checkModelingRules", argsJson);
        assertSuccessfulToolResponse(response,
                "checkModelingRules",
                "CORE",
                "validForAutomatedRules");
    }

    // ---- helpers ----

    private void initializeSession() throws Exception {
        final int initId = 1;

        send("{"
                + "\"jsonrpc\":\"2.0\","
                + "\"id\":" + initId + ","
                + "\"method\":\"initialize\","
                + "\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{"
                + "\"roots\":{\"listChanged\":true},"
                + "\"sampling\":{}"
                + "},"
                + "\"clientInfo\":{"
                + "\"name\":\"JUnitStdioClient\","
                + "\"version\":\"1.0.0\""
                + "}"
                + "}"
                + "}");

        String initResp = waitForResponseWithId(initId, 10_000);
        assertNotNull(initResp, "Did not receive initialize response");
        assertContainsAll(initResp, "serverInfo", "\"tools\"", "\"resources\"", "\"prompts\"", "\"version\":\"" + EXPECTED_PROJECT_VERSION + "\"");
        assertFalse(initResp.contains("\"completions\""),
                "initialize response should not advertise completion capabilities but was: " + initResp);

        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    }

    private String listTools(int id) throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/list\"}");
        String response = waitForResponseWithId(id, 10_000);
        assertNotNull(response, "Did not receive tools/list response");
        return response;
    }

    private String listResources(int id) throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"resources/list\"}");
        String response = waitForResponseWithId(id, 10_000);
        assertNotNull(response, "Did not receive resources/list response");
        return response;
    }

    private String readResource(int id, String uri) throws Exception {
        send("{"
                + "\"jsonrpc\":\"2.0\","
                + "\"id\":" + id + ","
                + "\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"" + uri + "\"}"
                + "}");
        String response = waitForResponseWithId(id, 10_000);
        assertNotNull(response, "Did not receive resources/read response");
        return response;
    }

    private String listPrompts(int id) throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"prompts/list\"}");
        String response = waitForResponseWithId(id, 10_000);
        assertNotNull(response, "Did not receive prompts/list response");
        return response;
    }

    private String getPrompt(int id, String promptName, String argumentsJson) throws Exception {
        send("{"
                + "\"jsonrpc\":\"2.0\","
                + "\"id\":" + id + ","
                + "\"method\":\"prompts/get\","
                + "\"params\":{"
                + "\"name\":\"" + promptName + "\","
                + "\"arguments\":" + argumentsJson
                + "}"
                + "}");
        String response = waitForResponseWithId(id, 10_000);
        assertNotNull(response, "Did not receive prompts/get response");
        return response;
    }

    private String callTool(int id, String toolName, String argumentsJson) throws Exception {
        send("{"
                + "\"jsonrpc\":\"2.0\","
                + "\"id\":" + id + ","
                + "\"method\":\"tools/call\","
                + "\"params\":{"
                + "\"name\":\"" + toolName + "\","
                + "\"arguments\":" + argumentsJson
                + "}"
                + "}");
        String response = waitForResponseWithId(id, 10_000);
        assertNotNull(response, "Did not receive " + toolName + " response");
        return response;
    }

    private void assertSuccessfulToolResponse(String response, String toolName, String... expectedSubstrings) {
        assertFalse(response.contains("\"error\""),
                toolName + " should not return a top-level MCP error but was: " + response);
        assertFalse(response.contains("\"isError\":true"),
                toolName + " should not return isError=true but was: " + response);
        assertContainsAll(response, expectedSubstrings);
    }

    private void assertErrorToolResponse(String response, String toolName, String expectedMessageSubstring) {
        assertTrue(response.contains("\"error\"") || response.contains("\"isError\":true"),
                toolName + " should return an MCP error or isError=true but was: " + response);
        assertTrue(response.contains(expectedMessageSubstring),
                toolName + " response should contain '" + expectedMessageSubstring + "' but was: " + response);
    }

    private void assertContainsAll(String response, String... expectedSubstrings) {
        for (String expectedSubstring : expectedSubstrings) {
            assertTrue(response.contains(expectedSubstring),
                    "Response should contain '" + expectedSubstring + "' but was: " + response);
        }
    }

    private void send(String oneLineJson) throws IOException {
        if (oneLineJson.contains("\n")) {
            throw new IllegalArgumentException("MCP stdio requires single-line JSON (no embedded newlines).");
        }
        toServer.write(oneLineJson);
        toServer.write('\n');
        toServer.flush();
        // Uncomment for debugging:
        // System.out.println("[client ->] " + oneLineJson);
    }

    private String authorIliModelRequest(int id, String name) {
        return "{"
                + "\"jsonrpc\":\"2.0\","
                + "\"id\":" + id + ","
                + "\"method\":\"tools/call\","
                + "\"params\":{"
                + "\"name\":\"authorIliModel\","
                + "\"arguments\":{"
                + "\"spec\":{\"name\":\"" + name + "\","
                + "\"language\":\"de\","
                + "\"uri\":\"https://example.org/" + name + "\","
                + "\"version\":\"2026-08-24\","
                + "\"iliVersion\":\"2.4\"},"
                + "\"modelPurpose\":\"CAPTURE\""
                + "}"
                + "}"
                + "}";
    }

    private String promptRequest(int id, String promptName, String modelPurpose) {
        return "{"
                + "\"jsonrpc\":\"2.0\","
                + "\"id\":" + id + ","
                + "\"method\":\"prompts/get\","
                + "\"params\":{"
                + "\"name\":\"" + promptName + "\","
                + "\"arguments\":{\"modelPurpose\":\"" + modelPurpose + "\"}"
                + "}"
                + "}";
    }

    private void assertNoTransportErrors() throws InterruptedException {
        Thread.sleep(200);
        String stderr = String.join("\n", stderrLines);
        assertFalse(stderr.contains("Failed to enqueue message"), stderr);
        assertFalse(stderr.contains("Operator called default onErrorDropped"), stderr);
        assertFalse(stderr.contains("MCP connection closed"), stderr);
    }

    private String waitForResponseWithId(int id, long timeoutMillis) throws InterruptedException {
        String pending = pendingResponses.remove(id);
        if (pending != null) {
            return pending;
        }
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String needle = "\"id\":" + id;
        while (System.currentTimeMillis() < deadline) {
            long remaining = Math.max(1, deadline - System.currentTimeMillis());
            String line = stdoutLines.poll(remaining, TimeUnit.MILLISECONDS);
            if (line == null) continue; // timeout slice; loop again
            // Ignore notifications and unrelated messages; look for our id
            if (line.contains("\"jsonrpc\":\"2.0\"") && line.contains(needle)
                    && (line.contains("\"result\"") || line.contains("\"error\""))) {
                return line;
            }
            if (line.contains("\"jsonrpc\":\"2.0\"")
                    && (line.contains("\"result\"") || line.contains("\"error\""))) {
                Integer responseId = responseId(line);
                if (responseId != null) {
                    pendingResponses.put(responseId, line);
                }
            }
            // Otherwise keep waiting; other messages (e.g., pings/notifications) may arrive.
        }
        return null;
    }

    private Integer responseId(String line) {
        int marker = line.indexOf("\"id\":");
        if (marker < 0) {
            return null;
        }
        int start = marker + "\"id\":".length();
        int end = start;
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        if (start == end) {
            return null;
        }
        return Integer.valueOf(line.substring(start, end));
    }

    private String jsonString(String raw) {
        return "\""
                + raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                + "\"";
    }

    private String minimalRenameModel() {
        return """
                INTERLIS 2.4;

                MODEL Demo (de) AT "https://example.org/demo" VERSION "2024-01-01" =
                  TOPIC Topic =
                    CLASS Target =
                    END Target;
                  END Topic;
                END Demo.
                """;
    }

    private String currentJavaBinary() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}
