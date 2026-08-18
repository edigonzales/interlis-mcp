package ch.so.agi.mcp.tools;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ConstraintCaseGenerationTools {

  private static final Set<String> COMPARISON_OPERATORS = Set.of("==", "!=", "<", "<=", ">", ">=");

  private final ConstraintReviewTools reviewTools;
  private final ConstraintTestTools testTools;

  public ConstraintCaseGenerationTools(ConstraintReviewTools reviewTools, ConstraintTestTools testTools) {
    this.reviewTools = reviewTools;
    this.testTools = testTools;
  }

  @McpTool(
      name = "generateIliConstraintCases",
      description = "Erzeugt fuer kleine, sicher unterstuetzte INTERLIS-Mandatory-Constraints automatisch einen Witness und einen Counterexample und verifiziert beide mit testIliConstraint. Unterstuetzt zunaechst direkte skalare Attributvergleiche mit Literalen sowie DEFINED/NOT DEFINED auf optionalen direkten Attributen. Fuer komplexe Ausdruecke, Pfade, Funktionen oder Aggregate wird nicht geraten, sondern automaticCasesAvailable=false geliefert."
  )
  public Map<String, Object> generateIliConstraintCases(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Constraint-Name oder vollqualifizierter Constraint-Name", required = true) String constraint,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition", required = false) @Nullable String modelRepositories) {
    Map<String, Object> review = reviewTools.reviewIliConstraint(modelText, constraint, modelRepositories);
    if (!Boolean.TRUE.equals(review.get("valid"))) {
      return unavailable(
          "MODEL_OR_CONSTRAINT_REVIEW_UNAVAILABLE",
          "The constraint must compile and be reviewable before automatic cases can be generated.",
          review);
    }

    Map<String, Object> ast = map(review.get("ast"));
    if (!"MANDATORY_CONSTRAINT".equals(ast.get("kind"))) {
      return unavailable(
          "UNSUPPORTED_CONSTRAINT_KIND",
          "Automatic cases currently support Mandatory Constraints only.",
          review);
    }

    Map<String, Object> context = map(review.get("context"));
    String classFqn = String.valueOf(context.getOrDefault("scopedName", ""));
    if (classFqn.isBlank()) {
      return unavailable("UNSUPPORTED_CONTEXT", "Constraint context is not an identifiable class context.", review);
    }

    Map<String, Object> condition = unwrapGroup(map(ast.get("condition")));
    GeneratedCases generated = generate(condition, classFqn);
    if (generated == null) {
      return unavailable(
          "UNSUPPORTED_EXPRESSION",
          "Automatic cases currently require one direct scalar attribute comparison with a literal, DEFINED(attribute), or NOT(DEFINED(attribute)).",
          review);
    }
    if (generated.cases().isEmpty()) {
      return unavailable(generated.reasonCode(), generated.reason(), review);
    }

    Map<String, Object> verification = testTools.testIliConstraint(
        modelText,
        constraint,
        generated.cases(),
        modelRepositories);
    boolean verified = Boolean.TRUE.equals(verification.get("allPassed"));

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("automaticCasesAvailable", verified);
    response.put("automaticCasesGenerated", true);
    response.put("generationVerified", verified);
    response.put("pattern", generated.pattern());
    response.put("constraint", review.get("constraint"));
    response.put("context", review.get("context"));
    response.put("generatedCases", generated.summaries());
    response.put("verification", verification);
    if (!verified) {
      response.put("reasonCode", "GENERATED_CASES_NOT_VERIFIED");
      response.put("reason", "Candidate cases were generated but the real validator did not confirm both expected outcomes.");
    }
    response.put("limitations", limitations());
    return response;
  }

  private GeneratedCases generate(Map<String, Object> condition, String classFqn) {
    String kind = String.valueOf(condition.getOrDefault("kind", ""));
    if (COMPARISON_OPERATORS.contains(kind)) {
      return generateComparison(condition, classFqn, kind);
    }
    if ("DEFINED".equals(kind)) {
      return generateDefined(condition, classFqn, false);
    }
    if ("NOT".equals(kind)) {
      Map<String, Object> operand = unwrapGroup(map(condition.get("operand")));
      if ("DEFINED".equals(operand.get("kind"))) {
        return generateDefined(operand, classFqn, true);
      }
    }
    return null;
  }

  private GeneratedCases generateComparison(
      Map<String, Object> condition,
      String classFqn,
      String operator) {
    Map<String, Object> left = unwrapGroup(map(condition.get("left")));
    Map<String, Object> right = unwrapGroup(map(condition.get("right")));

    AttributeOperand attribute;
    LiteralOperand literal;
    String normalizedOperator = operator;
    if (isDirectAttribute(left) && isLiteral(right)) {
      attribute = attributeOperand(left);
      literal = literalOperand(right);
    } else if (isLiteral(left) && isDirectAttribute(right)) {
      attribute = attributeOperand(right);
      literal = literalOperand(left);
      normalizedOperator = reverse(operator);
    } else {
      return null;
    }
    if (attribute == null || literal == null) {
      return null;
    }

    ValuePair pair = comparisonValues(attribute.type(), literal, normalizedOperator);
    if (pair == null) {
      return new GeneratedCases(
          "SCALAR_COMPARISON",
          List.of(),
          List.of(),
          "NO_IN_DOMAIN_WITNESS_COUNTEREXAMPLE_PAIR",
          "The attribute domain does not provide both an in-domain witness and an in-domain counterexample for this comparison.");
    }

    ConstraintTestTools.TestCase witness = testCase(
        "automatic witness",
        true,
        classFqn,
        "auto_witness",
        attribute.name(),
        pair.witness(),
        true);
    ConstraintTestTools.TestCase counterexample = testCase(
        "automatic counterexample",
        false,
        classFqn,
        "auto_counterexample",
        attribute.name(),
        pair.counterexample(),
        true);

    List<Map<String, Object>> summaries = List.of(
        summary("WITNESS", witness, attribute.name(), pair.witness()),
        summary("COUNTEREXAMPLE", counterexample, attribute.name(), pair.counterexample()));
    return new GeneratedCases(
        "SCALAR_COMPARISON " + normalizedOperator,
        List.of(witness, counterexample),
        summaries,
        "",
        "");
  }

  private GeneratedCases generateDefined(
      Map<String, Object> defined,
      String classFqn,
      boolean negated) {
    Map<String, Object> argument = unwrapGroup(map(defined.get("argument")));
    if (!isDirectAttribute(argument)) {
      return null;
    }
    AttributeOperand attribute = attributeOperand(argument);
    if (attribute == null) {
      return null;
    }
    if (attribute.mandatory()) {
      return new GeneratedCases(
          negated ? "NOT_DEFINED" : "DEFINED",
          List.of(),
          List.of(),
          "MANDATORY_ATTRIBUTE_CANNOT_BE_OMITTED",
          "DEFINED/NOT DEFINED automatic cases require an optional attribute so the undefined fixture remains valid.");
    }

    Object sample = sampleValue(attribute.type());
    if (sample == null) {
      return new GeneratedCases(
          negated ? "NOT_DEFINED" : "DEFINED",
          List.of(),
          List.of(),
          "UNSUPPORTED_ATTRIBUTE_TYPE",
          "No safe scalar sample value is available for this attribute type.");
    }

    ConstraintTestTools.TestCase definedCase = testCase(
        negated ? "automatic counterexample" : "automatic witness",
        !negated,
        classFqn,
        negated ? "auto_counterexample" : "auto_witness",
        attribute.name(),
        sample,
        true);
    ConstraintTestTools.TestCase undefinedCase = testCase(
        negated ? "automatic witness" : "automatic counterexample",
        negated,
        classFqn,
        negated ? "auto_witness" : "auto_counterexample",
        attribute.name(),
        null,
        false);

    ConstraintTestTools.TestCase witness = negated ? undefinedCase : definedCase;
    ConstraintTestTools.TestCase counterexample = negated ? definedCase : undefinedCase;
    List<Map<String, Object>> summaries = List.of(
        summary("WITNESS", witness, attribute.name(), negated ? null : sample),
        summary("COUNTEREXAMPLE", counterexample, attribute.name(), negated ? sample : null));
    return new GeneratedCases(
        negated ? "NOT_DEFINED" : "DEFINED",
        List.of(witness, counterexample),
        summaries,
        "",
        "");
  }

  private ConstraintTestTools.TestCase testCase(
      String name,
      boolean expected,
      String classFqn,
      String oid,
      String attribute,
      @Nullable Object value,
      boolean includeValue) {
    ConstraintTestTools.TestObject object = new ConstraintTestTools.TestObject();
    object.classFqn = classFqn;
    object.oid = oid;
    object.values = includeValue && value != null ? Map.of(attribute, value) : Map.of();

    ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
    testCase.name = name;
    testCase.expectedConstraintValid = expected;
    testCase.objects = List.of(object);
    testCase.links = List.of();
    return testCase;
  }

  private Map<String, Object> summary(
      String purpose,
      ConstraintTestTools.TestCase testCase,
      String attribute,
      @Nullable Object value) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("purpose", purpose);
    result.put("name", testCase.name);
    result.put("expectedConstraintValid", testCase.expectedConstraintValid);
    result.put("attribute", attribute);
    if (value != null) {
      result.put("value", value);
    } else {
      result.put("attributeOmitted", true);
    }
    return result;
  }

  private ValuePair comparisonValues(
      Map<String, Object> type,
      LiteralOperand literal,
      String operator) {
    String typeKind = String.valueOf(type.getOrDefault("kind", ""));
    if ("NUMERIC".equals(typeKind) && "NUMERIC_LITERAL".equals(literal.kind())) {
      return numericComparisonValues(type, literal.value(), operator);
    }
    if (("TEXT".equals(typeKind) || "MTEXT".equals(typeKind))
        && "TEXT_LITERAL".equals(literal.kind())
        && ("==".equals(operator) || "!=".equals(operator))) {
      String expected = literal.value();
      String alternative = alternativeText(type, expected);
      if (alternative == null) {
        return null;
      }
      return "==".equals(operator)
          ? new ValuePair(expected, alternative)
          : new ValuePair(alternative, expected);
    }
    return null;
  }

  private ValuePair numericComparisonValues(
      Map<String, Object> type,
      String literalText,
      String operator) {
    BigDecimal literal;
    try {
      literal = new BigDecimal(literalText);
    } catch (NumberFormatException ex) {
      return null;
    }
    NumericDomain domain = numericDomain(type, literal);
    if (!domain.contains(literal)) {
      return null;
    }

    BigDecimal below = literal.subtract(domain.step());
    BigDecimal above = literal.add(domain.step());
    boolean belowValid = domain.contains(below);
    boolean aboveValid = domain.contains(above);

    return switch (operator) {
      case "==" -> aboveValid
          ? new ValuePair(decimal(literal), decimal(above))
          : belowValid ? new ValuePair(decimal(literal), decimal(below)) : null;
      case "!=" -> aboveValid
          ? new ValuePair(decimal(above), decimal(literal))
          : belowValid ? new ValuePair(decimal(below), decimal(literal)) : null;
      case "<" -> belowValid ? new ValuePair(decimal(below), decimal(literal)) : null;
      case "<=" -> aboveValid ? new ValuePair(decimal(literal), decimal(above)) : null;
      case ">" -> aboveValid ? new ValuePair(decimal(above), decimal(literal)) : null;
      case ">=" -> belowValid ? new ValuePair(decimal(literal), decimal(below)) : null;
      default -> null;
    };
  }

  private NumericDomain numericDomain(Map<String, Object> type, BigDecimal literal) {
    String typeText = String.valueOf(type.getOrDefault("typeText", ""));
    BigDecimal minimum = null;
    BigDecimal maximum = null;
    int scale = Math.max(0, literal.scale());
    int separator = typeText.indexOf("..");
    if (separator > 0) {
      try {
        minimum = new BigDecimal(typeText.substring(0, separator).trim());
        maximum = new BigDecimal(typeText.substring(separator + 2).trim());
        scale = Math.max(scale, Math.max(0, minimum.scale()));
        scale = Math.max(scale, Math.max(0, maximum.scale()));
      } catch (NumberFormatException ignore) {
        minimum = null;
        maximum = null;
      }
    }
    BigDecimal step = BigDecimal.ONE.movePointLeft(scale);
    return new NumericDomain(minimum, maximum, step);
  }

  private String decimal(BigDecimal value) {
    return value.toPlainString();
  }

  private @Nullable String alternativeText(Map<String, Object> type, String literal) {
    int maxLength = textMaxLength(type);
    String candidate = "x".equals(literal) ? "y" : "x";
    if (maxLength == 0) {
      return null;
    }
    return candidate;
  }

  private int textMaxLength(Map<String, Object> type) {
    String typeText = String.valueOf(type.getOrDefault("typeText", ""));
    int star = typeText.indexOf('*');
    if (star < 0) {
      return -1;
    }
    try {
      return Integer.parseInt(typeText.substring(star + 1));
    } catch (NumberFormatException ex) {
      return -1;
    }
  }

  private @Nullable Object sampleValue(Map<String, Object> type) {
    String kind = String.valueOf(type.getOrDefault("kind", ""));
    if ("NUMERIC".equals(kind)) {
      NumericDomain domain = numericDomain(type, BigDecimal.ZERO);
      if (domain.contains(BigDecimal.ZERO)) {
        return "0";
      }
      if (domain.minimum() != null) {
        return decimal(domain.minimum());
      }
      if (domain.maximum() != null) {
        return decimal(domain.maximum());
      }
      return "0";
    }
    if ("TEXT".equals(kind) || "MTEXT".equals(kind)) {
      return textMaxLength(type) == 0 ? null : "x";
    }
    if ("BOOLEAN".equals(kind)) {
      return true;
    }
    if ("ENUM".equals(kind) && type.get("values") instanceof List<?> values && !values.isEmpty()) {
      return String.valueOf(values.getFirst());
    }
    return null;
  }

  private boolean isDirectAttribute(Map<String, Object> node) {
    if (!"OBJECT_PATH".equals(node.get("kind")) || Boolean.TRUE.equals(node.get("collection"))) {
      return false;
    }
    List<Map<String, Object>> steps = mapList(node.get("steps"));
    return steps.size() == 1 && "ATTRIBUTE".equals(steps.getFirst().get("kind"));
  }

  private @Nullable AttributeOperand attributeOperand(Map<String, Object> node) {
    if (!isDirectAttribute(node)) {
      return null;
    }
    Map<String, Object> step = mapList(node.get("steps")).getFirst();
    String name = String.valueOf(step.getOrDefault("name", ""));
    if (name.isBlank()) {
      return null;
    }
    Map<String, Object> type = !map(node.get("type")).isEmpty()
        ? map(node.get("type"))
        : map(step.get("type"));
    return new AttributeOperand(name, type, Boolean.TRUE.equals(step.get("mandatory")));
  }

  private boolean isLiteral(Map<String, Object> node) {
    String kind = String.valueOf(node.getOrDefault("kind", ""));
    return "NUMERIC_LITERAL".equals(kind) || "TEXT_LITERAL".equals(kind);
  }

  private @Nullable LiteralOperand literalOperand(Map<String, Object> node) {
    if (!isLiteral(node) || node.get("value") == null) {
      return null;
    }
    return new LiteralOperand(String.valueOf(node.get("kind")), String.valueOf(node.get("value")));
  }

  private String reverse(String operator) {
    return switch (operator) {
      case "<" -> ">";
      case "<=" -> ">=";
      case ">" -> "<";
      case ">=" -> "<=";
      default -> operator;
    };
  }

  private Map<String, Object> unwrapGroup(Map<String, Object> node) {
    Map<String, Object> current = node;
    while ("GROUP".equals(current.get("kind"))) {
      current = map(current.get("expression"));
    }
    return current;
  }

  private Map<String, Object> unavailable(
      String reasonCode,
      String reason,
      Map<String, Object> review) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("automaticCasesAvailable", false);
    response.put("automaticCasesGenerated", false);
    response.put("generationVerified", false);
    response.put("reasonCode", reasonCode);
    response.put("reason", reason);
    if (review.get("constraint") != null) {
      response.put("constraint", review.get("constraint"));
    }
    if (review.get("context") != null) {
      response.put("context", review.get("context"));
    }
    response.put("limitations", limitations());
    return response;
  }

  private List<String> limitations() {
    return List.of(
        "Automatic generation intentionally supports only small scalar Mandatory Constraint patterns.",
        "AND/OR/IMPLIES, multi-step paths, associations, functions, aggregates and structured or geometry values are not synthesized in this step.",
        "automaticCasesAvailable=true is returned only after the generated witness and counterexample both pass testIliConstraint with the expected outcomes.");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(@Nullable Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> mapList(@Nullable Object value) {
    return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }

  private record AttributeOperand(String name, Map<String, Object> type, boolean mandatory) {
  }

  private record LiteralOperand(String kind, String value) {
  }

  private record ValuePair(Object witness, Object counterexample) {
  }

  private record NumericDomain(
      @Nullable BigDecimal minimum,
      @Nullable BigDecimal maximum,
      BigDecimal step) {
    private boolean contains(BigDecimal value) {
      return (minimum == null || value.compareTo(minimum) >= 0)
          && (maximum == null || value.compareTo(maximum) <= 0);
    }
  }

  private record GeneratedCases(
      String pattern,
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries,
      String reasonCode,
      String reason) {
  }
}
