package ch.so.agi.mcp;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.LinkedBlockingQueue;
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
                    // Log noise from server; not used for assertions.
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
    void initialize_listTools_createModelSnippet_and_metaAttributeBlock() throws Exception {
        initializeSession();

        String toolsResp = listTools(2);
        assertContainsAll(toolsResp,
                "\"tools\"",
                "createModelSnippet",
                "createAttributeLine",
                "createEnumTreeDomainSnippet",
                "createMetaAttributeBlock",
                "renameModelElement");

        String today = LocalDate.now().toString();
        String argsJson = "{"
                + "\"name\":\"DemoModel\"," 
                + "\"lang\":\"de\"," 
                + "\"uri\":\"https://example.org/DemoModel\"," 
                + "\"version\":\"" + today + "\"," 
                + "\"iliVersion\":\"2.4\"," 
                + "\"imports\":[\"INTERLIS\"]," 
                + "\"includeSolothurnHeader\":false"
                + "}";

        String createResp = callTool(3, "createModelSnippet", argsJson);
        assertSuccessfulToolResponse(createResp, "createModelSnippet", "INTERLIS 2.4", "MODEL DemoModel (de)");

        String metaArgsJson = "{"
                + "\"metaAttributes\":["
                + "{\"name\":\"title\",\"value\":\"Demo\"},"
                + "{\"name\":\"ch.so.flag\",\"rawValue\":\"TRUE\"}"
                + "]"
                + "}";

        String metaResp = callTool(4, "createMetaAttributeBlock", metaArgsJson);
        assertSuccessfulToolResponse(metaResp, "createMetaAttributeBlock", "!!@ title=", "Demo", "!!@ ch.so.flag=TRUE");
    }

    @Test
    void createAttributeLine_referenceType_overStdio() throws Exception {
        initializeSession();

        String argsJson = "{"
                + "\"req\":{"
                + "\"name\":\"ziel\","
                + "\"typeSpec\":{"
                + "\"referenceType\":{"
                + "\"targetClassFqn\":\"Demo.Topic.Target\","
                + "\"external\":true"
                + "}"
                + "}"
                + "}"
                + "}";

        String response = callTool(3, "createAttributeLine", argsJson);
        assertSuccessfulToolResponse(response,
                "createAttributeLine",
                "ziel",
                "REFERENCE TO",
                "EXTERNAL",
                "Demo.Topic.Target");
    }

    @Test
    void createEnumTreeDomainSnippet_recursivePayload_overStdio() throws Exception {
        initializeSession();

        String argsJson = "{"
                + "\"name\":\"StatusTree\","
                + "\"items\":["
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
                + "]"
                + "}";

        String response = callTool(3, "createEnumTreeDomainSnippet", argsJson);
        assertSuccessfulToolResponse(response,
                "createEnumTreeDomainSnippet",
                "StatusTree",
                "/**",
                "!!@ ili2db.dispName=",
                "A",
                "B",
                "D");
    }

    @Test
    void createEnumDomainSnippet_itemSpecs_withDispName_overStdio() throws Exception {
        initializeSession();

        String argsJson = "{"
                + "\"name\":\"GebaeudeArt\","
                + "\"itemSpecs\":["
                + "{"
                + "\"name\":\"Wohnhaus\","
                + "\"iliDoc\":\"Wohngebaeude\","
                + "\"metaAttributes\":[{\"name\":\"ili2db.dispName\",\"value\":\"Wohngebaeude\"}]"
                + "},"
                + "{\"name\":\"Gewerbe\"}"
                + "]"
                + "}";

        String response = callTool(3, "createEnumDomainSnippet", argsJson);
        assertSuccessfulToolResponse(response,
                "createEnumDomainSnippet",
                "GebaeudeArt",
                "!!@ ili2db.dispName=",
                "Wohnhaus",
                "Gewerbe");
    }

    @Test
    void createAssociationSnippet_withExternalRole_overStdio() throws Exception {
        initializeSession();

        String argsJson = "{"
                + "\"name\":\"Link\","
                + "\"roles\":["
                + "{"
                + "\"name\":\"from\","
                + "\"classFQN\":\"Demo.Topic.Source\","
                + "\"card\":\"{1}\","
                + "\"external\":true"
                + "},"
                + "{"
                + "\"name\":\"to\","
                + "\"classFQN\":\"Demo.Topic.Target\","
                + "\"card\":\"{0..*}\""
                + "}"
                + "]"
                + "}";

        String response = callTool(3, "createAssociationSnippet", argsJson);
        assertSuccessfulToolResponse(response,
                "createAssociationSnippet",
                "ASSOCIATION Link",
                "from (EXTERNAL) -- {1} Demo.Topic.Source",
                "to -- {0..*} Demo.Topic.Target");
    }

    @Test
    void createAssociationSnippet_withRelationshipAttributes_overStdio() throws Exception {
        initializeSession();

        String argsJson = "{"
                + "\"name\":\"Link\","
                + "\"roles\":["
                + "{\"name\":\"from\",\"classFQN\":\"Demo.Topic.Source\"},"
                + "{\"name\":\"to\",\"classFQN\":\"Demo.Topic.Target\",\"card\":\"{0..1}\"}"
                + "],"
                + "\"attrLines\":[\"/** Beziehungscode */\\ncode : TEXT*20;\"]"
                + "}";

        String response = callTool(3, "createAssociationSnippet", argsJson);
        assertSuccessfulToolResponse(response,
                "createAssociationSnippet",
                "ASSOCIATION Link",
                "ATTRIBUTE",
                "/** Beziehungscode */",
                "code : TEXT*20;");
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
    void createMetaAttributeBlock_duplicateMetaAttributes_returnsError() throws Exception {
        initializeSession();

        String argsJson = "{"
                + "\"metaAttributes\":["
                + "{\"name\":\"title\",\"value\":\"Demo\"},"
                + "{\"name\":\"title\",\"value\":\"Demo 2\"}"
                + "]"
                + "}";

        String response = callTool(3, "createMetaAttributeBlock", argsJson);
        assertErrorToolResponse(response, "createMetaAttributeBlock", "Duplicate meta attribute");
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
        assertContainsAll(initResp, "serverInfo", "\"tools\"", "\"version\":\"" + EXPECTED_PROJECT_VERSION + "\"");
        assertFalse(initResp.contains("\"resources\""),
                "initialize response should not advertise resource capabilities but was: " + initResp);
        assertFalse(initResp.contains("\"prompts\""),
                "initialize response should not advertise prompt capabilities but was: " + initResp);
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

    private String waitForResponseWithId(int id, long timeoutMillis) throws InterruptedException {
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
            // Otherwise keep waiting; other messages (e.g., pings/notifications) may arrive.
        }
        return null;
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
