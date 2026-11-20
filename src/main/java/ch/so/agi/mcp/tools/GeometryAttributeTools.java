package ch.so.agi.mcp.tools;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class GeometryAttributeTools {

  @McpTool(
      name = "listGeometryTypes",
      description = "Listet Geometrietypen aus INTERLIS und CHBase für die gewählte Sprachversion.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false)
  )
  public Map<String, Object> listGeometryTypes(
      @McpToolParam(description = "INTERLIS Sprachversion (2.3 oder 2.4)", required = false) @Nullable String iliVersion
  ) {
    String ili = normalizeIliVersion(iliVersion);
    String modelName = "2.3".equals(ili) ? "GeometryCHLV95_V1" : "GeometryCHLV95_V2";
    List<Map<String, String>> entries = new ArrayList<>();
    interlisGeometryTypes(ili).forEach(type -> entries.add(Map.of(
        "name", type,
        "model", "INTERLIS"
    )));
    chBaseGeometryTypes(ili).forEach(type -> entries.add(Map.of(
        "name", modelName + "." + type,
        "model", "CHBase"
    )));
    return Map.of(
        "iliVersion", ili,
        "types", entries
    );
  }

  private String normalizeIliVersion(@Nullable String iliVersion) {
    String ili = (iliVersion == null || iliVersion.isBlank()) ? "2.4" : iliVersion.trim();
    if (!"2.3".equals(ili) && !"2.4".equals(ili)) {
      throw new IllegalArgumentException("iliVersion must be '2.3' oder '2.4'.");
    }
    return ili;
  }

  private List<String> interlisGeometryTypes(String ili) {
    if ("2.3".equals(ili)) {
      return List.of("POLYLINE", "SURFACE", "AREA");
    }
    if ("2.4".equals(ili)) {
      return List.of("POLYLINE", "SURFACE", "AREA", "MULTIPOLYLINE", "MULTISURFACE", "MULTIAREA");
    }
    throw new IllegalArgumentException("iliVersion must be '2.3' oder '2.4'.");
  }

  private Set<String> chBaseGeometryTypes(String ili) {
    return new LinkedHashSet<>(new GeometryTypeRenderer().supportedChBaseTypes(ili));
  }
}
