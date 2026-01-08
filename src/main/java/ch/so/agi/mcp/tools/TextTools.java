package ch.so.agi.mcp.tools;

import org.springaicommunity.mcp.annotation.McpTool;
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
      description = "Listet alle INTERLIS-Text-Funktionen für 2.3 und 2.4 mit Signatur und Rückgabetyp auf."
  )
  public Map<String, Object> listTextFunctions() {
    return Map.of(
        "2.3", TEXT_FUNCTIONS_23,
        "2.4", TEXT_FUNCTIONS_24
    );
  }
}
