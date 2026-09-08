package ch.so.agi.mcp.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** Technical fixture QA through the registered handlers. This is NOT a native benchmark run. */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
class BenchmarkReferenceFixturesTest {
  static final Path SUITE = Path.of("evals/constraint-reconstruction/v2").toAbsolutePath();
  @DynamicPropertySource static void repositories(DynamicPropertyRegistry properties) {
    properties.add("interlis.mcp.model-repositories", () -> SUITE.resolve("dependencies").toString());
  }
  @Autowired @Qualifier("toolSpecs") List<SyncToolSpecification> tools;
  final ObjectMapper mapper = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(strings={"P01","P02","P03","P04","P05","P06","P07","P08","P09","P10","N11","N12"})
  @SuppressWarnings("unchecked")
  void frozenPayloadProducesIndependentlyInspectableResult(String cid) throws Exception {
    Map<String,Object> request = mapper.readValue(Files.readString(SUITE.resolve("reference/"+cid+"/request-template.json")), Map.class);
    Map<String,Object> payload = (Map<String,Object>) request.get("payload");
    payload.put("modelText", Files.readString(SUITE.resolve("public/"+cid+"/model.ili")));
    String name = request.get("tool").toString().replace("mcp__interlis_mcp_eval__", "");
    var handler = tools.stream().filter(s -> name.equals(s.tool().name())).findFirst().orElseThrow();
    Path dir = Path.of("build/benchmark/reference-diagnostic",cid); Files.createDirectories(dir);
    Files.writeString(dir.resolve("request.json"), mapper.writeValueAsString(request));
    var response = handler.callHandler().apply(null, new McpSchema.CallToolRequest(name, payload));
    Files.writeString(dir.resolve("raw-result.json"), mapper.writeValueAsString(response));
    Map<String,Object> result = response.structuredContent() instanceof Map<?,?> content ? (Map<String,Object>)content
        : mapper.readValue(((McpSchema.TextContent)response.content().getFirst()).text(), Map.class);
    assertThat(response.isError()).as(mapper.writeValueAsString(response)).isNotEqualTo(true);
    Object candidate = result.get("updatedModelText"); if (candidate == null) candidate = result.get("candidateModelText");
    assertThat(candidate).as(mapper.writeValueAsString(result)).isNotNull();
    Path file=dir.resolve("candidate.ili"); Files.writeString(file,candidate.toString());
    var evidence=CompilerEvidence.compare(SUITE.resolve("public/"+cid+"/model.ili"),file,SUITE.resolve("dependencies").toString());
    Files.writeString(dir.resolve("compiler-evidence.json"),mapper.writeValueAsString(evidence));
    assertThat(evidence).containsEntry("candidateCompiles",true).containsEntry("astComplete",true).containsEntry("noCollateralChanges",true);
    if(cid.startsWith("P")) {
      assertThat(result).as(mapper.writeValueAsString(result)).containsEntry("status","GENERATED").containsEntry("proofVerified",true);
    } else {
      assertThat(result).containsEntry("status","EXTERNAL_FUNCTION_SEMANTICS_REQUIRED").containsEntry("proofVerified",false).containsEntry("generated",false);
      assertThat(result.get("updatedModelText")).isNull();
    }
  }
}
