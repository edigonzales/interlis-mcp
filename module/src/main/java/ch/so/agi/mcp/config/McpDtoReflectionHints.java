package ch.so.agi.mcp.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registers the DTO types of the MCP tool contract for reflection.
 *
 * <p>The MCP SDK converts tool arguments and tool results through Jackson, which
 * invokes record and bean accessors reflectively. A GraalVM native image keeps
 * only members that are reachable from a hint, so the payload types are listed in
 * {@code META-INF/native-image/ch.so.agi/interlis-mcp/reachability-metadata.json}.
 * The list is collected with the GraalVM tracing agent while
 * {@code tools/test-mcp-stdio.py} drives every MCP tool.</p>
 *
 * <p>The type list is kept as data rather than as a hand written Java list because
 * tool results nest deeply and are composed at runtime. Registering every member
 * of every listed type keeps accessors working regardless of which record
 * component Jackson touches.</p>
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(McpDtoReflectionHints.McpDtoTypes.class)
public class McpDtoReflectionHints {

  static final String METADATA_RESOURCE =
      "META-INF/native-image/ch.so.agi/interlis-mcp/reachability-metadata.json";

  static final class McpDtoTypes implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
      for (String typeName : readTypeNames(classLoader)) {
        Class<?> type = ClassUtils.isPresent(typeName, classLoader)
            ? ClassUtils.resolveClassName(typeName, classLoader)
            : null;
        if (type == null || type.isPrimitive() || type.isArray() || type.isInterface()) continue;
        hints.reflection().registerType(type,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
            MemberCategory.ACCESS_PUBLIC_FIELDS);
      }
    }

    private static List<String> readTypeNames(ClassLoader classLoader) {
      try (InputStream input = classLoader.getResourceAsStream(METADATA_RESOURCE)) {
        if (input == null) {
          // Failing here is deliberate: without these hints the native image
          // starts but individual tools fail with
          // MissingReflectionRegistrationError, which is far harder to diagnose.
          throw new IllegalStateException("Missing classpath resource " + METADATA_RESOURCE);
        }
        ObjectMapper mapper = JsonMapper.builder().build();
        JsonNode root = mapper.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        List<String> names = new ArrayList<>();
        for (JsonNode entry : root.path("reflection")) {
          JsonNode type = entry.path("type");
          if (type.isString() && !type.asString().isBlank()) names.add(type.asString());
        }
        if (names.isEmpty()) {
          throw new IllegalStateException("No reflection entries in " + METADATA_RESOURCE);
        }
        return names;
      } catch (IOException error) {
        throw new UncheckedIOException("Unable to read " + METADATA_RESOURCE, error);
      }
    }
  }
}
