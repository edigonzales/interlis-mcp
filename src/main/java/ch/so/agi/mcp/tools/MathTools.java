package ch.so.agi.mcp.tools;

import org.springaicommunity.mcp.annotation.McpTool;
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
      description = "Listet alle INTERLIS-Math-Funktionen für 2.3 und 2.4 mit Signatur und Rückgabetyp auf."
  )
  public Map<String, Object> listMathFunctions() {
    return Map.of(
        "2.3", MATH_FUNCTIONS_23,
        "2.4", MATH_FUNCTIONS_24
    );
  }
}
