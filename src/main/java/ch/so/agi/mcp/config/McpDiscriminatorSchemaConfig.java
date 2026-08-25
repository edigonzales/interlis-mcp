package ch.so.agi.mcp.config;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Makes closed {@code kind}-discriminated MCP unions explicit JSON Schema {@code oneOf}s. */
@Configuration(proxyBeanMethods = false)
public class McpDiscriminatorSchemaConfig {

  @Bean
  static BeanPostProcessor discriminatedToolSchemaPostProcessor() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!"toolSpecs".equals(beanName) || !(bean instanceof List<?> raw)) return bean;
        List<Object> normalizedSpecifications = new ArrayList<>();
        for (Object item : raw) {
          if (!(item instanceof SyncToolSpecification specification)) {
            normalizedSpecifications.add(item);
            continue;
          }
          McpSchema.Tool tool = specification.tool();
          Map<String, Object> normalizedInput = normalize(tool.inputSchema());
          Map<String, Object> normalizedOutput = normalize(tool.outputSchema());
          McpSchema.Tool normalizedTool = new McpSchema.Tool(
              tool.name(), tool.title(), tool.description(), normalizedInput, normalizedOutput,
              tool.annotations(), tool.meta(), tool.icons());
          normalizedSpecifications.add(
              new SyncToolSpecification(normalizedTool, specification.callHandler()));
        }
        return List.copyOf(normalizedSpecifications);
      }
    };
  }

  private static Map<String, Object> normalize(Map<String, Object> schema) {
    if (schema == null || schema.isEmpty()) return schema;
    Map<String, Object> copy = copyMap(schema);
    @SuppressWarnings("unchecked")
    Map<String, Object> definitions = copy.get("$defs") instanceof Map<?, ?> defs
        ? (Map<String, Object>) defs : Map.of();
    normalizeNode(copy, definitions);
    return copy;
  }

  private static void normalizeNode(Object node, Map<String, Object> definitions) {
    if (node instanceof Map<?, ?> raw) {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) raw;
      Object alternatives = map.get("anyOf");
      if (alternatives instanceof List<?> list && isDiscriminatedUnion(list, definitions)) {
        map.remove("anyOf");
        map.put("oneOf", alternatives);
      }
      for (Object child : new ArrayList<>(map.values())) normalizeNode(child, definitions);
    } else if (node instanceof List<?> list) {
      list.forEach(child -> normalizeNode(child, definitions));
    }
  }

  private static boolean isDiscriminatedUnion(
      List<?> alternatives, Map<String, Object> definitions) {
    if (alternatives.size() < 2) return false;
    Set<String> discriminatorValues = new LinkedHashSet<>();
    for (Object alternative : alternatives) {
      String value = discriminatorValue(alternative, definitions);
      if (value == null || !discriminatorValues.add(value)) return false;
    }
    return true;
  }

  private static String discriminatorValue(
      Object alternative, Map<String, Object> definitions) {
    if (!(alternative instanceof Map<?, ?> raw)) return null;
    Object ref = raw.get("$ref");
    if (ref instanceof String reference && reference.startsWith("#/$defs/")) {
      return discriminatorValue(definitions.get(reference.substring("#/$defs/".length())), definitions);
    }
    Object properties = raw.get("properties");
    if (!(properties instanceof Map<?, ?> propertyMap)) return null;
    Object kind = propertyMap.get("kind");
    if (!(kind instanceof Map<?, ?> kindMap)) return null;
    Object constant = kindMap.get("const");
    return constant instanceof String text && !text.isBlank() ? text : null;
  }

  private static Map<String, Object> copyMap(Map<String, Object> source) {
    Map<String, Object> result = new LinkedHashMap<>();
    source.forEach((key, value) -> result.put(key, copy(value)));
    return result;
  }

  private static Object copy(Object value) {
    if (value instanceof Map<?, ?> raw) {
      Map<String, Object> result = new LinkedHashMap<>();
      raw.forEach((key, child) -> result.put(String.valueOf(key), copy(child)));
      return result;
    }
    if (value instanceof List<?> list) return new ArrayList<>(list.stream().map(
        McpDiscriminatorSchemaConfig::copy).toList());
    return value;
  }
}
