package ch.so.agi.mcp.knowledge;

import ch.so.agi.mcp.analysis.ModelPurpose;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ModelCorpusService {

  private static final Pattern MODEL_PATTERN = Pattern.compile("\\bMODEL\\s+([A-Za-z][A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);
  private static final Pattern TOPIC_PATTERN = Pattern.compile("\\bTOPIC\\s+([A-Za-z][A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);
  private static final Pattern CLASS_PATTERN = Pattern.compile("\\bCLASS\\s+([A-Za-z][A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);
  private static final Pattern DOMAIN_PATTERN = Pattern.compile("\\bDOMAIN\\s+([A-Za-z][A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);
  private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*:", Pattern.MULTILINE);

  private final String configuredPaths;
  private final long maxModelBytes;
  private final int maxSearchResults;

  public ModelCorpusService(
      @Value("${interlis.knowledge.model-paths:}") String configuredPaths,
      @Value("${interlis.knowledge.max-model-bytes:1048576}") long maxModelBytes,
      @Value("${interlis.knowledge.max-search-results:10}") int maxSearchResults) {
    this.configuredPaths = configuredPaths;
    this.maxModelBytes = maxModelBytes;
    this.maxSearchResults = maxSearchResults;
  }

  public Map<String, Object> indexConfiguredModels() {
    ScanResult scan = scan();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("configuredPaths", configuredPathStrings());
    response.put("maxModelBytes", maxModelBytes);
    response.put("indexedCount", scan.documents().size());
    response.put("ignored", scan.ignored());
    response.put("errors", scan.errors());
    response.put("models", scan.documents().stream().map(ModelDocument::summaryMap).toList());
    return response;
  }

  public Map<String, Object> findSimilarModels(
      @Nullable String query,
      @Nullable String modelText,
      @Nullable ModelPurpose modelPurpose,
      @Nullable Integer limit) {
    Set<String> terms = queryTerms(query, modelText, modelPurpose);
    if (terms.isEmpty()) {
      throw new IllegalArgumentException("At least one of query or modelText must contain searchable terms.");
    }

    ScanResult scan = scan();
    int effectiveLimit = limit != null && limit > 0 ? Math.min(limit, maxSearchResults) : maxSearchResults;
    List<Map<String, Object>> results = scan.documents().stream()
        .map(document -> document.scoredAgainst(terms))
        .filter(result -> ((Number) result.get("score")).doubleValue() > 0.0)
        .sorted(Comparator.comparingDouble(result -> -((Number) result.get("score")).doubleValue()))
        .limit(effectiveLimit)
        .toList();

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("queryTerms", terms);
    response.put("results", results);
    response.put("ignored", scan.ignored());
    response.put("errors", scan.errors());
    return response;
  }

  public Map<String, Object> readModelExample(String requestedPath) {
    if (requestedPath == null || requestedPath.isBlank()) {
      throw new IllegalArgumentException("Model example path is required.");
    }

    Path path = Path.of(requestedPath.trim());
    try {
      Path realPath = path.toRealPath();
      if (!isConfiguredModelPath(realPath)) {
        throw new IllegalArgumentException("Model example path is outside the configured corpus: " + requestedPath);
      }
      if (!Files.isRegularFile(realPath)) {
        throw new IllegalArgumentException("Model example path is not a regular file: " + requestedPath);
      }
      if (!realPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ili")) {
        throw new IllegalArgumentException("Model example must be an .ili file: " + requestedPath);
      }

      long size = Files.size(realPath);
      if (size > maxModelBytes) {
        throw new IllegalArgumentException(
            "Model example exceeds max-model-bytes (" + size + " > " + maxModelBytes + ").");
      }

      String text = Files.readString(realPath, StandardCharsets.UTF_8);
      ModelDocument document = ModelDocument.from(realPath, text);
      Map<String, Object> response = new LinkedHashMap<>(document.summaryMap());
      response.put("sizeBytes", size);
      response.put("modelText", text);
      return response;
    } catch (IOException e) {
      throw new IllegalArgumentException("Unable to read model example: " + requestedPath, e);
    }
  }

  public String indexMarkdown() {
    ScanResult scan = scan();
    StringBuilder markdown = new StringBuilder("# Configured INTERLIS Model Corpus\n\n");
    markdown.append("- configuredPaths: ").append(configuredPathStrings()).append('\n');
    markdown.append("- indexedCount: ").append(scan.documents().size()).append('\n');
    markdown.append("- ignoredCount: ").append(scan.ignored().size()).append('\n');
    markdown.append("- errorCount: ").append(scan.errors().size()).append("\n\n");

    for (ModelDocument document : scan.documents()) {
      markdown.append("- `").append(document.path()).append("`");
      if (!document.modelName().isBlank()) {
        markdown.append(" - ").append(document.modelName());
      }
      markdown.append('\n');
    }
    return markdown.toString();
  }

  private ScanResult scan() {
    List<ModelDocument> documents = new ArrayList<>();
    List<Map<String, Object>> ignored = new ArrayList<>();
    List<Map<String, Object>> errors = new ArrayList<>();

    for (Path root : configuredPaths()) {
      if (!Files.exists(root)) {
        errors.add(message(root, "Path does not exist."));
        continue;
      }
      try {
        if (Files.isRegularFile(root)) {
          readCandidate(root, documents, ignored, errors);
        } else if (Files.isDirectory(root)) {
          try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ili"))
                .sorted()
                .toList()) {
              readCandidate(path, documents, ignored, errors);
            }
          }
        } else {
          ignored.add(message(root, "Path is neither file nor directory."));
        }
      } catch (IOException e) {
        errors.add(message(root, e.getMessage()));
      }
    }

    return new ScanResult(documents, ignored, errors);
  }

  private void readCandidate(
      Path path,
      List<ModelDocument> documents,
      List<Map<String, Object>> ignored,
      List<Map<String, Object>> errors) {
    if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ili")) {
      ignored.add(message(path, "Not an .ili file."));
      return;
    }
    try {
      Path realPath = path.toRealPath();
      if (!isConfiguredModelPath(realPath)) {
        ignored.add(message(path, "File resolves outside the configured model corpus."));
        return;
      }
      long size = Files.size(realPath);
      if (size > maxModelBytes) {
        ignored.add(message(path, "File exceeds max-model-bytes (" + size + " > " + maxModelBytes + ")."));
        return;
      }
      String text = Files.readString(realPath, StandardCharsets.UTF_8);
      documents.add(ModelDocument.from(realPath, text));
    } catch (IOException e) {
      errors.add(message(path, e.getMessage()));
    }
  }

  private boolean isConfiguredModelPath(Path candidateRealPath) {
    for (Path configuredPath : configuredPaths()) {
      try {
        Path configuredRealPath = configuredPath.toRealPath();
        if (Files.isRegularFile(configuredRealPath) && candidateRealPath.equals(configuredRealPath)) {
          return true;
        }
        if (Files.isDirectory(configuredRealPath) && candidateRealPath.startsWith(configuredRealPath)) {
          return true;
        }
      } catch (IOException ignore) {
      }
    }
    return false;
  }

  private List<Path> configuredPaths() {
    return configuredPathStrings().stream().map(Path::of).toList();
  }

  private List<String> configuredPathStrings() {
    if (configuredPaths == null || configuredPaths.isBlank()) {
      return List.of();
    }
    List<String> paths = new ArrayList<>();
    for (String rawPath : configuredPaths.split(",")) {
      String path = rawPath.trim();
      if (!path.isBlank()) {
        paths.add(path);
      }
    }
    return paths;
  }

  private Map<String, Object> message(Path path, String message) {
    return Map.of("path", path.toString(), "message", message);
  }

  private Set<String> queryTerms(@Nullable String query, @Nullable String modelText, @Nullable ModelPurpose modelPurpose) {
    Set<String> terms = new LinkedHashSet<>();
    tokenizeInto(terms, query);
    tokenizeInto(terms, modelText);
    ModelPurpose purpose = ModelPurpose.normalize(modelPurpose);
    if (purpose != ModelPurpose.UNKNOWN && purpose != ModelPurpose.ANY) {
      terms.add(purpose.name().toLowerCase(Locale.ROOT));
    }
    return terms;
  }

  private static void tokenizeInto(Set<String> terms, @Nullable String text) {
    if (text == null) {
      return;
    }
    for (String token : text.split("[^A-Za-z0-9_]+")) {
      String normalized = token.toLowerCase(Locale.ROOT);
      if (normalized.length() >= 3) {
        terms.add(normalized);
      }
    }
  }

  private record ScanResult(
      List<ModelDocument> documents,
      List<Map<String, Object>> ignored,
      List<Map<String, Object>> errors) {
  }

  private record ModelDocument(
      Path path,
      String modelName,
      Set<String> topics,
      Set<String> classes,
      Set<String> domains,
      Set<String> attributes,
      Set<String> allTokens,
      String snippet) {

    static ModelDocument from(Path path, String text) {
      String modelName = firstMatch(MODEL_PATTERN, text);
      Set<String> topics = matches(TOPIC_PATTERN, text);
      Set<String> classes = matches(CLASS_PATTERN, text);
      Set<String> domains = matches(DOMAIN_PATTERN, text);
      Set<String> attributes = matches(ATTRIBUTE_PATTERN, text);
      Set<String> allTokens = new LinkedHashSet<>();
      tokenizeInto(allTokens, text);
      return new ModelDocument(path, modelName, topics, classes, domains, attributes, allTokens, snippet(text));
    }

    Map<String, Object> summaryMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("path", path.toString());
      map.put("modelName", modelName);
      map.put("topics", topics);
      map.put("classes", classes);
      map.put("domains", domains);
      return map;
    }

    Map<String, Object> scoredAgainst(Set<String> queryTerms) {
      Set<String> matchedTerms = new LinkedHashSet<>();
      double score = 0.0;
      for (String term : queryTerms) {
        if (containsToken(modelName, term)) {
          score += 8.0;
          matchedTerms.add(term);
        }
        if (containsAny(topics, term)) {
          score += 5.0;
          matchedTerms.add(term);
        }
        if (containsAny(classes, term)) {
          score += 5.0;
          matchedTerms.add(term);
        }
        if (containsAny(domains, term)) {
          score += 4.0;
          matchedTerms.add(term);
        }
        if (containsAny(attributes, term)) {
          score += 3.0;
          matchedTerms.add(term);
        }
        if (allTokens.contains(term)) {
          score += 1.0;
          matchedTerms.add(term);
        }
      }

      Map<String, Object> map = new LinkedHashMap<>();
      map.put("path", path.toString());
      map.put("modelName", modelName);
      map.put("score", score);
      map.put("matchedTerms", matchedTerms);
      map.put("summary", summary());
      map.put("snippet", snippet);
      return map;
    }

    private String summary() {
      return "model=" + blankFallback(modelName, "(unknown)")
          + ", topics=" + topics.size()
          + ", classes=" + classes.size()
          + ", domains=" + domains.size()
          + ", attributes=" + attributes.size();
    }

    private static boolean containsAny(Set<String> values, String term) {
      return values.stream().anyMatch(value -> containsToken(value, term));
    }

    private static boolean containsToken(String value, String term) {
      return value != null && value.toLowerCase(Locale.ROOT).contains(term);
    }

    private static String firstMatch(Pattern pattern, String text) {
      Matcher matcher = pattern.matcher(text);
      return matcher.find() ? matcher.group(1) : "";
    }

    private static Set<String> matches(Pattern pattern, String text) {
      Set<String> values = new LinkedHashSet<>();
      Matcher matcher = pattern.matcher(text);
      while (matcher.find()) {
        values.add(matcher.group(1));
      }
      return values;
    }

    private static String snippet(String text) {
      String normalized = text.replaceAll("\\s+", " ").trim();
      return normalized.length() <= 800 ? normalized : normalized.substring(0, 800);
    }

    private static String blankFallback(String value, String fallback) {
      return value == null || value.isBlank() ? fallback : value;
    }
  }
}
