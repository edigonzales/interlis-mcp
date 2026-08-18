package ch.so.agi.mcp.tools;

import static ch.so.agi.mcp.constraint.StandardFunctionRegistry.Family.TEXT;

import ch.so.agi.mcp.constraint.ConstraintExpression.IliVersion;
import ch.so.agi.mcp.constraint.StandardFunctionRegistry;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class TextTools {

  @McpTool(
      name = "listTextFunctions",
      description = "Listet alle INTERLIS-Text-Funktionen für die gewünschte Sprachversion mit Signatur und Rückgabetyp auf."
  )
  public Map<String, Object> listTextFunctions(
      @McpToolParam(description = "INTERLIS Sprachversion (2.3 oder 2.4)", required = false) @Nullable String iliVersion
  ) {
    IliVersion version = normalizeIliVersion(iliVersion);
    return Map.of(
        "iliVersion", version.text(),
        "functions", StandardFunctionRegistry.functions(TEXT).stream()
            .map(function -> Map.of(
                "function", function.functionSignature(version),
                "returns", function.declaredReturnType()))
            .toList()
    );
  }

  private static IliVersion normalizeIliVersion(@Nullable String iliVersion) {
    String ili = (iliVersion == null || iliVersion.isBlank()) ? "2.4" : iliVersion.trim();
    return switch (ili) {
      case "2.3" -> IliVersion.ILI_23;
      case "2.4" -> IliVersion.ILI_24;
      default -> throw new IllegalArgumentException("iliVersion must be '2.3' oder '2.4'.");
    };
  }
}
