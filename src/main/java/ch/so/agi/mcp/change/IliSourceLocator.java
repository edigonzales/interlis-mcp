package ch.so.agi.mcp.change;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class IliSourceLocator {

  public enum BlockKind {
    MODEL,
    TOPIC,
    CLASS,
    STRUCTURE,
    ASSOCIATION,
    VIEW,
    GRAPHIC
  }

  public record BlockLocation(
      BlockKind kind,
      String name,
      IliSourceSpan declarationSpan,
      IliSourceSpan headerSpan,
      IliSourceSpan bodySpan,
      IliSourceSpan endMarkerSpan) {}

  private enum TokenKind {
    IDENTIFIER,
    STRING,
    SYMBOL
  }

  private record Token(TokenKind kind, String text, int startOffset, int endOffset, int line) {
    boolean isIdentifier(String expected) {
      return kind == TokenKind.IDENTIFIER && text.equals(expected);
    }

    boolean isKeyword(String expected) {
      return kind == TokenKind.IDENTIFIER && text.equalsIgnoreCase(expected);
    }

    boolean isSymbol(String expected) {
      return kind == TokenKind.SYMBOL && text.equals(expected);
    }
  }

  private record OpenBlock(
      BlockKind kind,
      String name,
      int openingTokenIndex,
      int equalsTokenIndex) {}

  public List<BlockLocation> locateNamedBlocks(
      IliSourceDocument document,
      BlockKind kind,
      String name) {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(kind, "kind");
    requireIdentifier(name, "name");
    return parseBlocks(document).stream()
        .filter(block -> block.kind() == kind && block.name().equals(name))
        .toList();
  }

  public BlockLocation locateNamedBlock(
      IliSourceDocument document,
      BlockKind kind,
      String name) {
    List<BlockLocation> matches = locateNamedBlocks(document, kind, name);
    if (matches.isEmpty()) {
      throw new IllegalArgumentException(kind + " '" + name + "' not found in source.");
    }
    if (matches.size() > 1) {
      throw new IllegalArgumentException(
          kind + " '" + name + "' is ambiguous in source; provide an approximate source line.");
    }
    return matches.getFirst();
  }

  public BlockLocation locateNamedBlock(
      IliSourceDocument document,
      BlockKind kind,
      String name,
      int approximateLine) {
    if (approximateLine < 1) {
      throw new IllegalArgumentException("approximateLine must be >= 1.");
    }
    List<BlockLocation> matches = locateNamedBlocks(document, kind, name);
    if (matches.isEmpty()) {
      throw new IllegalArgumentException(kind + " '" + name + "' not found in source.");
    }
    return chooseClosest(
        matches,
        approximateLine,
        block -> block.headerSpan().startLine(),
        kind + " '" + name + "'");
  }

  public IliSourceSpan locateAttribute(
      IliSourceDocument document,
      BlockLocation container,
      String attributeName,
      int approximateLine) {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(container, "container");
    requireIdentifier(attributeName, "attributeName");
    if (approximateLine < 1) {
      throw new IllegalArgumentException("approximateLine must be >= 1.");
    }

    List<Token> tokens = tokenize(document);
    List<IliSourceSpan> candidates = new ArrayList<>();
    for (int i = 0; i + 1 < tokens.size(); i++) {
      Token token = tokens.get(i);
      if (token.startOffset() < container.bodySpan().startOffset()
          || token.startOffset() >= container.bodySpan().endOffset()) {
        continue;
      }
      if (!token.isIdentifier(attributeName) || !tokens.get(i + 1).isSymbol(":")) {
        continue;
      }
      int semicolon = findSemicolon(tokens, i + 2, container.bodySpan().endOffset());
      if (semicolon < 0) {
        continue;
      }
      candidates.add(document.span(token.startOffset(), tokens.get(semicolon).endOffset()));
    }

    if (candidates.isEmpty()) {
      throw new IllegalArgumentException(
          "Attribute '" + attributeName + "' not found in " + container.kind() + " '" + container.name() + "'.");
    }
    return chooseClosest(
        candidates,
        approximateLine,
        IliSourceSpan::startLine,
        "Attribute '" + attributeName + "'");
  }

  private List<BlockLocation> parseBlocks(IliSourceDocument document) {
    List<Token> tokens = tokenize(document);
    Deque<OpenBlock> stack = new ArrayDeque<>();
    List<BlockLocation> result = new ArrayList<>();

    for (int i = 0; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      BlockKind openingKind = blockKind(token);
      if (openingKind != null && i + 1 < tokens.size() && tokens.get(i + 1).kind() == TokenKind.IDENTIFIER) {
        int equalsTokenIndex = findHeaderEquals(tokens, i + 2);
        if (equalsTokenIndex >= 0) {
          stack.push(new OpenBlock(openingKind, tokens.get(i + 1).text(), i, equalsTokenIndex));
        }
        continue;
      }

      if (!token.isKeyword("END") || i + 2 >= tokens.size()) {
        continue;
      }
      Token nameToken = tokens.get(i + 1);
      Token terminator = tokens.get(i + 2);
      if (nameToken.kind() != TokenKind.IDENTIFIER
          || !(terminator.isSymbol(";") || terminator.isSymbol("."))) {
        continue;
      }

      OpenBlock opening = removeMatchingOpenBlock(stack, nameToken.text());
      if (opening == null) {
        continue;
      }
      Token openingToken = tokens.get(opening.openingTokenIndex());
      Token equalsToken = tokens.get(opening.equalsTokenIndex());
      result.add(new BlockLocation(
          opening.kind(),
          opening.name(),
          document.span(openingToken.startOffset(), terminator.endOffset()),
          document.span(openingToken.startOffset(), equalsToken.endOffset()),
          document.span(equalsToken.endOffset(), token.startOffset()),
          document.span(token.startOffset(), terminator.endOffset())));
    }

    result.sort(Comparator.comparingInt(block -> block.declarationSpan().startOffset()));
    return List.copyOf(result);
  }

  private OpenBlock removeMatchingOpenBlock(Deque<OpenBlock> stack, String name) {
    if (stack.isEmpty()) {
      return null;
    }
    List<OpenBlock> skipped = new ArrayList<>();
    OpenBlock match = null;
    while (!stack.isEmpty()) {
      OpenBlock current = stack.pop();
      if (current.name().equals(name)) {
        match = current;
        break;
      }
      skipped.add(current);
    }
    for (int i = skipped.size() - 1; i >= 0; i--) {
      stack.push(skipped.get(i));
    }
    return match;
  }

  private int findHeaderEquals(List<Token> tokens, int startIndex) {
    for (int i = startIndex; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      if (token.isSymbol("=")) {
        return i;
      }
      if (token.isSymbol(";") || token.isKeyword("END")) {
        return -1;
      }
    }
    return -1;
  }

  private int findSemicolon(List<Token> tokens, int startIndex, int limitOffset) {
    for (int i = startIndex; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      if (token.startOffset() >= limitOffset) {
        return -1;
      }
      if (token.isSymbol(";")) {
        return i;
      }
    }
    return -1;
  }

  private BlockKind blockKind(Token token) {
    if (token.kind() != TokenKind.IDENTIFIER) {
      return null;
    }
    try {
      BlockKind candidate = BlockKind.valueOf(token.text().toUpperCase(Locale.ROOT));
      return EnumSet.allOf(BlockKind.class).contains(candidate) ? candidate : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private List<Token> tokenize(IliSourceDocument document) {
    String source = document.text();
    List<Token> tokens = new ArrayList<>();
    int i = 0;
    while (i < source.length()) {
      char c = source.charAt(i);

      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (c == '!' && i + 1 < source.length() && source.charAt(i + 1) == '!') {
        i = skipLineComment(source, i + 2);
        continue;
      }
      if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
        i = skipBlockComment(source, i + 2);
        continue;
      }
      if (c == '"') {
        int end = scanString(source, i);
        tokens.add(new Token(TokenKind.STRING, source.substring(i, end), i, end, document.lineOfOffset(i)));
        i = end;
        continue;
      }
      if (isIdentifierStart(c)) {
        int end = i + 1;
        while (end < source.length() && isIdentifierPart(source.charAt(end))) {
          end++;
        }
        tokens.add(new Token(TokenKind.IDENTIFIER, source.substring(i, end), i, end, document.lineOfOffset(i)));
        i = end;
        continue;
      }

      tokens.add(new Token(TokenKind.SYMBOL, String.valueOf(c), i, i + 1, document.lineOfOffset(i)));
      i++;
    }
    return tokens;
  }

  private int skipLineComment(String source, int start) {
    int i = start;
    while (i < source.length() && source.charAt(i) != '\r' && source.charAt(i) != '\n') {
      i++;
    }
    return i;
  }

  private int skipBlockComment(String source, int start) {
    int i = start;
    while (i + 1 < source.length()) {
      if (source.charAt(i) == '*' && source.charAt(i + 1) == '/') {
        return i + 2;
      }
      i++;
    }
    return source.length();
  }

  private int scanString(String source, int start) {
    int i = start + 1;
    boolean escaped = false;
    while (i < source.length()) {
      char c = source.charAt(i);
      if (escaped) {
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else if (c == '"') {
        return i + 1;
      }
      i++;
    }
    return source.length();
  }

  private boolean isIdentifierStart(char c) {
    return Character.isLetter(c) || c == '_';
  }

  private boolean isIdentifierPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  private void requireIdentifier(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required.");
    }
  }

  private <T> T chooseClosest(
      List<T> candidates,
      int approximateLine,
      java.util.function.ToIntFunction<T> lineExtractor,
      String description) {
    List<T> ordered = candidates.stream()
        .sorted(Comparator.comparingInt(candidate -> Math.abs(lineExtractor.applyAsInt(candidate) - approximateLine)))
        .toList();
    if (ordered.size() == 1) {
      return ordered.getFirst();
    }
    int firstDistance = Math.abs(lineExtractor.applyAsInt(ordered.getFirst()) - approximateLine);
    int secondDistance = Math.abs(lineExtractor.applyAsInt(ordered.get(1)) - approximateLine);
    if (firstDistance == secondDistance) {
      throw new IllegalArgumentException(description + " is ambiguous near line " + approximateLine + ".");
    }
    return ordered.getFirst();
  }
}
