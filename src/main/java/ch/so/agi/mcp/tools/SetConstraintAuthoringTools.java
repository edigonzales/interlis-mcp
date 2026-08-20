package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.Viewable;
import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.ConstraintAuthoringWorkflow;
import ch.so.agi.mcp.constraint.ConstraintExpression;
import ch.so.agi.mcp.constraint.SemanticConstraint;
import ch.so.agi.mcp.service.IliCompilerService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** High-level typed authoring for proof-capable SET objectCount(ALL) constraints. */
@Component
public class SetConstraintAuthoringTools {

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
  private static final Pattern FQN = Pattern.compile(
      "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
  private static final Pattern ENUM_VALUE = Pattern.compile(
      "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*");
  private static final Pattern INTERLIS_VERSION = Pattern.compile(
      "(?m)^\\s*INTERLIS\\s+(2\\.3|2\\.4)\\s*;");
  private static final Set<String> OPERATORS = Set.of("==", "!=", "<", "<=", ">", ">=");

  /** Optional direct scalar WHERE predicate for the SET selection. */
  public static class WhereSpec {
    public String attribute;
    public String operator;
    public String valueKind;
    public Object value;
  }

  private final ConstraintCaseGenerationTools caseGenerationTools;
  private final ConstraintAuthoringWorkflow authoringWorkflow;

  public SetConstraintAuthoringTools(
      ConstraintCaseGenerationTools caseGenerationTools,
      ConstraintAuthoringWorkflow authoringWorkflow) {
    this.caseGenerationTools = caseGenerationTools;
    this.authoringWorkflow = authoringWorkflow;
  }

  @McpTool(
      name = "authorIliSetConstraint",
      description = "Erzeugt einen proof-faehigen INTERLIS SET CONSTRAINT fuer INTERLIS.objectCount(ALL) aus operator und threshold. Optional perBasket=true erzeugt SET CONSTRAINT (BASKET); optional where beschreibt einen typisierten direkten Attributvergleich mit attribute/operator/valueKind/value und steuert die Objektmenge vor ALL. Das Tool fuegt source-preserving ein, kompiliert Before/After je genau einmal, prueft Context/(BASKET)/WHERE/objectCount-Operator/Threshold ueber die constraint-level SET-IR und beweist Witness/Counterexample sowie einen global-vs-Basket-Scope-Fall mit echtem ilivalidator. Geometry-aware SET-Funktionen werden bewusst nicht approximiert."
  )
  public Map<String, Object> authorIliSetConstraint(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext ohne den zu erzeugenden SET Constraint", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter SET-Constraint-Kontext Model.Topic.Class", required = true) String context,
      @McpToolParam(description = "Technischer Constraint-Name", required = true) String constraintName,
      @McpToolParam(description = "Vergleichsoperator fuer objectCount(ALL): ==, !=, <, <=, > oder >=", required = true) String operator,
      @McpToolParam(description = "Numerischer Vergleichswert fuer INTERLIS.objectCount(ALL)", required = true) BigDecimal threshold,
      @McpToolParam(description = "true fuer SET CONSTRAINT (BASKET), sonst globale SET-Semantik", required = false) @Nullable Boolean perBasket,
      @McpToolParam(description = "Optionaler direkter WHERE-Attributvergleich: attribute, operator, valueKind (NUMERIC/BOOLEAN/ENUM/TEXT/MTEXT), value", required = false) @Nullable WhereSpec where) {
    String normalizedContext;
    String normalizedName;
    String normalizedOperator;
    BigDecimal normalizedThreshold;
    boolean normalizedPerBasket = Boolean.TRUE.equals(perBasket);
    ConstraintExpression.IliVersion version;
    String whereExpression;
    try {
      normalizedContext = requireFqn(context, "context");
      normalizedName = requireIdentifier(constraintName, "constraintName");
      normalizedOperator = requireOperator(operator, "operator");
      normalizedThreshold = requireThreshold(threshold);
      version = iliVersion(modelText);
      whereExpression = renderWhere(where, version);
    } catch (IllegalArgumentException ex) {
      return unavailable("INVALID_AUTHORING_SPEC", ex.getMessage(), null, null, null);
    }

    IliCompilerService.CompilationResult beforeCompilation = authoringWorkflow.compileBefore(
        modelText,
        "ili2c_set_authoring_before_");
    if (!beforeCompilation.valid() || beforeCompilation.transferDescription() == null) {
      Map<String, Object> result = unavailable(
          "BEFORE_MODEL_NOT_COMPILABLE",
          "The supplied model must compile before a source-preserving SET constraint can be inserted.",
          null,
          null,
          null);
      result.put("compilerMessages", beforeCompilation.messages());
      return result;
    }

    Element beforeContext = beforeCompilation.transferDescription().getElement(normalizedContext);
    if (!(beforeContext instanceof Viewable<?>)) {
      return unavailable(
          "SET_CONTEXT_NOT_FOUND",
          "SET context is not a compiled CLASS/ASSOCIATION/VIEW: " + normalizedContext,
          null,
          null,
          null);
    }

    String conditionExpression = "INTERLIS.objectCount(ALL) "
        + normalizedOperator + " " + normalizedThreshold.toPlainString();
    String constraintBlock = renderConstraintBlock(
        normalizedContext,
        normalizedName,
        normalizedPerBasket,
        whereExpression,
        conditionExpression);

    ConstraintAuthoringWorkflow.PreparedConstraint prepared;
    try {
      prepared = authoringWorkflow.insertAndResolve(
          modelText,
          beforeCompilation,
          normalizedContext,
          constraintBlock,
          normalizedContext + "." + normalizedName,
          "ili2c_set_authoring_after_");
    } catch (IllegalArgumentException ex) {
      return unavailable(
          "CONSTRAINT_INSERTION_FAILED",
          ex.getMessage(),
          conditionExpression,
          constraintBlock,
          null);
    }

    var insertion = prepared.insertion();
    var afterResolution = prepared.resolution();
    if (!afterResolution.available()) {
      Map<String, Object> result = unavailable(
          afterResolution.compilation().valid()
              ? nonBlank(afterResolution.reasonCode(), "GENERATED_SET_NOT_RESOLVED")
              : "GENERATED_SET_NOT_COMPILABLE",
          afterResolution.compilation().valid()
              ? nonBlank(afterResolution.reason(), "The generated SET constraint could not be resolved.")
              : "The typed SET proposal could not be compiled in the supplied model.",
          conditionExpression,
          constraintBlock,
          insertion.updatedModelText());
      result.put("compilerMessages", afterResolution.compilation().messages());
      result.put("sourceEdit", insertion.sourceEdit());
      return result;
    }

    CompiledConstraintContext compiled = afterResolution.context();
    if (!(compiled.semantics() instanceof SemanticConstraint.Set set)) {
      return unavailable(
          "CONSTRAINT_KIND_ROUND_TRIP_MISMATCH",
          "Compiled constraint is not a SET constraint: " + compiled.semantics().kind(),
          conditionExpression,
          constraintBlock,
          insertion.updatedModelText());
    }

    String mismatch = roundTripMismatch(
        set,
        normalizedContext,
        normalizedPerBasket,
        normalizedOperator,
        normalizedThreshold,
        whereExpression);
    if (mismatch != null) {
      return unavailable(
          "SET_SEMANTIC_ROUND_TRIP_MISMATCH",
          mismatch,
          conditionExpression,
          constraintBlock,
          insertion.updatedModelText());
    }

    Map<String, Object> proof = caseGenerationTools.generateCompiledConstraintCases(compiled);
    boolean verified = Boolean.TRUE.equals(proof.get("generationVerified"));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("generated", true);
    result.put("proofVerified", verified);
    result.put("constraintName", normalizedName);
    result.put("context", normalizedContext);
    result.put("iliVersion", version.text());
    result.put("operator", normalizedOperator);
    result.put("threshold", normalizedThreshold.toPlainString());
    result.put("perBasket", normalizedPerBasket);
    if (whereExpression != null) {
      result.put("whereExpression", whereExpression);
    }
    result.put("constraintExpression", conditionExpression);
    result.put("constraintBlock", constraintBlock);
    result.put("updatedModelText", insertion.updatedModelText());
    result.put("sourceEdit", insertion.sourceEdit());
    result.put("semanticConstraint", semanticSummary(set));
    result.put("proof", proof);
    copyIfPresent(proof, result, "coverageGoalCount");
    copyIfPresent(proof, result, "coverageSolvedCount");
    copyIfPresent(proof, result, "coverageComplete");
    copyIfPresent(proof, result, "coverageUnsolved");
    if (!verified) {
      result.put("reasonCode", proof.getOrDefault("reasonCode", "SET_PROOF_NOT_VERIFIED"));
      result.put("reason", proof.getOrDefault(
          "reason",
          "The SET constraint compiled and round-tripped to typed IR, but its generated proof was not fully verified."));
    }
    result.put("compileContract", "2_REAL_COMPILES_BEFORE_AND_AFTER; proof reuses compiled After context.");
    result.put("limitations", limitations());
    return result;
  }

