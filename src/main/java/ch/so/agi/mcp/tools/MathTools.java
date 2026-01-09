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
      "add(a: NUMERIC; b: NUMERIC): NUMERIC",
      "sub(a: NUMERIC; b: NUMERIC): NUMERIC",
      "mul(a: NUMERIC; b: NUMERIC): NUMERIC",
      "div(a: NUMERIC; b: NUMERIC): NUMERIC",
      "abs(a: NUMERIC): NUMERIC",
      "acos(a: NUMERIC): NUMERIC",
      "asin(a: NUMERIC): NUMERIC",
      "atan(a: NUMERIC): NUMERIC",
      "atan2(ordinate: NUMERIC; abscissa: NUMERIC): NUMERIC",
      "cbrt(a: NUMERIC): NUMERIC",
      "cos(a: NUMERIC): NUMERIC",
      "cosh(a: NUMERIC): NUMERIC",
      "exp(a: NUMERIC): NUMERIC",
      "hypot(a: NUMERIC; b: NUMERIC): NUMERIC",
      "log(a: NUMERIC): NUMERIC",
      "log10(a: NUMERIC): NUMERIC",
      "pow(a: NUMERIC; b: NUMERIC): NUMERIC",
      "round(a: NUMERIC): NUMERIC",
      "signum(a: NUMERIC): NUMERIC",
      "sin(a: NUMERIC): NUMERIC",
      "sinh(a: NUMERIC): NUMERIC",
      "sqrt(a: NUMERIC): NUMERIC",
      "tan(a: NUMERIC): NUMERIC",
      "tanh(a: NUMERIC): NUMERIC",
      "max(a: NUMERIC; b: NUMERIC): NUMERIC",
      "min(a: NUMERIC; b: NUMERIC): NUMERIC",
      "avg(attributePath: TEXT): NUMERIC",
      "max2(attributePath: TEXT): NUMERIC",
      "min2(attributePath: TEXT): NUMERIC",
      "sum(attributePath: TEXT): NUMERIC"
  );

  private static final List<String> MATH_FUNCTIONS_24 = List.of(
      "add(a: NUMERIC; b: NUMERIC): NUMERIC",
      "sub(a: NUMERIC; b: NUMERIC): NUMERIC",
      "mul(a: NUMERIC; b: NUMERIC): NUMERIC",
      "div(a: NUMERIC; b: NUMERIC): NUMERIC",
      "abs(a: NUMERIC): NUMERIC",
      "acos(a: NUMERIC): NUMERIC",
      "asin(a: NUMERIC): NUMERIC",
      "atan(a: NUMERIC): NUMERIC",
      "atan2(ordinate: NUMERIC; abscissa: NUMERIC): NUMERIC",
      "cbrt(a: NUMERIC): NUMERIC",
      "cos(a: NUMERIC): NUMERIC",
      "cosh(a: NUMERIC): NUMERIC",
      "exp(a: NUMERIC): NUMERIC",
      "hypot(a: NUMERIC; b: NUMERIC): NUMERIC",
      "log(a: NUMERIC): NUMERIC",
      "log10(a: NUMERIC): NUMERIC",
      "pow(a: NUMERIC; b: NUMERIC): NUMERIC",
      "round(a: NUMERIC): NUMERIC",
      "signum(a: NUMERIC): NUMERIC",
      "sin(a: NUMERIC): NUMERIC",
      "sinh(a: NUMERIC): NUMERIC",
      "sqrt(a: NUMERIC): NUMERIC",
      "tan(a: NUMERIC): NUMERIC",
      "tanh(a: NUMERIC): NUMERIC",
      "max(a: NUMERIC; b: NUMERIC): NUMERIC",
      "min(a: NUMERIC; b: NUMERIC): NUMERIC",
      "avg(attributePath: TEXT): NUMERIC",
      "max2(attributePath: TEXT): NUMERIC",
      "min2(attributePath: TEXT): NUMERIC",
      "sum(attributePath: TEXT): NUMERIC"
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
