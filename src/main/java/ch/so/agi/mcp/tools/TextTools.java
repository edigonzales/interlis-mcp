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
      "Text.compareToIgnoreCase(a: TEXT; b: TEXT): NUMERIC",
      "Text.compareToIgnoreCaseM(a: MTEXT; b: MTEXT): NUMERIC",
      "Text.concat(a: TEXT; b: TEXT): TEXT",
      "Text.concatM(a: MTEXT; b: MTEXT): MTEXT",
      "Text.endsWith(val: TEXT; suffix: TEXT): BOOLEAN",
      "Text.endsWithM(val: MTEXT; suffix: MTEXT): BOOLEAN",
      "Text.equalsIgnoreCase(val: TEXT; anotherVal: TEXT): BOOLEAN",
      "Text.equalsIgnoreCaseM(val: MTEXT; anotherVal: MTEXT): BOOLEAN",
      "Text.indexOf(val: TEXT; str: TEXT; fromIndex: NUMERIC): NUMERIC",
      "Text.indexOfM(val: MTEXT; str: MTEXT; fromIndex: NUMERIC): NUMERIC",
      "Text.lastIndexOf(val: TEXT; str: TEXT; fromIndex: NUMERIC): NUMERIC",
      "Text.lastIndexOfM(val: MTEXT; str: MTEXT; fromIndex: NUMERIC): NUMERIC",
      "Text.matches(val: TEXT; regex: TEXT): BOOLEAN",
      "Text.matchesM(val: MTEXT; regex: MTEXT): BOOLEAN",
      "Text.replace(val: TEXT; old: TEXT; new: TEXT): TEXT",
      "Text.replaceM(val: MTEXT; old: MTEXT; new: MTEXT): MTEXT",
      "Text.startsWith(val: TEXT; prefix: TEXT): BOOLEAN",
      "Text.startsWithM(val: MTEXT; prefix: MTEXT): BOOLEAN",
      "Text.substring(val: TEXT; beginIndex: NUMERIC; endIndex: NUMERIC): TEXT",
      "Text.substringM(val: MTEXT; beginIndex: NUMERIC; endIndex: NUMERIC): MTEXT",
      "Text.toLowerCase(val: TEXT): TEXT",
      "Text.toLowerCaseM(val: MTEXT): MTEXT",
      "Text.toUpperCase(val: TEXT): TEXT",
      "Text.toUpperCaseM(val: MTEXT): MTEXT"
  );

  private static final List<String> TEXT_FUNCTIONS_24 = List.of(
      "Text_V2.compareToIgnoreCase(a: TEXT; b: TEXT): NUMERIC",
      "Text_V2.compareToIgnoreCaseM(a: MTEXT; b: MTEXT): NUMERIC",
      "Text_V2.concat(a: TEXT; b: TEXT): TEXT",
      "Text_V2.concatM(a: MTEXT; b: MTEXT): MTEXT",
      "Text_V2.endsWith(val: TEXT; suffix: TEXT): BOOLEAN",
      "Text_V2.endsWithM(val: MTEXT; suffix: MTEXT): BOOLEAN",
      "Text_V2.equalsIgnoreCase(val: TEXT; anotherVal: TEXT): BOOLEAN",
      "Text_V2.equalsIgnoreCaseM(val: MTEXT; anotherVal: MTEXT): BOOLEAN",
      "Text_V2.indexOf(val: TEXT; str: TEXT; fromIndex: NUMERIC): NUMERIC",
      "Text_V2.indexOfM(val: MTEXT; str: MTEXT; fromIndex: NUMERIC): NUMERIC",
      "Text_V2.lastIndexOf(val: TEXT; str: TEXT; fromIndex: NUMERIC): NUMERIC",
      "Text_V2.lastIndexOfM(val: MTEXT; str: MTEXT; fromIndex: NUMERIC): NUMERIC",
      "Text_V2.matches(val: TEXT; regex: TEXT): BOOLEAN",
      "Text_V2.matchesM(val: MTEXT; regex: MTEXT): BOOLEAN",
      "Text_V2.replace(val: TEXT; old: TEXT; new: TEXT): TEXT",
      "Text_V2.replaceM(val: MTEXT; old: MTEXT; new: MTEXT): MTEXT",
      "Text_V2.startsWith(val: TEXT; prefix: TEXT): BOOLEAN",
      "Text_V2.startsWithM(val: MTEXT; prefix: MTEXT): BOOLEAN",
      "Text_V2.substring(val: TEXT; beginIndex: NUMERIC; endIndex: NUMERIC): TEXT",
      "Text_V2.substringM(val: MTEXT; beginIndex: NUMERIC; endIndex: NUMERIC): MTEXT",
      "Text_V2.toLowerCase(val: TEXT): TEXT",
      "Text_V2.toLowerCaseM(val: MTEXT): MTEXT",
      "Text_V2.toUpperCase(val: TEXT): TEXT",
      "Text_V2.toUpperCaseM(val: MTEXT): MTEXT"
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
