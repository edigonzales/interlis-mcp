package ch.so.agi.mcp.change;

import java.util.ArrayList;
import java.util.List;

/** Lexical comment spans; quoted strings are never interpreted as comments. */
public final class IliComments {
  private IliComments() {}

  public static List<IliSourceSpan> spans(String source) {
    var document = IliSourceDocument.of(source);
    var spans = new ArrayList<IliSourceSpan>();
    int i = 0;
    while (i < source.length()) {
      if (source.charAt(i) == '"') {
        i++;
        while (i < source.length()) {
          char c = source.charAt(i++);
          if (c == '\\' && i < source.length()) i++;
          else if (c == '"') break;
        }
      } else if (source.startsWith("!!", i)) {
        int start = i;
        while (i < source.length() && source.charAt(i) != '\n' && source.charAt(i) != '\r') i++;
        spans.add(document.span(start, i));
      } else if (source.startsWith("/*", i)) {
        int start = i;
        i += 2;
        int depth = 1;
        while (i < source.length() && depth > 0) {
          if (source.startsWith("/*", i)) { depth++; i += 2; }
          else if (source.startsWith("*/", i)) { depth--; i += 2; }
          else i++;
        }
        spans.add(document.span(start, i));
      } else i++;
    }
    return List.copyOf(spans);
  }

  /** Preserve line positions and token separation while removing comment content. */
  public static String whitespace(String comment) {
    return comment.replaceAll("[^\\r\\n]", " ");
  }

  public static List<IliTextPatch> removalPatches(IliSourceDocument document) {
    String source = document.text();
    var comments = spans(source);
    var patches = new ArrayList<IliTextPatch>();
    for (var span : comments) {
      String comment = source.substring(span.startOffset(), span.endOffset());
      patches.add(IliTextPatch.replace(span, whitespace(comment), "Remove comment (REMOVE_COMMENTS)"));
      var name = java.util.regex.Pattern.compile("!!@\\s*name\\s*=\\s*\"([A-Za-z][A-Za-z0-9_]*)\"\\s*").matcher(comment);
      if (!name.matches()) continue;
      int next = span.endOffset();
      while (next < source.length()) {
        if (Character.isWhitespace(source.charAt(next))) { next++; continue; }
        final int offset = next;
        var following = comments.stream().filter(s -> s.startOffset() == offset).findFirst();
        if (following.isPresent()) { next = following.get().endOffset(); continue; }
        break;
      }
      var prefix = java.util.regex.Pattern.compile("(?:MANDATORY CONSTRAINT|EXISTENCE CONSTRAINT|SET CONSTRAINT(?: \\(BASKET\\))?|UNIQUE(?: \\(BASKET\\))?|CONSTRAINT)(?=\\s)").matcher(source.substring(next));
      if (!prefix.lookingAt()) continue;
      int anchor = next + prefix.end();
      if (source.substring(anchor).matches("(?s)^\\s+[A-Za-z][A-Za-z0-9_]*\\s*:.*")) continue;
      patches.add(IliTextPatch.insert(document, anchor, " " + name.group(1) + ":", "Preserve constraint name as native INTERLIS identifier"));
    }
    return List.copyOf(patches);
  }
}
