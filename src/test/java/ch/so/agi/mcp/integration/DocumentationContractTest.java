package ch.so.agi.mcp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
class DocumentationContractTest {

  private static final Pattern TOOL_HEADING = Pattern.compile("(?m)^## `([^`]+)`$");
  private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^]]+]\\(([^)]+)\\)");

  @Autowired
  @Qualifier("toolSpecs")
  List<SyncToolSpecification> toolSpecifications;

  @Test
  void toolReferenceHasExactlyOneHeadingForEveryRegisteredTool() throws IOException {
    String reference = Files.readString(Path.of("docs/TOOL_REFERENCE.md"));
    Set<String> documented = new LinkedHashSet<>();
    Matcher matcher = TOOL_HEADING.matcher(reference);
    while (matcher.find()) {
      assertThat(documented.add(matcher.group(1)))
          .as("duplicate tool heading %s", matcher.group(1))
          .isTrue();
    }

    Set<String> registered = toolSpecifications.stream()
        .map(specification -> specification.tool().name())
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    assertThat(documented).containsExactlyInAnyOrderElementsOf(registered);
  }

  @Test
  void localMarkdownLinksResolve() throws IOException {
    List<Path> markdownFiles;
    try (var docs = Files.walk(Path.of("docs"))) {
      markdownFiles = new java.util.ArrayList<>(docs.filter(path -> path.toString().endsWith(".md")).toList());
    }
    markdownFiles.add(Path.of("README.md"));

    for (Path source : markdownFiles) {
      Matcher matcher = MARKDOWN_LINK.matcher(Files.readString(source));
      while (matcher.find()) {
        String target = matcher.group(1);
        if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("#")) {
          continue;
        }
        String pathPart = target.split("#", 2)[0];
        if (pathPart.isBlank()) {
          continue;
        }
        assertThat(source.getParent() == null
                ? Path.of(pathPart).normalize()
                : source.getParent().resolve(pathPart).normalize())
            .as("local link %s in %s", target, source)
            .exists();
      }
    }
  }
}
