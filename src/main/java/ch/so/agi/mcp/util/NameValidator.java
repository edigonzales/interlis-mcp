package ch.so.agi.mcp.util;

import java.util.Set;
import java.util.regex.Pattern;

public final class NameValidator {
  private static final int MAX_LENGTH = 255;
  private static final Pattern ASCII = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");
  private static final Pattern UNICODE = Pattern.compile("^\\p{L}[\\p{L}\\p{Nd}_]*$");
  private static final Set<String> RESERVED_WORDS = Set.of(
      "ABSTRACT", "ACCORDING", "AGGREGATES", "AGGREGATION",
      "ALL", "AND", "ANY", "ANYCLASS",
      "ANYSTRUCTURE", "ARCS", "AREA", "AS",
      "ASSOCIATION", "AT", "ATTRIBUTE", "ATTRIBUTES",
      "BAG", "BASE", "BASED", "BASKET",
      "BINARY", "BLACKBOX", "BLANK", "BOOLEAN",
      "BY", "CARDINALITY", "CHARSET", "CIRCULAR",
      "CLASS", "CLOCKWISE", "CODE", "CONSTRAINT",
      "CONSTRAINTS", "CONTEXT", "CONTINUE", "CONTINUOUS",
      "CONTOUR", "CONTRACTED", "COORD", "COORD2",
      "COORD3", "COUNTERCLOCKWISE", "DATE", "DATETIME",
      "DEFAULT", "DEFERRED", "DEFINED", "DEGREES",
      "DEPENDS", "DERIVATIVES", "DERIVED", "DIM1",
      "DIM2", "DIRECTED", "DOMAIN", "END",
      "ENUMTREEVAL", "ENUMVAL", "EQUAL", "EXISTENCE",
      "EXTENDED", "EXTENDS", "EXTERNAL", "FINAL",
      "FIRST", "FIX", "FONT", "FORM",
      "FORMAT", "FREE", "FROM", "FUNCTION",
      "GENERIC", "GENERICS", "GRADS", "GRAPHIC",
      "HALIGNMENT", "HIDING", "I16", "I32",
      "IDENT", "IMPORTS", "IN", "INHERITANCE",
      "INSPECTION", "INTERLIS", "JOIN", "LAST",
      "LINE", "LINEATTR", "LINESIZE", "LIST",
      "LNBASE", "LOCAL", "MANDATORY", "METAOBJECT",
      "MODEL", "MTEXT", "MULTIAREA", "MULTICOORD",
      "MULTIPOLYLINE", "MULTISURFACE", "NAME", "NO",
      "NOINCREMENTALTRANSFER", "NOT", "NULL", "NUMERIC",
      "OBJECT", "OBJECTS", "OF", "OID",
      "ON", "OPTIONAL", "OR", "ORDERED",
      "OTHERS", "OVERLAPS", "PARAMETER", "PARENT",
      "PERIPHERY", "PI", "POLYLINE", "PROJECTION",
      "RADIANS", "REFERENCE", "REFSYS", "REFSYSTEM",
      "REQUIRED", "RESTRICTION", "ROTATION", "SET",
      "SIGN", "STRAIGHTS", "STRUCTURE", "SUBDIVISION",
      "SURFACE", "SYMBOLOGY", "TABLE", "TEXT",
      "THATAREA", "THIS", "THISAREA", "TID",
      "TIDSIZE", "TIMEOFDAY", "TO", "TOPIC",
      "TRANSFER", "TRANSIENT", "TRANSLATION", "TYPE",
      "UNDEFINED", "UNION", "UNIQUE", "UNIT",
      "UNQUALIFIED", "URI", "VALIGNMENT", "VERSION",
      "VERTEX", "VERTEXINFO", "VIEW", "WHEN",
      "WHERE", "WITH", "WITHOUT", "XMLNS");

  private final Pattern pattern;

  private NameValidator(Pattern pattern) {
    this.pattern = pattern;
  }

  public static NameValidator ascii() {
    return new NameValidator(ASCII);
  }

  public static NameValidator unicode() {
    return new NameValidator(UNICODE);
  }

  public static boolean isReservedWord(String value) {
    return RESERVED_WORDS.contains(value);
  }

  public void validateIdent(String value, String what) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(what + " is required.");
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(what + " must not exceed " + MAX_LENGTH + " characters.");
    }
    if (!pattern.matcher(value).matches()) {
      String rule = (pattern == ASCII) ? "[A-Za-z][A-Za-z0-9_]*" : "\\p{L}[\\p{L}\\p{Nd}_]*";
      throw new IllegalArgumentException(what + " must match " + rule
          + " (starts with a letter, then letters/digits/underscore). Got: '" + value + "'.");
    }
    if (isReservedWord(value)) {
      throw new IllegalArgumentException(what + " must not be an INTERLIS reserved word. Got: '" + value + "'.");
    }
  }

  public void validateFqn(String fqn, String what) {
    if (fqn == null || fqn.isBlank()) {
      throw new IllegalArgumentException(what + " is required.");
    }
    String[] parts = fqn.split("\\.");
    for (String p : parts) {
      validateIdent(p, what + " segment");
    }
  }
}