  private @Nullable String roundTripMismatch(
      SemanticConstraint.Set set,
      String context,
      boolean perBasket,
      String operator,
      BigDecimal threshold,
      @Nullable String whereExpression) {
    if (!context.equals(set.contextFqn())) {
      return "Compiled SET context differs from the request: " + set.contextFqn();
    }
    if (perBasket != set.perBasket()) {
      return "Compiled SET (BASKET) scope differs from the request.";
    }
    if ((whereExpression != null) != (set.preCondition() != null)) {
      return "Compiled SET WHERE presence differs from the request.";
    }
    if (whereExpression != null) {
      String compiledWhere = set.preCondition().toInterlis(set.version());
      if (!canonicalExpression(whereExpression).equals(canonicalExpression(compiledWhere))) {
        return "Compiled SET WHERE differs from the request: " + compiledWhere;
      }
    }
    if (!(set.condition() instanceof SemanticConstraint.ObjectCountSetCondition objectCount)) {
      return "Compiled SET condition is not typed objectCount(ALL): "
          + set.condition().getClass().getSimpleName();
    }
    if (!(objectCount.objects() instanceof SemanticConstraint.AllObjects all) || !all.plain()) {
      return "Compiled SET object set is not plain ALL.";
    }
    if (!context.equals(all.contextFqn())) {
      return "Compiled ALL context differs from the request: " + all.contextFqn();
    }
    if (!operator.equals(symbol(objectCount.operator()))) {
      return "Compiled objectCount operator differs from the request: " + symbol(objectCount.operator());
    }
    if (threshold.compareTo(objectCount.threshold()) != 0) {
      return "Compiled objectCount threshold differs from the request: " + objectCount.threshold();
    }
    return null;
  }

