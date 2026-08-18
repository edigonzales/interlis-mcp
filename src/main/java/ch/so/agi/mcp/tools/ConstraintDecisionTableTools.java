package ch.so.agi.mcp.tools;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ConstraintDecisionTableTools {

  private static final Set<String> OPERATORS = Set.of("==", "!=", "<", "<=", ">", ">=");
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

  private final ConstraintReviewTools reviewTools;
  private final ConstraintTestTools testTools;

  public ConstraintDecisionTableTools(ConstraintReviewTools reviewTools, ConstraintTestTools testTools) {
    this.reviewTools = reviewTools;
    this.testTools = testTools;
  }

  public static class DecisionRow {
    public String name;
    public List<DecisionCondition> conditions;
  }

  public static class DecisionCondition {
    public String attribute;
    public String operator;
    public Object value;
  }

  @McpTool(
      name = "generateIliConstraintFromDecisionTable",
      description = "Erzeugt aus einer strukturierten Entscheidungstabelle einen INTERLIS Mandatory Constraint, leitet automatisch numerische Boundary-Testfaelle ab und beweist den erzeugten Constraint mit testIliConstraint und dem echten ilivalidator. Jede Tabellenzeile beschreibt eine erlaubte Kombination; Bedingungen einer Zeile werden mit AND, mehrere Zeilen mit OR verbunden. Der MVP unterstuetzt direkte numerische Attribute und die Operatoren ==, !=, <, <=, >, >=."
  )
  public Map<String, Object> generateIliConstraintFromDecisionTable(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext ohne den zu erzeugenden Constraint", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Klassenkontext Model.Topic.Class", required = true) String context,
      @McpToolParam(description = "Technischer Name des zu erzeugenden Constraints", required = true) String constraintName,
      @McpToolParam(description = "Erlaubte Entscheidungszeilen. Jede Zeile hat name und conditions; jede Bedingung hat attribute, operator und numerischen value.", required = true) List<DecisionRow> rows,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition", required = false) @Nullable String modelRepositories) {
    String normalizedContext = requireContext(context);
    String normalizedConstraintName = requireIdentifier(constraintName, "constraintName");
    List<NormalizedRow> normalizedRows = normalizeRows(rows);

    String expression = renderExpression(normalizedRows);
    String constraintBlock = renderConstraintBlock(normalizedContext, normalizedConstraintName, expression);
    String proofModelText;
    try {
      proofModelText = insertConstraintBlock(modelText, normalizedContext, constraintBlock);
    } catch (IllegalArgumentException ex) {
      return unavailable(
          "CONSTRAINT_INSERTION_FAILED",
          ex.getMessage(),
          expression,
          constraintBlock,
          normalizedRows,
          null);
    }

    Map<String, Object> review = reviewTools.reviewIliConstraint(
        proofModelText,
        normalizedConstraintName,
        modelRepositories);
    if (!Boolean.TRUE.equals(review.get("valid"))) {
      return unavailable(
          "GENERATED_CONSTRAINT_NOT_COMPILABLE",
          "The generated decision-table constraint could not be compiled and reviewed in the supplied model.",
          expression,
          constraintBlock,
          normalizedRows,
          review);
    }

    Map<String, NumericDomain> domains = attributeDomains(map(review.get("ast")), normalizedRows);
    if (domains.size() != referencedAttributes(normalizedRows).size()) {
      return unavailable(
          "UNSUPPORTED_ATTRIBUTE_TYPE",
          "Decision-table boundary proof currently requires every referenced attribute to be a direct NUMERIC attribute.",
          expression,
          constraintBlock,
          normalizedRows,
          review);
    }

    BoundaryGeneration boundaries = generateBoundaryCases(normalizedContext, normalizedRows, domains);
    if (!boundaries.available()) {
      return unavailable(
          boundaries.reasonCode(),
          boundaries.reason(),
          expression,
          constraintBlock,
          normalizedRows,
          review);
    }

    Map<String, Object> verification = testTools.testIliConstraint(
        proofModelText,
        normalizedConstraintName,
        boundaries.cases(),
        modelRepositories);
    boolean verified = Boolean.TRUE.equals(verification.get("allPassed"));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("generated", true);
    result.put("proofVerified", verified);
    result.put("constraintName", normalizedConstraintName);
    result.put("context", normalizedContext);
    result.put("decisionTable", decisionTableSummary(normalizedRows));
    result.put("constraintExpression", expression);
    result.put("constraintBlock", constraintBlock);
    result.put("boundaryCases", boundaries.summaries());
    result.put("boundaryCaseCount", boundaries.cases().size());
    result.put("verification", verification);
    if (!verified) {
      result.put("reasonCode", "BOUNDARY_PROOF_FAILED");
      result.put("reason", "The generated boundary cases were created, but the validator did not confirm all expected decision-table outcomes.");
    }
    result.put("limitations", limitations());
    return result;
  }

  private List<NormalizedRow> normalizeRows(@Nullable List<DecisionRow> rows) {
    if (rows == null || rows.isEmpty()) {
      throw new IllegalArgumentException("Decision table requires at least one allowed row.");
    }
    List<NormalizedRow> result = new ArrayList<>();
    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      DecisionRow row = rows.get(rowIndex);
      if (row == null) {
        throw new IllegalArgumentException("Decision row at index " + rowIndex + " must not be null.");
      }
      String name = row.name == null || row.name.isBlank() ? "row " + (rowIndex + 1) : row.name.trim();
      if (row.conditions == null || row.conditions.isEmpty()) {
        throw new IllegalArgumentException("Decision row '" + name + "' requires at least one condition.");
      }
      List<NormalizedCondition> conditions = new ArrayList<>();
      for (int conditionIndex = 0; conditionIndex < row.conditions.size(); conditionIndex++) {
        DecisionCondition condition = row.conditions.get(conditionIndex);
        if (condition == null) {
          throw new IllegalArgumentException("Decision row '" + name + "' contains a null condition.");
        }
        String attribute = requireIdentifier(condition.attribute, "condition attribute");
        String operator = condition.operator == null ? "" : condition.operator.trim();
        if (!OPERATORS.contains(operator)) {
          throw new IllegalArgumentException("Unsupported decision-table operator '" + operator + "'.");
        }
        conditions.add(new NormalizedCondition(
            attribute,
            operator,
            numericValue(condition.value, name, conditionIndex)));
      }
      result.add(new NormalizedRow(name, conditions));
    }
    return result;
  }

  private String renderExpression(List<NormalizedRow> rows) {
    List<String> rowExpressions = new ArrayList<>();
    for (NormalizedRow row : rows) {
      List<String> conditions = row.conditions().stream()
          .map(condition -> condition.attribute() + " " + condition.operator() + " " + decimal(condition.value()))
          .toList();
      String expression = String.join(" AND ", conditions);
      rowExpressions.add(conditions.size() > 1 ? "(" + expression + ")" : expression);
    }
    return rowExpressions.size() == 1
        ? rowExpressions.getFirst()
        : rowExpressions.stream()
            .map(expression -> "(" + expression + ")")
            .reduce((left, right) -> left + " OR " + right)
            .orElseThrow();
  }

  private String renderConstraintBlock(String context, String constraintName, String expression) {
    return "CONSTRAINTS OF " + context + " =\n"
        + "  !!@ name = \"" + constraintName + "\"\n"
        + "  MANDATORY CONSTRAINT\n"
        + "    " + expression + ";\n"
        + "END;";
  }

  private String insertConstraintBlock(String modelText, String context, String constraintBlock) {
    String[] parts = context.split("\\.");
    String modelName = parts[0];
    String topicName = parts[1];

    Matcher modelMatcher = Pattern.compile(
        "(?m)^\\s*(?:(?:CONTRACTED|REFSYSTEM|SYMBOLOGY|TYPE)\\s+)?MODEL\\s+"
            + Pattern.quote(modelName) + "\\b")
        .matcher(modelText);
    if (!modelMatcher.find()) {
      throw new IllegalArgumentException("Model '" + modelName + "' was not found in modelText.");
    }
    int modelStart = modelMatcher.start();

    Matcher modelEndMatcher = Pattern.compile(
        "(?m)^\\s*END\\s+" + Pattern.quote(modelName) + "\\s*\\.")
        .matcher(modelText);
    if (!modelEndMatcher.find(modelStart)) {
      throw new IllegalArgumentException("End of model '" + modelName + "' was not found.");
    }
    int modelEnd = modelEndMatcher.start();

    Matcher topicMatcher = Pattern.compile(
        "(?m)^\\s*TOPIC\\s+" + Pattern.quote(topicName) + "\\b")
        .matcher(modelText);
    if (!topicMatcher.find(modelStart) || topicMatcher.start() >= modelEnd) {
      throw new IllegalArgumentException("Topic '" + topicName + "' was not found in model '" + modelName + "'.");
    }

    Matcher topicEndMatcher = Pattern.compile(
        "(?m)^\\s*END\\s+" + Pattern.quote(topicName) + "\\s*;")
        .matcher(modelText);
    topicEndMatcher.region(topicMatcher.start(), modelEnd);
    int insertAt = -1;
    while (topicEndMatcher.find()) {
      insertAt = topicEndMatcher.start();
    }
    if (insertAt < 0) {
      throw new IllegalArgumentException("End of topic '" + topicName + "' was not found.");
    }

    String indentation = leadingWhitespace(modelText, insertAt);
    String indentedBlock = constraintBlock.lines()
        .map(line -> indentation + "  " + line)
        .reduce((left, right) -> left + "\n" + right)
        .orElse(constraintBlock);
    return modelText.substring(0, insertAt)
        + indentedBlock + "\n\n"
        + modelText.substring(insertAt);
  }

  private String leadingWhitespace(String text, int offset) {
    int lineStart = text.lastIndexOf('\n', Math.max(0, offset - 1));
    lineStart = lineStart < 0 ? 0 : lineStart + 1;
    int pos = lineStart;
    while (pos < text.length() && (text.charAt(pos) == ' ' || text.charAt(pos) == '\t')) {
      pos++;
    }
    return text.substring(lineStart, pos);
  }

  private Map<String, NumericDomain> attributeDomains(
      Map<String, Object> ast,
      List<NormalizedRow> rows) {
    Map<String, Map<String, Object>> types = new LinkedHashMap<>();
    collectDirectAttributeTypes(map(ast.get("condition")), types);

    Map<String, NumericDomain> result = new LinkedHashMap<>();
    Map<String, Integer> literalScales = literalScales(rows);
    for (String attribute : referencedAttributes(rows)) {
      Map<String, Object> type = types.get(attribute);
      if (type != null && "NUMERIC".equals(type.get("kind"))) {
        result.put(attribute, numericDomain(type, literalScales.getOrDefault(attribute, 0)));
      }
    }
    return result;
  }

  private void collectDirectAttributeTypes(
      Map<String, Object> node,
      Map<String, Map<String, Object>> sink) {
    if (node.isEmpty()) {
      return;
    }
    if ("OBJECT_PATH".equals(node.get("kind")) && !Boolean.TRUE.equals(node.get("collection"))) {
      List<Map<String, Object>> steps = mapList(node.get("steps"));
      if (steps.size() == 1 && "ATTRIBUTE".equals(steps.getFirst().get("kind"))) {
        String name = String.valueOf(steps.getFirst().getOrDefault("name", ""));
        Map<String, Object> type = map(node.get("type"));
        if (type.isEmpty()) {
          type = map(steps.getFirst().get("type"));
        }
        if (!name.isBlank()) {
          sink.putIfAbsent(name, type);
        }
      }
    }
    collectDirectAttributeTypes(map(node.get("left")), sink);
    collectDirectAttributeTypes(map(node.get("right")), sink);
    collectDirectAttributeTypes(map(node.get("operand")), sink);
    collectDirectAttributeTypes(map(node.get("argument")), sink);
    collectDirectAttributeTypes(map(node.get("expression")), sink);
    for (Map<String, Object> child : mapList(node.get("children"))) {
      collectDirectAttributeTypes(child, sink);
    }
  }

  private BoundaryGeneration generateBoundaryCases(
      String context,
      List<NormalizedRow> rows,
      Map<String, NumericDomain> domains) {
    List<Candidate> candidates = new ArrayList<>();

    for (NormalizedRow row : rows) {
      Map<String, BigDecimal> baseline = representative(row, domains);
      if (baseline == null) {
        return BoundaryGeneration.unavailable(
            "UNSATISFIABLE_DECISION_ROW",
            "No in-domain numeric assignment satisfies allowed decision row '" + row.name() + "'.");
      }
      candidates.add(new Candidate("ROW_WITNESS", row.name(), baseline));

      for (NormalizedCondition condition : row.conditions()) {
        NumericDomain domain = domains.get(condition.attribute());
        for (BoundaryValue boundary : boundaryValues(condition, domain)) {
          Map<String, BigDecimal> values = new LinkedHashMap<>(baseline);
          values.put(condition.attribute(), boundary.value());
          if (allValuesInDomain(values, domains)) {
            candidates.add(new Candidate(
                boundary.purpose(),
                row.name() + ": " + condition.attribute() + " " + condition.operator()
                    + " " + decimal(condition.value()),
                values));
          }
        }
      }
    }

    Map<String, Candidate> unique = new LinkedHashMap<>();
    for (Candidate candidate : candidates) {
      unique.putIfAbsent(candidateKey(candidate.values()), candidate);
    }

    List<ConstraintTestTools.TestCase> cases = new ArrayList<>();
    List<Map<String, Object>> summaries = new ArrayList<>();
    int index = 1;
    for (Candidate candidate : unique.values()) {
      boolean expectedValid = tableMatches(rows, candidate.values());
      ConstraintTestTools.TestObject object = new ConstraintTestTools.TestObject();
      object.classFqn = context;
      object.oid = "decision_case_" + index;
      Map<String, Object> objectValues = new LinkedHashMap<>();
      candidate.values().forEach((attribute, value) -> objectValues.put(attribute, decimal(value)));
      object.values = objectValues;

      ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
      testCase.name = "boundary " + index + " - " + candidate.purpose();
      testCase.expectedConstraintValid = expectedValid;
      testCase.objects = List.of(object);
      testCase.links = List.of();
      cases.add(testCase);

      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("name", testCase.name);
      summary.put("purpose", candidate.purpose());
      summary.put("source", candidate.source());
      summary.put("values", objectValues);
      summary.put("expectedConstraintValid", expectedValid);
      summaries.add(summary);
      index++;
    }

    if (cases.isEmpty()) {
      return BoundaryGeneration.unavailable(
          "NO_BOUNDARY_CASES",
          "No in-domain boundary cases could be derived from the decision table.");
    }
    return new BoundaryGeneration(true, cases, summaries, "", "");
  }

  private @Nullable Map<String, BigDecimal> representative(
      NormalizedRow row,
      Map<String, NumericDomain> domains) {
    Map<String, BigDecimal> result = new LinkedHashMap<>();
    domains.forEach((attribute, domain) -> result.put(attribute, defaultValue(domain)));

    Map<String, List<NormalizedCondition>> byAttribute = new LinkedHashMap<>();
    for (NormalizedCondition condition : row.conditions()) {
      byAttribute.computeIfAbsent(condition.attribute(), key -> new ArrayList<>()).add(condition);
    }

    for (Map.Entry<String, List<NormalizedCondition>> entry : byAttribute.entrySet()) {
      NumericDomain domain = domains.get(entry.getKey());
      LinkedHashSet<BigDecimal> candidates = new LinkedHashSet<>();
      for (NormalizedCondition condition : entry.getValue()) {
        candidates.add(condition.value());
        candidates.add(condition.value().subtract(domain.step()));
        candidates.add(condition.value().add(domain.step()));
      }
      if (domain.minimum() != null) {
        candidates.add(domain.minimum());
      }
      if (domain.maximum() != null) {
        candidates.add(domain.maximum());
      }
      if (domain.minimum() != null && domain.maximum() != null) {
        candidates.add(domain.minimum().add(domain.maximum())
            .divide(BigDecimal.valueOf(2), domain.step().scale(), RoundingMode.HALF_UP));
      }
      candidates.add(BigDecimal.ZERO.setScale(domain.step().scale()));

      BigDecimal selected = candidates.stream()
          .filter(domain::contains)
          .filter(value -> entry.getValue().stream().allMatch(condition -> matches(condition, value)))
          .findFirst()
          .orElse(null);
      if (selected == null) {
        return null;
      }
      result.put(entry.getKey(), selected);
    }
    return result;
  }

  private BigDecimal defaultValue(NumericDomain domain) {
    BigDecimal zero = BigDecimal.ZERO.setScale(domain.step().scale());
    if (domain.contains(zero)) {
      return zero;
    }
    if (domain.minimum() != null) {
      return domain.minimum();
    }
    if (domain.maximum() != null) {
      return domain.maximum();
    }
    return zero;
  }

  private List<BoundaryValue> boundaryValues(
      NormalizedCondition condition,
      NumericDomain domain) {
    BigDecimal literal = condition.value();
    BigDecimal below = literal.subtract(domain.step());
    BigDecimal above = literal.add(domain.step());
    List<BoundaryValue> result = new ArrayList<>();
    switch (condition.operator()) {
      case "==" -> {
        addIfInDomain(result, domain, "AT_EQUALITY", literal);
        if (domain.contains(below)) {
          result.add(new BoundaryValue("BELOW_EQUALITY", below));
        } else {
          addIfInDomain(result, domain, "ABOVE_EQUALITY", above);
        }
      }
      case "!=" -> {
        addIfInDomain(result, domain, "AT_EXCLUDED_VALUE", literal);
        if (domain.contains(below)) {
          result.add(new BoundaryValue("BELOW_EXCLUDED_VALUE", below));
        } else {
          addIfInDomain(result, domain, "ABOVE_EXCLUDED_VALUE", above);
        }
      }
      case "<" -> {
        addIfInDomain(result, domain, "JUST_BELOW_UPPER_BOUND", below);
        addIfInDomain(result, domain, "AT_EXCLUSIVE_UPPER_BOUND", literal);
      }
      case "<=" -> {
        addIfInDomain(result, domain, "AT_INCLUSIVE_UPPER_BOUND", literal);
        addIfInDomain(result, domain, "JUST_ABOVE_UPPER_BOUND", above);
      }
      case ">" -> {
        addIfInDomain(result, domain, "AT_EXCLUSIVE_LOWER_BOUND", literal);
        addIfInDomain(result, domain, "JUST_ABOVE_LOWER_BOUND", above);
      }
      case ">=" -> {
        addIfInDomain(result, domain, "JUST_BELOW_LOWER_BOUND", below);
        addIfInDomain(result, domain, "AT_INCLUSIVE_LOWER_BOUND", literal);
      }
      default -> {
      }
    }
    return result;
  }

  private void addIfInDomain(
      List<BoundaryValue> sink,
      NumericDomain domain,
      String purpose,
      BigDecimal value) {
    if (domain.contains(value)) {
      sink.add(new BoundaryValue(purpose, value));
    }
  }

  private boolean tableMatches(List<NormalizedRow> rows, Map<String, BigDecimal> values) {
    return rows.stream().anyMatch(row -> row.conditions().stream()
        .allMatch(condition -> values.containsKey(condition.attribute())
            && matches(condition, values.get(condition.attribute()))));
  }

  private boolean matches(NormalizedCondition condition, BigDecimal actual) {
    int cmp = actual.compareTo(condition.value());
    return switch (condition.operator()) {
      case "==" -> cmp == 0;
      case "!=" -> cmp != 0;
      case "<" -> cmp < 0;
      case "<=" -> cmp <= 0;
      case ">" -> cmp > 0;
      case ">=" -> cmp >= 0;
      default -> false;
    };
  }

  private boolean allValuesInDomain(
      Map<String, BigDecimal> values,
      Map<String, NumericDomain> domains) {
    return values.entrySet().stream()
        .allMatch(entry -> domains.containsKey(entry.getKey())
            && domains.get(entry.getKey()).contains(entry.getValue()));
  }

  private NumericDomain numericDomain(Map<String, Object> type, int literalScale) {
    String typeText = String.valueOf(type.getOrDefault("typeText", ""));
    BigDecimal minimum = null;
    BigDecimal maximum = null;
    int scale = Math.max(0, literalScale);
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
    return new NumericDomain(minimum, maximum, BigDecimal.ONE.movePointLeft(scale));
  }

  private Map<String, Integer> literalScales(List<NormalizedRow> rows) {
    Map<String, Integer> result = new LinkedHashMap<>();
    for (NormalizedRow row : rows) {
      for (NormalizedCondition condition : row.conditions()) {
        result.merge(condition.attribute(), Math.max(0, condition.value().scale()), Math::max);
      }
    }
    return result;
  }

  private Set<String> referencedAttributes(List<NormalizedRow> rows) {
    Set<String> result = new LinkedHashSet<>();
    for (NormalizedRow row : rows) {
      for (NormalizedCondition condition : row.conditions()) {
        result.add(condition.attribute());
      }
    }
    return result;
  }

  private String candidateKey(Map<String, BigDecimal> values) {
    return values.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> entry.getKey() + "=" + decimal(entry.getValue()))
        .reduce((left, right) -> left + "|" + right)
        .orElse("");
  }

  private BigDecimal numericValue(@Nullable Object value, String rowName, int conditionIndex) {
    if (value == null) {
      throw new IllegalArgumentException(
          "Decision row '" + rowName + "' condition " + conditionIndex + " requires a numeric value.");
    }
    try {
      return new BigDecimal(String.valueOf(value));
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(
          "Decision row '" + rowName + "' condition " + conditionIndex
              + " value is not numeric: " + value);
    }
  }

  private String requireContext(@Nullable String context) {
    if (context == null || context.isBlank()) {
      throw new IllegalArgumentException("context is required.");
    }
    String normalized = context.trim();
    String[] parts = normalized.split("\\.");
    if (parts.length != 3) {
      throw new IllegalArgumentException("Decision-table MVP requires context in the form Model.Topic.Class.");
    }
    for (String part : parts) {
      requireIdentifier(part, "context part");
    }
    return normalized;
  }

  private String requireIdentifier(@Nullable String value, String label) {
    if (value == null || value.isBlank() || !IDENTIFIER.matcher(value.trim()).matches()) {
      throw new IllegalArgumentException(label + " must be a simple INTERLIS identifier.");
    }
    return value.trim();
  }

  private String decimal(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  private List<Map<String, Object>> decisionTableSummary(List<NormalizedRow> rows) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (NormalizedRow row : rows) {
      List<Map<String, Object>> conditions = new ArrayList<>();
      for (NormalizedCondition condition : row.conditions()) {
        conditions.add(Map.of(
            "attribute", condition.attribute(),
            "operator", condition.operator(),
            "value", decimal(condition.value())));
      }
      result.add(Map.of("name", row.name(), "allowed", true, "conditions", conditions));
    }
    return result;
  }

  private Map<String, Object> unavailable(
      String reasonCode,
      String reason,
      String expression,
      String constraintBlock,
      List<NormalizedRow> rows,
      @Nullable Map<String, Object> review) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("generated", false);
    result.put("proofVerified", false);
    result.put("reasonCode", reasonCode);
    result.put("reason", reason);
    result.put("decisionTable", decisionTableSummary(rows));
    result.put("constraintExpression", expression);
    result.put("constraintBlock", constraintBlock);
    if (review != null) {
      result.put("review", review);
    }
    result.put("limitations", limitations());
    return result;
  }

  private List<String> limitations() {
    return List.of(
        "Decision rows currently describe allowed combinations only; the generated Mandatory Constraint is their OR-union.",
        "Conditions currently support direct numeric attributes and ==, !=, <, <=, >, >= only.",
        "Boundary candidates are kept inside the declared numeric attribute domain and are verified with testIliConstraint.",
        "Complex paths, functions, associations, aggregates, text/enumeration decisions and geometry are outside this first decision-table implementation.");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(@Nullable Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> mapList(@Nullable Object value) {
    return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }

  private record NormalizedRow(String name, List<NormalizedCondition> conditions) {
  }

  private record NormalizedCondition(String attribute, String operator, BigDecimal value) {
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

  private record BoundaryValue(String purpose, BigDecimal value) {
  }

  private record Candidate(String purpose, String source, Map<String, BigDecimal> values) {
  }

  private record BoundaryGeneration(
      boolean available,
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries,
      String reasonCode,
      String reason) {
    private static BoundaryGeneration unavailable(String reasonCode, String reason) {
      return new BoundaryGeneration(false, List.of(), List.of(), reasonCode, reason);
    }
  }
}
