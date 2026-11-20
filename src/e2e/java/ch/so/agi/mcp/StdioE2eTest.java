package ch.so.agi.mcp;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("e2e")
public class StdioE2eTest {
    private static final String EXPECTED_PROJECT_VERSION = System.getProperty("expectedProjectVersion", "0.0.LOCALBUILD");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private Process proc;
    private BufferedWriter toServer;
    private Thread stdoutPump;
    private Thread stderrPump;
    private final LinkedBlockingQueue<String> stdoutLines = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<String> stderrLines = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<Integer, String> pendingResponses = new ConcurrentHashMap<>();

    @TempDir
    Path corpusDirectory;

    @BeforeEach
    void startServer() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(currentJavaBinary(), "-jar", "build/libs/interlis-mcp.jar",
                "--interlis.knowledge.model-paths=" + corpusDirectory);
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
            if (proc.isAlive()) {
                proc.destroy();
                if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                    proc.waitFor(2, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void stdinEofShutsDownServerProcess() throws Exception {
        assertCleanEof();
    }

    @Test
    void initialize_toolsList_fullDrain_thenEofShutsDownWithExitCodeZero() throws Exception {
        initializeSession();

        String toolsResponse = listTools(2);
        assertTrue(JSON.readTree(toolsResponse).path("result").path("tools").isArray(), toolsResponse);
        assertContainsAll(toolsResponse,
                "\"tools\"",
                "validateIliModel",
                "reviewIliConstraint",
                "authorIliConstraint",
                "verifyIliModel");
        assertEquals(21, JSON.readTree(toolsResponse).path("result").path("tools").size());

        assertCleanEof();
    }

    @Test
    void largeModelExampleReturnsCompleteResponseAndShutsDownAfterEof() throws Exception {
        String commentLine = "!! " + "0123456789".repeat(13) + "\n";
        String model = minimalRenameModel() + commentLine.repeat(4096) + "!! End of synthetic fixture\n";
        Path example = Files.writeString(corpusDirectory.resolve("large.ili"), model);
        initializeSession();

        String arguments = JSON.writeValueAsString(Map.of("path", example.toString()));
        String response = callTool(2, "readModelExample", arguments);
        assertTrue(response.getBytes(StandardCharsets.UTF_8).length > 512 * 1024,
                "Synthetic response must exceed the STDOUT pipe buffer");
        var result = JSON.readTree(response).path("result");
        assertFalse(result.path("isError").asBoolean(), "readModelExample reported an error");
        var payload = JSON.readTree(result.path("content").path(0).path("text").asString());
        assertTrue(model.equals(payload.path("modelText").asString()),
                "readModelExample must return the entire model unchanged");
        assertCleanEof();
    }

    private void assertCleanEof() throws Exception {
        toServer.close();
        toServer = null;

        assertTrue(proc.waitFor(10, TimeUnit.SECONDS),
                "MCP server should terminate after the STDIO input reaches EOF");
        assertTrue(proc.exitValue() == 0,
                "MCP server should terminate cleanly after EOF; exit code was " + proc.exitValue());
        stdoutPump.join(1000);
        assertFalse(stdoutPump.isAlive(), "STDOUT should close when the process exits");
        stdoutLines.forEach(this::rpcResponseId);
    }

    @Test
    void initialize_listTools_and_authorAnnotatedModel() throws Exception {
        initializeSession();

        String toolsResp = listTools(2);
        assertContainsAll(toolsResp,
                "\"tools\"",
                "authorIliModel",
                "applyIliModelChanges",
                "authorIliConstraint",
                "renameModelElement",
                "analyzeIliModel",
                "reviewIliModel",
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
                createResp, "authorIliModel", "GENERATED", "INTERLIS 2.4", "MODEL DemoModel (de)", "!!@ title=", "Demo", "afterCompilation", "constraintProofs", "constraintInteractions");
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
                "Demo.Topic.Target", "beforeCompilation", "afterCompilation", "constraintProofs", "constraintInteractions");
    }

    @Test
    void reviewIliChangeReportsAddedAttributeOverStdio() throws Exception {
        initializeSession();

        String before = minimalRenameModel();
        String after = before.replace("END Target;", "label : TEXT*20; END Target;");
        String arguments = JSON.writeValueAsString(Map.of("beforeModelText", before, "afterModelText", after));
        String response = callTool(2, "reviewIliChange", arguments);
        var result = JSON.readTree(response).path("result");
        assertFalse(result.path("isError").asBoolean(), response);
        var change = JSON.readTree(result.path("content").path(0).path("text").asString());
        assertTrue(change.path("comparable").asBoolean(), response);
        assertEquals("Demo.Topic.Target.label", change.path("added").path(0).path("scopedName").asString(), response);
    }

    @Test
    void comparesAssociationSemanticsAndAppliesAnOptionalAttributeOverStdio() throws Exception {
        initializeSession();

        String before = """
                INTERLIS 2.4;
                MODEL Demo (de) AT "https://example.org/demo" VERSION "2026-08-24" =
                  IMPORTS INTERLIS;
                  !! IMPORTS FakeExternal;
                  TOPIC Topic =
                    CLASS First =
                    END First;
                    CLASS Second =
                    END Second;
                    ASSOCIATION Link (OID) =
                      OID AS INTERLIS.UUIDOID;
                      first -- {1} First;
                      second -- {0..*} Second;
                    END Link;
                  END Topic;
                END Demo.
                """;
        var analysis = toolPayload(2, "analyzeIliModel", Map.of("modelText", before));
        assertTrue(analysis.path("valid").asBoolean(), analysis.toString());
        assertEquals(JSON.readTree("[{\"model\":\"INTERLIS\",\"qualified\":true}]"), analysis.path("imports"));
        assertTrue(analysis.path("associations").path(0).path("identifiable").asBoolean());
        assertEquals("INTERLIS.UUIDOID", analysis.path("associations").path(0).path("oid").asString());

        String moved = before.replace("    ASSOCIATION", "\n    !! Link moved\n    ASSOCIATION")
                .replace("FakeExternal", "OtherFake");
        var unchanged = toolPayload(3, "reviewIliChange", Map.of("beforeModelText", before, "afterModelText", moved));
        assertTrue(unchanged.path("comparable").asBoolean(), unchanged.toString());
        assertFalse(unchanged.path("hasChanges").asBoolean(), unchanged.toString());
        assertEquals("NONE", unchanged.path("impact").asString());

        var oidChanged = toolPayload(4, "reviewIliChange", Map.of(
                "beforeModelText", before, "afterModelText", moved.replace("UUIDOID", "STANDARDOID")));
        assertTrue(oidChanged.path("hasChanges").asBoolean(), oidChanged.toString());
        assertEquals("POTENTIALLY_BREAKING", oidChanged.path("impact").asString());
        assertEquals(1, oidChanged.path("changed").size());
        assertEquals("Demo.Topic.Link", oidChanged.path("changed").path(0).path("scopedName").asString());
        assertEquals("[\"oid\"]", oidChanged.path("changed").path(0).path("changedFields").toString());

        var request = JSON.readTree("""
                {"changes":[{"operation":"ADD_ATTRIBUTE","addAttribute":{
                  "containerFqn":"Demo.Topic.First",
                  "attribute":{"name":"label","typeSpec":{"baseType":{"kind":"TEXT","length":20}}}
                }}]}
                """);
        var applied = toolPayload(5, "applyIliModelChanges", Map.of("modelText", before, "request", request));
        assertEquals("APPLIED", applied.path("status").asString(), applied.toString());
        var diff = applied.path("semanticDiff");
        assertEquals(1, diff.path("added").size());
        assertEquals("Demo.Topic.First.label", diff.path("added").path(0).path("scopedName").asString());
        assertTrue(diff.path("changed").isEmpty(), diff.toString());
    }

    private tools.jackson.databind.JsonNode toolPayload(int id, String tool, Map<String, ?> arguments) throws Exception {
        String response = callTool(id, tool, JSON.writeValueAsString(arguments));
        var result = JSON.readTree(response).path("result");
        assertFalse(result.path("isError").asBoolean(), response);
        return JSON.readTree(result.path("content").path(0).path("text").asString());
    }

    @Test
    void readModelExampleRejectsPathOutsideConfiguredCorpusOverStdio(@TempDir Path directory) throws Exception {
        initializeSession();

        Path example = Files.writeString(directory.resolve("outside.ili"), minimalRenameModel());
        String arguments = JSON.writeValueAsString(Map.of("path", example.toString()));
        String response = callTool(2, "readModelExample", arguments);
        var result = JSON.readTree(response).path("result");
        assertTrue(result.path("isError").asBoolean(), response);
        assertTrue(result.path("content").path(0).path("text").asString().contains("outside the configured corpus"), response);
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

        assertContainsAll(generateResponse, "validation", "VALID", "MODEL_RULES_FOR_TRANSFERRED_DATA");

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
        assertContainsAll(resources, "interlis://knowledge/handbook-rules",
                "interlis://knowledge/agent-workflow", "interlis://knowledge/so-data-rules",
                "interlis://knowledge/model-corpus-index");
        assertEquals(4, new tools.jackson.databind.ObjectMapper().readTree(resources)
                .path("result").path("resources").size());
        assertContainsAll(readResource(4, "interlis://knowledge/handbook-rules"), "MDE-010", "MDE-020");
        assertContainsAll(readResource(11, "interlis://knowledge/agent-workflow"),
                "afterCompilation", "constraintProofs", "APPLIED", "afterReview.validForAutomatedRules",
                "modelHashes.before", "coverageComplete", "includeSuccessfulTestXtf");
        assertContainsAll(readResource(16, "interlis://knowledge/so-data-rules"), "DATA-001");
        assertContainsAll(readResource(17, "interlis://knowledge/model-corpus-index"), "Configured INTERLIS Model Corpus");

        assertContainsAll(listPrompts(5), "interlis-modeling-agent", "review-interlis-model",
                "extend-interlis-model", "author-interlis-constraint");
        String reviewPrompt = getPrompt(6, "review-interlis-model", "{\"modelPurpose\":\"PUBLICATION\"}");
        assertContainsAll(reviewPrompt, "reviewIliModel", "PUBLICATION", "manualChecks");
        assertContainsAll(getPrompt(10, "interlis-modeling-agent", "{}"),
                "authorIliModel", "authorIliConstraint", "reviewIliModel", "testIliConstraint");
        assertContainsAll(getPrompt(12, "extend-interlis-model", "{}"),
                "applyIliModelChanges", "afterCompilation.valid", "semanticDiff", "afterReview",
                "openQuestions", "allowPotentiallyBreaking");
        assertContainsAll(getPrompt(14, "author-interlis-constraint", "{\"constraintKind\":\"SET\"}"),
                "SET", "authorIliConstraint", "PASSED", "testIliConstraint", "candidateModelText");

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

        String argsJson = "{\"modelText\":" + jsonString(modelText) + ","
                + "\"modelPurpose\":\"PUBLICATION\",\"ruleProfile\":\"SO-GIS\"}";
        String review = callTool(8, "reviewIliModel", argsJson);
        assertSuccessfulToolResponse(review, "reviewIliModel",
                "SO-GIS", "validForAutomatedRules", "manualChecks", "MDE-060");
        String invalidProfile = callTool(9, "reviewIliModel", argsJson.replace("SO-GIS", "SO"));
        assertTrue(invalidProfile.contains("\"isError\":true"), invalidProfile);
    }

    @Test
    void findsAndResolvesConstraintPathsOverStdio() throws Exception {
        initializeSession();
        var mapper = new tools.jackson.databind.ObjectMapper();
        String model = """
                INTERLIS 2.4;
                MODEL Discovery (en) AT "https://example.org" VERSION "1" =
                  TOPIC T =
                    CLASS Root = END Root;
                    CLASS Child = amount : MANDATORY 0..100; END Child;
                    ASSOCIATION Link = parents -- {0..*} Root; children -- {0..3} Child; END Link;
                  END T;
                END Discovery.
                """;
        String args = mapper.writeValueAsString(java.util.Map.of("modelText", model,
                "context", "Discovery.T.Root", "targetFqn", "Discovery.T.Child.amount"));
        String response = callTool(3, "findConstraintPaths", args);
        assertSuccessfulToolResponse(response, "findConstraintPaths", "children->amount", "COLLECTION_SUM", "NOT_RUN", "modelHashes");
        var envelope = mapper.readTree(response).get("result");
        var result = envelope.has("structuredContent") ? envelope.get("structuredContent")
                : mapper.readTree(envelope.get("content").get(0).get("text").asString());
        org.junit.jupiter.api.Assertions.assertEquals("AVAILABLE", result.get("status").asString());
        org.junit.jupiter.api.Assertions.assertEquals(1, result.get("paths").size());
        assertTrue(result.get("paths").get(0).get("collection").asBoolean());
        assertTrue(result.get("paths").get(0).get("steps").get(0).get("optional").asBoolean());

        String resolved = callTool(4, "resolveConstraintPath", mapper.writeValueAsString(java.util.Map.of(
                "modelText", model, "context", "Discovery.T.Root", "path", "children->amount")));
        assertSuccessfulToolResponse(resolved, "resolveConstraintPath", "COLLECTION_SUM", "optional");
        var resolvedEnvelope = mapper.readTree(resolved).get("result");
        var resolvedResult = resolvedEnvelope.has("structuredContent") ? resolvedEnvelope.get("structuredContent")
                : mapper.readTree(resolvedEnvelope.get("content").get(0).get("text").asString());
        org.junit.jupiter.api.Assertions.assertEquals(result.get("paths").get(0), resolvedResult);
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
        while (System.currentTimeMillis() < deadline) {
            long remaining = Math.max(1, deadline - System.currentTimeMillis());
            String line = stdoutLines.poll(remaining, TimeUnit.MILLISECONDS);
            if (line == null) continue; // timeout slice; loop again
            Integer responseId = rpcResponseId(line);
            if (responseId != null) {
                if (responseId == id) return line;
                pendingResponses.put(responseId, line);
            }
            // Otherwise keep waiting; other messages (e.g., pings/notifications) may arrive.
        }
        return null;
    }

    private Integer rpcResponseId(String line) {
        var message = JSON.readTree(line);
        assertTrue("2.0".equals(message.path("jsonrpc").asString()), () -> "Unexpected STDOUT: " + line);
        if (message.has("method")) return null;
        var id = message.path("id");
        assertTrue(id.isInt() && (message.has("result") ^ message.has("error")),
                () -> "Invalid JSON-RPC response: " + line);
        return id.asInt();
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