  private Map<String, Object> semanticSummary(SemanticConstraint.Set set) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("kind", set.kind().name());
    result.put("constraintScopedName", set.constraintScopedName());
    result.put("contextFqn", set.contextFqn());
    result.put("perBasket", set.perBasket());
    result.put("wherePresent", set.preCondition() != null);
    if (set.preCondition() != null) {
      result.put("where", set.preCondition().toInterlis(set.version()));
    }
    result.put("conditionKind", set.condition().getClass().getSimpleName());
    if (set.condition() instanceof SemanticConstraint.ObjectCountSetCondition objectCount) {
      result.put("operator", symbol(objectCount.operator()));
      result.put("threshold", objectCount.threshold().stripTrailingZeros().toPlainString());
      if (objectCount.objects() instanceof SemanticConstraint.AllObjects all) {
        result.put("objectSet", "ALL");
        result.put("allContextFqn", all.contextFqn());
      }
    }
    return Map.copyOf(result);
  }

  private String renderConstraintBlock(
      String context,
      String constraintName,
      boolean perBasket,
      @Nullable String whereExpression,
      String conditionExpression) {
    StringBuilder result = new StringBuilder();
    result.append("CONSTRAINTS OF ").append(context).append(" =\n")
        .append("  !!@ name = \"").append(constraintName).append("\"\n")
        .append("  SET CONSTRAINT");
    if (perBasket) {
      result.append(" (BASKET)");
    }
    if (whereExpression != null) {
      result.append(" WHERE ").append(whereExpression).append(':');
    }
    result.append("\n    ").append(conditionExpression).append(";\n")
        .append("END;");
    return result.toString();
  }

  private @Nullable String renderWhere(
      @Nullable WhereSpec where,
      ConstraintExpression.IliVersion version) {
    if (where == null) {
      return null;
    }
    String attribute = requireIdentifier(where.attribute, "where.attribute");
    String operator = requireOperator(where.operator, "where.operator");
    String kind = requireText(where.valueKind, "where.valueKind").toUpperCase(Locale.ROOT);
    String literal = switch (kind) {
      case "NUMERIC" -> numericLiteral(where.value).toInterlis(version);
      case "BOOLEAN" -> booleanLiteral(where.value).toInterlis(version);
      case "ENUM" -> enumLiteral(where.value).toInterlis(version);
      case "TEXT" -> textLiteral(where.value, ConstraintExpression.ScalarKind.TEXT).toInterlis(version);
      case "MTEXT" -> textLiteral(where.value, ConstraintExpression.ScalarKind.MTEXT).toInterlis(version);
      default -> throw new IllegalArgumentException(
          "where.valueKind must be NUMERIC, BOOLEAN, ENUM, TEXT or MTEXT.");
    };
    return attribute + " " + operator + " " + literal;
  }

  private ConstraintExpression.NumericLiteral numericLiteral(@Nullable Object value) {
    if (value == null) {
      throw new IllegalArgumentException("where.value is required for NUMERIC.");
    }
    try {
      return new ConstraintExpression.NumericLiteral(new BigDecimal(String.valueOf(value)));
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("where.value is not numeric: " + value);
    }
  }

  private ConstraintExpression.BooleanLiteral booleanLiteral(@Nullable Object value) {
    if (value instanceof Boolean bool) {
      return new ConstraintExpression.BooleanLiteral(bool);
    }
    if (value instanceof String text
        && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
      return new ConstraintExpression.BooleanLiteral(Boolean.parseBoolean(text));
    }
    throw new IllegalArgumentException("where.value must be true or false for BOOLEAN.");
  }

  private ConstraintExpression.EnumLiteral enumLiteral(@Nullable Object value) {
    String text = requireText(value == null ? null : String.valueOf(value), "where.value");
    if (text.startsWith("#")) {
      text = text.substring(1);
    }
    if (!ENUM_VALUE.matcher(text).matches()) {
      throw new IllegalArgumentException("where.value must be an INTERLIS enumeration value.");
    }
    return new ConstraintExpression.EnumLiteral(text);
  }

  private ConstraintExpression.TextLiteral textLiteral(
      @Nullable Object value,
      ConstraintExpression.ScalarKind kind) {
    if (value == null) {
      throw new IllegalArgumentException("where.value is required for " + kind + ".");
    }
    return new ConstraintExpression.TextLiteral(String.valueOf(value), kind);
  }

  private ConstraintExpression.IliVersion iliVersion(String modelText) {
    Matcher matcher = INTERLIS_VERSION.matcher(modelText == null ? "" : modelText);
    if (!matcher.find()) {
      throw new IllegalArgumentException("modelText must declare INTERLIS 2.3 or 2.4.");
    }
    return "2.4".equals(matcher.group(1))
        ? ConstraintExpression.IliVersion.ILI_24
        : ConstraintExpression.IliVersion.ILI_23;
  }

  private String requireFqn(@Nullable String value, String label) {
    String normalized = requireText(value, label);
    if (!FQN.matcher(normalized).matches()) {
      throw new IllegalArgumentException(label + " must be a qualified INTERLIS name.");
    }
    return normalized;
  }

  private String requireIdentifier(@Nullable String value, String label) {
    String normalized = requireText(value, label);
    if (!IDENTIFIER.matcher(normalized).matches()) {
      throw new IllegalArgumentException(label + " must be a simple INTERLIS identifier.");
    }
    return normalized;
  }

  private String requireOperator(@Nullable String value, String label) {
    String normalized = requireText(value, label);
    if (!OPERATORS.contains(normalized)) {
      throw new IllegalArgumentException(label + " must be one of ==, !=, <, <=, > or >=.");
    }
    return normalized;
  }

  private BigDecimal requireThreshold(@Nullable BigDecimal threshold) {
    if (threshold == null) {
      throw new IllegalArgumentException("threshold is required.");
    }
    return threshold.stripTrailingZeros();
  }

  private String requireText(@Nullable String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " is required.");
    }
    return value.trim();
  }

  private String canonicalExpression(String value) {
    return value.replaceAll("\\s+", "").replace("(", "").replace(")", "");
  }

  private String symbol(ConstraintExpression.ComparisonOperator operator) {
    return switch (operator) {
      case EQ -> "==";
      case NE -> "!=";
      case LT -> "<";
      case LE -> "<=";
      case GT -> ">";
      case GE -> ">=";
    };
  }

  private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
    if (source.containsKey(key)) {
      target.put(key, source.get(key));
    }
  }

  private Map<String, Object> unavailable(
      String reasonCode,
      String reason,
      @Nullable String expression,
      @Nullable String constraintBlock,
      @Nullable String candidateModelText) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("generated", false);
    result.put("proofVerified", false);
    result.put("reasonCode", nonBlank(reasonCode, "SET_AUTHORING_UNAVAILABLE"));
    result.put("reason", nonBlank(reason, "SET authoring is unavailable."));
    if (expression != null) {
      result.put("constraintExpression", expression);
    }
    if (constraintBlock != null) {
      result.put("constraintBlock", constraintBlock);
    }
    if (candidateModelText != null) {
      result.put("candidateModelText", candidateModelText);
    }
    result.put("limitations", limitations());
    return result;
  }

  private String nonBlank(@Nullable String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private List<String> limitations() {
    return List.of(
        "Typed SET authoring deliberately targets INTERLIS.objectCount(ALL) comparisons; arbitrary raw SET expressions are not accepted by this high-level tool.",
        "Optional WHERE authoring currently uses one direct scalar attribute comparison so the generated included/excluded object proof remains deterministic.",
        "Plain ALL is proof-capable. ili2c base/RESTRICTION metadata and geometry-aware SET functions are preserved/reported by semantic analysis but are not automatically authored or proven in this slice.",
        "proofVerified=true means the source-preserving Before/After roundtrip and every generated SET fixture were confirmed by the real ilivalidator.");
  }
}
