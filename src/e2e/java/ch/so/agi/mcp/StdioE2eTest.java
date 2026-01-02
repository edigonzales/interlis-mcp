package ch.so.agi.mcp;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("e2e")
public class StdioE2eTest {

    private Process proc;
    private BufferedWriter toServer;
    private Thread stdoutPump;
    private Thread stderrPump;
    private final LinkedBlockingQueue<String> stdoutLines = new LinkedBlockingQueue<>();
    
    @BeforeEach
    void startServer() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("java", "-jar", "build/libs/interlis-mcp.jar");
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
    void initialize_listTools_createModelSnippet() throws Exception {
        // ---- IDs we'll use ----
        final int initId = 1;
        final int toolsListId = 2;
        final int createModelId = 3;

        // ---- 1) initialize ----
        String initialize = "{"
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
              + "}";
        send(initialize);

        // Wait for the init response (up to 10s), assert it contains "serverInfo"
        String initResp = waitForResponseWithId(initId, 10_000);
        assertNotNull(initResp, "Did not receive initialize response");
        assertTrue(initResp.contains("serverInfo"),
                "initialize response should contain 'serverInfo' but was: " + initResp);

        // ---- 2) notifications/initialized + tools/list ----
        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        String toolsList = "{\"jsonrpc\":\"2.0\",\"id\":" + toolsListId + ",\"method\":\"tools/list\"}";
        send(toolsList);

        String toolsResp = waitForResponseWithId(toolsListId, 10_000);
        assertNotNull(toolsResp, "Did not receive tools/list response");
        assertTrue(toolsResp.contains("\"tools\""),
                "tools/list response should contain 'tools' but was: " + toolsResp);

        // ---- 3) tools/call createModelSnippet ----
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

        String createModelCall =
                "{"
                  + "\"jsonrpc\":\"2.0\","
                  + "\"id\":" + createModelId + ","
                  + "\"method\":\"tools/call\","
                  + "\"params\":{"
                    + "\"name\":\"createModelSnippet\","
                    + "\"arguments\":" + argsJson
                  + "}"
                + "}";

        send(createModelCall);

        String createResp = waitForResponseWithId(createModelId, 10_000);
        assertNotNull(createResp, "Did not receive createModelSnippet response");
        assertTrue(createResp.contains("INTERLIS 2.4"),
                "createModelSnippet response should contain 'INTERLIS 2.4' but was: " + createResp);
        assertTrue(createResp.contains("MODEL DemoModel (de)"),
                "createModelSnippet response should contain 'MODEL DemoModel (de)' but was: " + createResp);
    }

    // ---- helpers ----

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
}
