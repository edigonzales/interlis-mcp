package ch.so.agi.mcp.tools;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class TextTools {

  private static final List<String> TEXT_FUNCTIONS_23 = List.of(
      "compareToIgnoreCase(a: TEXT; b: TEXT): NUMERIC",
      "compareToIgnoreCaseM(a: MTEXT; b: MTEXT): NUMERIC",
      "concat(a: TEXT; b: TEXT): TEXT",
      "concatM(a: MTEXT; b: MTEXT): MTEXT",
      "endsWith(val: TEXT; suffix: TEXT): BOOLEAN",
      "endsWithM(val: MTEXT; suffix: MTEXT): BOOLEAN",
      "equalsIgnoreCase(val: TEXT; anotherVal: TEXT): BOOLEAN",
      "equalsIgnoreCaseM(val: MTEXT; anotherVal: MTEXT): BOOLEAN",
      "indexOf(val: TEXT; str: TEXT; fromIndex: NUMERIC): NUMERIC",
      "indexOfM(val: MTEXT; str: MTEXT; fromIndex: NUMERIC): NUMERIC",
      "lastIndexOf(val: TEXT; str: TEXT; fromIndex: NUMERIC): NUMERIC",
      "lastIndexOfM(val: MTEXT; str: MTEXT; fromIndex: NUMERIC): NUMERIC",
      "matches(val: TEXT; regex: TEXT): BOOLEAN",
      "matchesM(val: MTEXT; regex: MTEXT): BOOLEAN",
      "replace(val: TEXT; old: TEXT; new: TEXT): TEXT",
      "replaceM(val: MTEXT; old: MTEXT; new: MTEXT): MTEXT",
      "startsWith(val: TEXT; prefix: TEXT): BOOLEAN",
      "startsWithM(val: MTEXT; prefix: MTEXT): BOOLEAN",
      "substring(val: TEXT; beginIndex: NUMERIC; endIndex: NUMERIC): TEXT",
      "substringM(val: MTEXT; beginIndex: NUMERIC; endIndex: NUMERIC): MTEXT",
      "toLowerCase(val: TEXT): TEXT",
      "toLowerCaseM(val: MTEXT): MTEXT",
      "toUpperCase(val: TEXT): TEXT",
      "toUpperCaseM(val: MTEXT): MTEXT"
  );

  private static final List<String> TEXT_FUNCTIONS_24 = List.of(
      "compareToIgnoreCase(a: TEXT; b: TEXT): NUMERIC",
      "compareToIgnoreCaseM(a: MTEXT; b: MTEXT): NUMERIC",
      "concat(a: TEXT; b: TEXT): TEXT",
      "concatM(a: MTEXT; b: MTEXT): MTEXT",
      "endsWith(val: TEXT; suffix: TEXT): BOOLEAN",
      "endsWithM(val: MTEXT; suffix: MTEXT): BOOLEAN",
      "equalsIgnoreCase(val: TEXT; anotherVal: TEXT): BOOLEAN",
      "equalsIgnoreCaseM(val: MTEXT; anotherVal: MTEXT): BOOLEAN",
      "indexOf(val: TEXT; str: TEXT; fromIndex: NUMERIC): NUMERIC",
      "indexOfM(val: MTEXT; str: MTEXT; fromIndex: NUMERIC): NUMERIC",
      "lastIndexOf(val: TEXT; str: TEXT; fromIndex: NUMERIC): NUMERIC",
      "lastIndexOfM(val: MTEXT; str: MTEXT; fromIndex: NUMERIC): NUMERIC",
      "matches(val: TEXT; regex: TEXT): BOOLEAN",
      "matchesM(val: MTEXT; regex: MTEXT): BOOLEAN",
      "replace(val: TEXT; old: TEXT; new: TEXT): TEXT",
      "replaceM(val: MTEXT; old: MTEXT; new: MTEXT): MTEXT",
      "startsWith(val: TEXT; prefix: TEXT): BOOLEAN",
      "startsWithM(val: MTEXT; prefix: MTEXT): BOOLEAN",
      "substring(val: TEXT; beginIndex: NUMERIC; endIndex: NUMERIC): TEXT",
      "substringM(val: MTEXT; beginIndex: NUMERIC; endIndex: NUMERIC): MTEXT",
      "toLowerCase(val: TEXT): TEXT",
      "toLowerCaseM(val: MTEXT): MTEXT",
      "toUpperCase(val: TEXT): TEXT",
      "toUpperCaseM(val: MTEXT): MTEXT"
  );

  @McpTool(
      name = "listTextFunctions",
      description = "Listet alle INTERLIS-Text-Funktionen für die gewünschte Sprachversion mit Signatur und Rückgabetyp auf."
  )
  public Map<String, Object> listTextFunctions(
      @McpToolParam(description = "INTERLIS Sprachversion (2.3 oder 2.4)", required = false) @Nullable String iliVersion
  ) {
    String ili = normalizeIliVersion(iliVersion);
    List<String> functions = "2.3".equals(ili) ? TEXT_FUNCTIONS_23 : TEXT_FUNCTIONS_24;
    return Map.of(
        "iliVersion", ili,
        "functions", functions.stream().map(TextTools::toFunctionEntry).toList()
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
