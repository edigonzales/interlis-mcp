package ch.so.agi.mcp.constraint;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.knowledge.KnowledgeRuleLoader;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.model.IliSpecRenderer;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.tools.AttributeTools;
import ch.so.agi.mcp.tools.ConstraintAuthoringTools;
import ch.so.agi.mcp.tools.ConstraintCaseGenerationTools;
import ch.so.agi.mcp.tools.ConstraintTestTools;
import ch.so.agi.mcp.tools.DomainTools;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

public final class ConstraintFixtures {
  private static final String ROOT = "/constraint/";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ConstraintFixtures() {}

  public static ConstraintAuthoringTools authoring(IliCompilerService compiler) {
    var analysis = new ModelAnalysisTools(compiler);
    var rules = new ModelingRuleTools(new KnowledgeRuleLoader(), analysis, compiler);
    var contexts = new ConstraintContextService(compiler);
    return new ConstraintAuthoringTools(new ConstraintAuthoringEngine(
        compiler, contexts, new ConstraintSourceEditService(),
        new IliSpecRenderer(new AttributeTools(), new DomainTools()),
        new ConstraintCaseGenerationTools(contexts, new ConstraintTestTools(compiler)),
        new ModelChangeReviewService(analysis, rules)));
  }

  public static String repositories() {
    // ili2c resolves imports from a filesystem directory. Gradle copies these test resources there.
    var resource = Objects.requireNonNull(ConstraintFixtures.class.getResource(ROOT + "imports"));
    return Path.of(URI.create(resource.toExternalForm())).toString();
  }

  public static <T> T arguments(String id, Class<T> type) throws IOException {
    try (var input = Objects.requireNonNull(ConstraintFixtures.class.getResourceAsStream(
        ROOT + "reconstruction/" + id + ".json"), id)) {
      return MAPPER.readValue(input, type);
    }
  }

  public static String original(String id) throws IOException {
    String model = switch (id) {
      case "P01", "P02", "P03", "P06" -> "soil";
      case "P04", "P05" -> "drainage";
      case "P07", "P08", "P10" -> "wastewater";
      case "P09" -> "shooting-noise";
      case "N11" -> "land-use";
      case "N12" -> "natural-hazards";
      default -> throw new IllegalArgumentException("Unknown reconstruction case: " + id);
    };
    try (var input = Objects.requireNonNull(ConstraintFixtures.class.getResourceAsStream(
        ROOT + "reconstruction/" + model + ".ili"), model)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }

  public static String withoutRule(String id) throws IOException {
    String source = original(id);
    String startMarker = "!! begin original rule " + id + "\n";
    String endMarker = "!! end original rule " + id + "\n";
    int start = source.indexOf(startMarker);
    int end = source.indexOf(endMarker);
    if (start < 0 || end <= start || source.lastIndexOf(startMarker) != start
        || source.lastIndexOf(endMarker) != end) {
      throw new IllegalStateException("Expected one marked original rule for " + id);
    }
    return source.substring(0, start) + source.substring(end + endMarker.length());
  }
}
