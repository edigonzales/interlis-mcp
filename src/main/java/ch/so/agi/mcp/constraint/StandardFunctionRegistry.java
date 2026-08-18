package ch.so.agi.mcp.constraint;

import static ch.so.agi.mcp.constraint.ConstraintExpression.ArgumentSemantics.ATTRIBUTE_PATH;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ArgumentSemantics.VALUE;
import static ch.so.agi.mcp.constraint.ConstraintExpression.IliVersion.ILI_23;
import static ch.so.agi.mcp.constraint.ConstraintExpression.IliVersion.ILI_24;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ResultTypeRule.DECLARED;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ResultTypeRule.PROPAGATE_NULLABILITY;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.BOOLEAN;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.MTEXT;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.NUMERIC;
import static ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind.TEXT;

import ch.so.agi.mcp.constraint.ConstraintExpression.ArgumentSemantics;
import ch.so.agi.mcp.constraint.ConstraintExpression.ArgumentSpec;
import ch.so.agi.mcp.constraint.ConstraintExpression.FunctionDefinition;
import ch.so.agi.mcp.constraint.ConstraintExpression.FunctionSyntax;
import ch.so.agi.mcp.constraint.ConstraintExpression.IliVersion;
import ch.so.agi.mcp.constraint.ConstraintExpression.InfixSyntax;
import ch.so.agi.mcp.constraint.ConstraintExpression.LanguageProfile;
import ch.so.agi.mcp.constraint.ConstraintExpression.ResultTypeRule;
import ch.so.agi.mcp.constraint.ConstraintExpression.ScalarKind;
import ch.so.agi.mcp.constraint.ConstraintExpression.SurfaceSyntax;
import ch.so.agi.mcp.constraint.ConstraintExpression.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Structured registry for the standard INTERLIS Math/Text constraint functions.
 *
 * <p>The registry is the single source for function signatures, stable semantic IDs, IR argument
 * types and version-specific names. A semantic operation has one {@link FunctionDefinition} even
 * when INTERLIS 2.3 and 2.4 use different model names or 2.4 offers native arithmetic syntax.</p>
 */
public final class StandardFunctionRegistry {

  public enum Family {
    MATH,
    TEXT
  }

  public record Parameter(
      String name,
      String declaredType,
      Type irType,
      ArgumentSemantics semantics) {
    public Parameter {
      requireText(name, "parameter name");
      requireText(declaredType, "parameter declaredType");
      Objects.requireNonNull(irType, "irType");
      Objects.requireNonNull(semantics, "semantics");
    }

    private ArgumentSpec argumentSpec() {
      return new ArgumentSpec(irType, semantics);
    }
  }

  public record StandardFunction(
      String semanticId,
      Family family,
      String localName,
      List<Parameter> parameters,
      String declaredReturnType,
      FunctionDefinition definition) {
    public StandardFunction {
      requireText(semanticId, "semanticId");
      Objects.requireNonNull(family, "family");
      requireText(localName, "localName");
      parameters = parameters == null ? List.of() : List.copyOf(parameters);
      requireText(declaredReturnType, "declaredReturnType");
      Objects.requireNonNull(definition, "definition");
      if (!semanticId.equals(definition.semanticId())) {
        throw new IllegalArgumentException("Standard function and IR definition semanticId must match.");
      }
    }

    public String modelName(IliVersion version) {
      LanguageProfile profile = LanguageProfile.forVersion(version);
      return family == Family.MATH ? profile.mathModel() : profile.textModel();
    }

    public String qualifiedName(IliVersion version) {
      return modelName(version) + "." + localName;
    }

    public String functionSignature(IliVersion version) {
      String arguments = parameters.stream()
          .map(parameter -> parameter.name() + ": " + parameter.declaredType())
          .reduce((left, right) -> left + "; " + right)
          .orElse("");
      return qualifiedName(version) + "(" + arguments + ")";
    }

    public String signature(IliVersion version) {
      return functionSignature(version) + ": " + declaredReturnType;
    }
  }

  private static final List<StandardFunction> FUNCTIONS = buildFunctions();
  private static final Map<String, StandardFunction> BY_SEMANTIC_ID = semanticIndex(FUNCTIONS);

  private StandardFunctionRegistry() {
  }

  public static List<StandardFunction> all() {
    return FUNCTIONS;
  }

  public static List<StandardFunction> functions(Family family) {
    Objects.requireNonNull(family, "family");
    return FUNCTIONS.stream().filter(function -> function.family() == family).toList();
  }

