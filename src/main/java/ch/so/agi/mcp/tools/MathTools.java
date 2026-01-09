package ch.so.agi.mcp.tools;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MathTools {

  private static final List<String> MATH_FUNCTIONS_23 = List.of(
      "Math.add(a: NUMERIC; b: NUMERIC): NUMERIC",
      "Math.sub(a: NUMERIC; b: NUMERIC): NUMERIC",
      "Math.mul(a: NUMERIC; b: NUMERIC): NUMERIC",
      "Math.div(a: NUMERIC; b: NUMERIC): NUMERIC",
      "Math.abs(a: NUMERIC): NUMERIC",
      "Math.acos(a: NUMERIC): NUMERIC",
      "Math.asin(a: NUMERIC): NUMERIC",
      "Math.atan(a: NUMERIC): NUMERIC",
      "Math.atan2(ordinate: NUMERIC; abscissa: NUMERIC): NUMERIC",
      "Math.cbrt(a: NUMERIC): NUMERIC",
      "Math.cos(a: NUMERIC): NUMERIC",
      "Math.cosh(a: NUMERIC): NUMERIC",
      "Math.exp(a: NUMERIC): NUMERIC",
      "Math.hypot(a: NUMERIC; b: NUMERIC): NUMERIC",
      "Math.log(a: NUMERIC): NUMERIC",
      "Math.log10(a: NUMERIC): NUMERIC",
      "Math.pow(a: NUMERIC; b: NUMERIC): NUMERIC",
      "Math.round(a: NUMERIC): NUMERIC",
      "Math.signum(a: NUMERIC): NUMERIC",
      "Math.sin(a: NUMERIC): NUMERIC",
      "Math.sinh(a: NUMERIC): NUMERIC",
      "Math.sqrt(a: NUMERIC): NUMERIC",
      "Math.tan(a: NUMERIC): NUMERIC",
      "Math.tanh(a: NUMERIC): NUMERIC",
      "Math.max(a: NUMERIC; b: NUMERIC): NUMERIC",
      "Math.min(a: NUMERIC; b: NUMERIC): NUMERIC",
      "Math.avg(attributePath: TEXT): NUMERIC",
      "Math.max2(attributePath: TEXT): NUMERIC",
      "Math.min2(attributePath: TEXT): NUMERIC",
      "Math.sum(attributePath: TEXT): NUMERIC"
  );

  private static final List<String> MATH_FUNCTIONS_24 = List.of(
      "Math_V2.add(a: NUMERIC; b: NUMERIC): NUMERIC",
      "Math_V2.sub(a: NUMERIC; b: NUMERIC): NUMERIC",
      "Math_V2.mul(a: NUMERIC; b: NUMERIC): NUMERIC",
      "Math_V2.div(a: NUMERIC; b: NUMERIC): NUMERIC",
      "Math_V2.abs(a: NUMERIC): NUMERIC",
      "Math_V2.acos(a: NUMERIC): NUMERIC",
      "Math_V2.asin(a: NUMERIC): NUMERIC",
      "Math_V2.atan(a: NUMERIC): NUMERIC",
      "Math_V2.atan2(ordinate: NUMERIC; abscissa: NUMERIC): NUMERIC",
      "Math_V2.cbrt(a: NUMERIC): NUMERIC",
      "Math_V2.cos(a: NUMERIC): NUMERIC",
      "Math_V2.cosh(a: NUMERIC): NUMERIC",
      "Math_V2.exp(a: NUMERIC): NUMERIC",
      "Math_V2.hypot(a: NUMERIC; b: NUMERIC): NUMERIC",
      "Math_V2.log(a: NUMERIC): NUMERIC",
      "Math_V2.log10(a: NUMERIC): NUMERIC",
      "Math_V2.pow(a: NUMERIC; b: NUMERIC): NUMERIC",
      "Math_V2.round(a: NUMERIC): NUMERIC",
      "Math_V2.signum(a: NUMERIC): NUMERIC",
      "Math_V2.sin(a: NUMERIC): NUMERIC",
      "Math_V2.sinh(a: NUMERIC): NUMERIC",
      "Math_V2.sqrt(a: NUMERIC): NUMERIC",
      "Math_V2.tan(a: NUMERIC): NUMERIC",
      "Math_V2.tanh(a: NUMERIC): NUMERIC",
      "Math_V2.max(a: NUMERIC; b: NUMERIC): NUMERIC",
      "Math_V2.min(a: NUMERIC; b: NUMERIC): NUMERIC",
      "Math_V2.avg(attributePath: TEXT): NUMERIC",
      "Math_V2.max2(attributePath: TEXT): NUMERIC",
      "Math_V2.min2(attributePath: TEXT): NUMERIC",
      "Math_V2.sum(attributePath: TEXT): NUMERIC"
  );

  @McpTool(
      name = "listMathFunctions",
      description = "Listet alle INTERLIS-Math-Funktionen für die gewünschte Sprachversion mit Signatur und Rückgabetyp auf."
  )
  public Map<String, Object> listMathFunctions(
      @McpToolParam(description = "INTERLIS Sprachversion (2.3 oder 2.4)", required = false) @Nullable String iliVersion
  ) {
    String ili = normalizeIliVersion(iliVersion);
    List<String> functions = "2.3".equals(ili) ? MATH_FUNCTIONS_23 : MATH_FUNCTIONS_24;
    return Map.of(
        "iliVersion", ili,
        "functions", functions.stream().map(MathTools::toFunctionEntry).toList()
    );
  }

  private static String normalizeIliVersion(@Nullable String iliVersion) {
    String ili = (iliVersion == null || iliVersion.isBlank()) ? "2.4" : iliVersion.trim();
    if (!"2.3".equals(ili) && !"2.4".equals(ili)) {
      throw new IllegalArgumentException("iliVersion must be '2.3' oder '2.4'.");
    }
    return ili;
  }

  private static Map<String, String> toFunctionEntry(String signature) {
    int lastColon = signature.lastIndexOf(':');
    if (lastColon < 0) {
      return Map.of(
          "function", signature.trim(),
          "returns", ""
      );
    }
    return Map.of(
        "function", signature.substring(0, lastColon).trim(),
        "returns", signature.substring(lastColon + 1).trim()
    );
  }
}