  public static Optional<StandardFunction> findBySemanticId(String semanticId) {
    if (semanticId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(BY_SEMANTIC_ID.get(semanticId));
  }

  public static Optional<StandardFunction> findByQualifiedName(IliVersion version, String qualifiedName) {
    Objects.requireNonNull(version, "version");
    if (qualifiedName == null || qualifiedName.isBlank()) {
      return Optional.empty();
    }
    String normalized = qualifiedName.trim();
    return FUNCTIONS.stream()
        .filter(function -> function.qualifiedName(version).equals(normalized))
        .findFirst();
  }

  public static Optional<StandardFunction> findByOperator(IliVersion version, String operator) {
    Objects.requireNonNull(version, "version");
    if (operator == null || operator.isBlank()) {
      return Optional.empty();
    }
    String normalized = operator.trim();
    return FUNCTIONS.stream()
        .filter(function -> {
          SurfaceSyntax syntax;
          try {
            syntax = function.definition().syntax(version);
          } catch (IllegalArgumentException ex) {
            return false;
          }
          return syntax instanceof InfixSyntax infix && normalized.equals(infix.symbol());
        })
        .findFirst();
  }

  private static List<StandardFunction> buildFunctions() {
    List<StandardFunction> functions = new ArrayList<>();

    functions.add(mathBinary("NUMERIC_ADD", "add", "+"));
    functions.add(mathBinary("NUMERIC_SUB", "sub", "-"));
    functions.add(mathBinary("NUMERIC_MUL", "mul", "*"));
    functions.add(mathBinary("NUMERIC_DIV", "div", "/"));
    functions.add(mathUnary("NUMERIC_ABS", "abs"));
    functions.add(mathUnary("NUMERIC_ACOS", "acos"));
    functions.add(mathUnary("NUMERIC_ASIN", "asin"));
    functions.add(mathUnary("NUMERIC_ATAN", "atan"));
    functions.add(math("NUMERIC_ATAN2", "atan2", List.of(
        value("ordinate", "NUMERIC", NUMERIC),
        value("abscissa", "NUMERIC", NUMERIC)), "NUMERIC", NUMERIC, PROPAGATE_NULLABILITY));
    functions.add(mathUnary("NUMERIC_CBRT", "cbrt"));
    functions.add(mathUnary("NUMERIC_COS", "cos"));
    functions.add(mathUnary("NUMERIC_COSH", "cosh"));
    functions.add(mathUnary("NUMERIC_EXP", "exp"));
    functions.add(math("NUMERIC_HYPOT", "hypot", List.of(
        value("a", "NUMERIC", NUMERIC),
        value("b", "NUMERIC", NUMERIC)), "NUMERIC", NUMERIC, PROPAGATE_NULLABILITY));
    functions.add(mathUnary("NUMERIC_LOG", "log"));
    functions.add(mathUnary("NUMERIC_LOG10", "log10"));
    functions.add(math("NUMERIC_POW", "pow", List.of(
        value("a", "NUMERIC", NUMERIC),
        value("b", "NUMERIC", NUMERIC)), "NUMERIC", NUMERIC, PROPAGATE_NULLABILITY));
    functions.add(mathUnary("NUMERIC_ROUND", "round"));
    functions.add(mathUnary("NUMERIC_SIGNUM", "signum"));
    functions.add(mathUnary("NUMERIC_SIN", "sin"));
    functions.add(mathUnary("NUMERIC_SINH", "sinh"));
    functions.add(mathUnary("NUMERIC_SQRT", "sqrt"));
    functions.add(mathUnary("NUMERIC_TAN", "tan"));
    functions.add(mathUnary("NUMERIC_TANH", "tanh"));
    functions.add(math("NUMERIC_MAX", "max", List.of(
        value("a", "NUMERIC", NUMERIC),
        value("b", "NUMERIC", NUMERIC)), "NUMERIC", NUMERIC, PROPAGATE_NULLABILITY));
    functions.add(math("NUMERIC_MIN", "min", List.of(
        value("a", "NUMERIC", NUMERIC),
        value("b", "NUMERIC", NUMERIC)), "NUMERIC", NUMERIC, PROPAGATE_NULLABILITY));
    functions.add(mathAggregate("COLLECTION_AVG", "avg"));
    functions.add(mathAggregate("COLLECTION_MAX", "max2"));
    functions.add(mathAggregate("COLLECTION_MIN", "min2"));
    functions.add(mathAggregate("COLLECTION_SUM", "sum"));

    functions.add(textBinary("TEXT_COMPARE_IGNORE_CASE", "compareToIgnoreCase", TEXT, NUMERIC));
    functions.add(textBinary("MTEXT_COMPARE_IGNORE_CASE", "compareToIgnoreCaseM", MTEXT, NUMERIC));
    functions.add(textBinary("TEXT_CONCAT", "concat", TEXT, TEXT));
    functions.add(textBinary("MTEXT_CONCAT", "concatM", MTEXT, MTEXT));
    functions.add(textBinary("TEXT_ENDS_WITH", "endsWith", TEXT, BOOLEAN));
    functions.add(textBinary("MTEXT_ENDS_WITH", "endsWithM", MTEXT, BOOLEAN));
    functions.add(textBinary("TEXT_EQUALS_IGNORE_CASE", "equalsIgnoreCase", TEXT, BOOLEAN));
    functions.add(textBinary("MTEXT_EQUALS_IGNORE_CASE", "equalsIgnoreCaseM", MTEXT, BOOLEAN));
    functions.add(textIndexed("TEXT_INDEX_OF", "indexOf", TEXT));
    functions.add(textIndexed("MTEXT_INDEX_OF", "indexOfM", MTEXT));
    functions.add(textIndexed("TEXT_LAST_INDEX_OF", "lastIndexOf", TEXT));
    functions.add(textIndexed("MTEXT_LAST_INDEX_OF", "lastIndexOfM", MTEXT));
    functions.add(textBinary("TEXT_MATCHES", "matches", TEXT, BOOLEAN, "val", "regex"));
    functions.add(textBinary("MTEXT_MATCHES", "matchesM", MTEXT, BOOLEAN, "val", "regex"));
    functions.add(textReplace("TEXT_REPLACE", "replace", TEXT));
    functions.add(textReplace("MTEXT_REPLACE", "replaceM", MTEXT));
    functions.add(textBinary("TEXT_STARTS_WITH", "startsWith", TEXT, BOOLEAN, "val", "prefix"));
    functions.add(textBinary("MTEXT_STARTS_WITH", "startsWithM", MTEXT, BOOLEAN, "val", "prefix"));
    functions.add(textSubstring("TEXT_SUBSTRING", "substring", TEXT));
    functions.add(textSubstring("MTEXT_SUBSTRING", "substringM", MTEXT));
    functions.add(textUnary("TEXT_TO_LOWER_CASE", "toLowerCase", TEXT));
    functions.add(textUnary("MTEXT_TO_LOWER_CASE", "toLowerCaseM", MTEXT));
    functions.add(textUnary("TEXT_TO_UPPER_CASE", "toUpperCase", TEXT));
    functions.add(textUnary("MTEXT_TO_UPPER_CASE", "toUpperCaseM", MTEXT));

    return List.copyOf(functions);
  }

  private static StandardFunction mathBinary(String semanticId, String localName, String operator24) {
    List<Parameter> parameters = List.of(
        value("a", "NUMERIC", NUMERIC),
        value("b", "NUMERIC", NUMERIC));
    return standard(
        semanticId,
        Family.MATH,
        localName,
        parameters,
        "NUMERIC",
        Type.scalar(NUMERIC),
        PROPAGATE_NULLABILITY,
        Map.of(
            ILI_23, new FunctionSyntax("Math." + localName),
            ILI_24, new InfixSyntax(operator24)));
  }

  private static StandardFunction mathUnary(String semanticId, String localName) {
    return math(
        semanticId,
        localName,
        List.of(value("a", "NUMERIC", NUMERIC)),
        "NUMERIC",
        NUMERIC,
        PROPAGATE_NULLABILITY);
  }

  private static StandardFunction mathAggregate(String semanticId, String localName) {
    return standard(
        semanticId,
        Family.MATH,
        localName,
        List.of(path("attributePath", "TEXT", NUMERIC)),
        "NUMERIC",
        Type.optionalScalar(NUMERIC),
        DECLARED,
        functionSyntax(Family.MATH, localName));
  }

  private static StandardFunction math(
      String semanticId,
      String localName,
      List<Parameter> parameters,
      String declaredReturnType,
      ScalarKind returnKind,
      ResultTypeRule resultRule) {
    return standard(
        semanticId,
        Family.MATH,
        localName,
        parameters,
        declaredReturnType,
        Type.scalar(returnKind),
        resultRule,
        functionSyntax(Family.MATH, localName));
  }

  private static StandardFunction textBinary(
      String semanticId,
      String localName,
      ScalarKind textKind,
      ScalarKind returnKind) {
    return textBinary(semanticId, localName, textKind, returnKind, "a", "b");
  }

  private static StandardFunction textBinary(
      String semanticId,
      String localName,
      ScalarKind textKind,
      ScalarKind returnKind,
      String firstName,
      String secondName) {
    String declaredText = declaredText(textKind);
    return standard(
        semanticId,
        Family.TEXT,
        localName,
        List.of(
            value(firstName, declaredText, textKind),
            value(secondName, declaredText, textKind)),
        declaredType(returnKind),
        Type.scalar(returnKind),
        PROPAGATE_NULLABILITY,
        functionSyntax(Family.TEXT, localName));
  }

  private static StandardFunction textIndexed(String semanticId, String localName, ScalarKind textKind) {
    String declaredText = declaredText(textKind);
    return standard(
        semanticId,
        Family.TEXT,
        localName,
        List.of(
            value("val", declaredText, textKind),
            value("str", declaredText, textKind),
            value("fromIndex", "NUMERIC", NUMERIC)),
        "NUMERIC",
        Type.scalar(NUMERIC),
        PROPAGATE_NULLABILITY,
        functionSyntax(Family.TEXT, localName));
  }

  private static StandardFunction textReplace(String semanticId, String localName, ScalarKind textKind) {
    String declaredText = declaredText(textKind);
    return standard(
        semanticId,
        Family.TEXT,
        localName,
        List.of(
            value("val", declaredText, textKind),
            value("old", declaredText, textKind),
            value("new", declaredText, textKind)),
        declaredText,
        Type.scalar(textKind),
        PROPAGATE_NULLABILITY,
        functionSyntax(Family.TEXT, localName));
  }

  private static StandardFunction textSubstring(String semanticId, String localName, ScalarKind textKind) {
    String declaredText = declaredText(textKind);
    return standard(
        semanticId,
        Family.TEXT,
        localName,
        List.of(
            value("val", declaredText, textKind),
            value("beginIndex", "NUMERIC", NUMERIC),
            value("endIndex", "NUMERIC", NUMERIC)),
        declaredText,
        Type.scalar(textKind),
        PROPAGATE_NULLABILITY,
        functionSyntax(Family.TEXT, localName));
  }

  private static StandardFunction textUnary(String semanticId, String localName, ScalarKind textKind) {
    String declaredText = declaredText(textKind);
    return standard(
        semanticId,
        Family.TEXT,
        localName,
        List.of(value("val", declaredText, textKind)),
        declaredText,
        Type.scalar(textKind),
        PROPAGATE_NULLABILITY,
        functionSyntax(Family.TEXT, localName));
  }

  private static StandardFunction standard(
      String semanticId,
      Family family,
      String localName,
      List<Parameter> parameters,
      String declaredReturnType,
      Type resultType,
      ResultTypeRule resultRule,
      Map<IliVersion, SurfaceSyntax> syntax) {
    FunctionDefinition definition = new FunctionDefinition(
        semanticId,
        parameters.stream().map(Parameter::argumentSpec).toList(),
        resultType,
        resultRule,
        syntax);
    return new StandardFunction(
        semanticId,
        family,
        localName,
        parameters,
        declaredReturnType,
        definition);
  }

  private static Map<IliVersion, SurfaceSyntax> functionSyntax(Family family, String localName) {
    String model23 = family == Family.MATH ? "Math" : "Text";
    String model24 = family == Family.MATH ? "Math_V2" : "Text_V2";
    return Map.of(
        ILI_23, new FunctionSyntax(model23 + "." + localName),
        ILI_24, new FunctionSyntax(model24 + "." + localName));
  }

  private static Parameter value(String name, String declaredType, ScalarKind kind) {
    return new Parameter(name, declaredType, Type.scalar(kind), VALUE);
  }

  private static Parameter path(String name, String declaredType, ScalarKind endpointKind) {
    return new Parameter(name, declaredType, Type.collection(endpointKind), ATTRIBUTE_PATH);
  }

  private static String declaredText(ScalarKind kind) {
    return kind == MTEXT ? "MTEXT" : "TEXT";
  }

  private static String declaredType(ScalarKind kind) {
    return switch (kind) {
      case BOOLEAN -> "BOOLEAN";
      case NUMERIC -> "NUMERIC";
      case TEXT -> "TEXT";
      case MTEXT -> "MTEXT";
      default -> kind.name();
    };
  }

  private static Map<String, StandardFunction> semanticIndex(List<StandardFunction> functions) {
    Map<String, StandardFunction> result = new LinkedHashMap<>();
    for (StandardFunction function : functions) {
      StandardFunction previous = result.putIfAbsent(function.semanticId(), function);
      if (previous != null) {
        throw new IllegalStateException("Duplicate standard function semanticId: " + function.semanticId());
      }
    }
    return Map.copyOf(result);
  }

  private static void requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank.");
    }
  }
}
