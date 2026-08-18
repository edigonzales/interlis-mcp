package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.constraint.ConstraintCoveragePlanner;
import ch.so.agi.mcp.constraint.ConstraintExpression;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer;
import ch.so.agi.mcp.constraint.StandardFunctionRegistry;
import ch.so.agi.mcp.service.IliCompilerService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ConstraintDecisionTableTools {

  private static final Set<String> OPERATORS = Set.of("==", "!=", "<", "<=", ">", ">=");
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
  private static final Pattern ENUM_VALUE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*");
  private static final Pattern INTERLIS_VERSION = Pattern.compile("(?m)^\\s*INTERLIS\\s+(2\\.3|2\\.4)\\s*;");

  private final ConstraintReviewTools reviewTools;
  private final ConstraintTestTools testTools;
  private final IliCompilerService compilerService;

  @Autowired
  public ConstraintDecisionTableTools(
      ConstraintReviewTools reviewTools,
      ConstraintTestTools testTools,
      IliCompilerService compilerService) {
    this.reviewTools = reviewTools;
    this.testTools = testTools;
    this.compilerService = compilerService;
  }

  ConstraintDecisionTableTools(ConstraintReviewTools reviewTools, ConstraintTestTools testTools) {
    this(reviewTools, testTools, new IliCompilerService());
  }

  public static class DecisionRow {
    public String name;
    public List<DecisionCondition> conditions;
  }

  public static class DecisionCondition {
    public String attribute;
    public @Nullable String aggregate;
    public @Nullable String addAttribute;
    public @Nullable Boolean defined;
    public @Nullable String operator;
    public @Nullable Object value;
  }

  @McpTool(
      name = "generateIliConstraintFromDecisionTable",
      description = "Erzeugt aus einer strukturierten Entscheidungstabelle einen INTERLIS Mandatory Constraint, leitet ueber die gemeinsame semantische IR-/Solver-Pipeline Boundary-/Kategoriefaelle ab und beweist den erzeugten Constraint mit testIliConstraint und dem echten ilivalidator. Unterstuetzt direkte NUMERIC/BOOLEAN/ENUM-Attribute, einen einzelnen hoechstens einwertigen Association-Pfad Rolle->Attribut, SUM auf einem mehrwertigen numerischen Association-Pfad sowie DEFINED/NOT DEFINED und SUM plus direktes NUMERIC-Attribut."
  )
  public Map<String, Object> generateIliConstraintFromDecisionTable(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext ohne den zu erzeugenden Constraint", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Klassenkontext Model.Topic.Class", required = true) String context,
      @McpToolParam(description = "Technischer Name des zu erzeugenden Constraints", required = true) String constraintName,
      @McpToolParam(description = "Erlaubte Entscheidungszeilen. Standardbedingung: attribute, operator, value. Optional aggregate=SUM. Fuer Summenpraesenz: defined=true/false ohne operator/value. Fuer Addition: addAttribute=<direktes NUMERIC-Attribut> zusammen mit aggregate=SUM, operator == und numerischem value.", required = true) List<DecisionRow> rows,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition", required = false) @Nullable String modelRepositories) {
    String normalizedContext = requireContext(context);
    String normalizedConstraintName = requireIdentifier(constraintName, "constraintName");
    List<NormalizedRow> normalizedRows = normalizeRows(rows);

    ConstraintExpression.IliVersion version = iliVersion(modelText);
    ConstraintExpression semanticExpression = decisionTableExpression(normalizedRows);
    String expression = semanticExpression.toInterlis(version);
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

    IliCompilerService.CompilationResult compilation = compilerService.compile(
        proofModelText,
        modelRepositories,
        "ili2c_constraint_decision_table_proof_");
    if (!compilation.valid() || compilation.transferDescription() == null) {
      return unavailable(
          "GENERATED_CONSTRAINT_NOT_COMPILABLE",
          "The generated decision-table constraint could not be compiled for semantic proof generation.",
          expression,
          constraintBlock,
          normalizedRows,
          review);
    }

    ConstraintModelSynthesizer.ModelBinding binding;
    try {
      binding = ConstraintModelSynthesizer.bind(
          compilation.transferDescription(),
          normalizedContext,
          semanticExpression);
    } catch (IllegalArgumentException ex) {
      return unavailable(
          "UNSUPPORTED_ATTRIBUTE_PATH_OR_TYPE",
          ex.getMessage(),
          expression,
          constraintBlock,
          normalizedRows,
          review);
    }

    ConstraintCoveragePlanner.CoveragePlan coverage = ConstraintCoveragePlanner.solve(
        semanticExpression,
        binding);
    if (coverage.cases().isEmpty()) {
      String reason = coverage.unsolved().isEmpty()
          ? "No semantic boundary/category probes could be derived."
          : coverage.unsolved().getFirst().reason();
      return unavailable(
          "NO_BOUNDARY_CASES",
          reason,
          expression,
          constraintBlock,
          normalizedRows,
          review);
    }

    ProofCases proofCases;
    try {
      proofCases = proofCases(
          semanticExpression,
          binding,
          coverage,
          normalizedRows,
          version);
    } catch (IllegalArgumentException ex) {
      return unavailable(
          "OBJECT_GRAPH_SYNTHESIS_FAILED",
          ex.getMessage(),
          expression,
          constraintBlock,
          normalizedRows,
          review);
    }

    Map<String, Object> verification = testTools.testIliConstraint(
        proofModelText,
        normalizedConstraintName,
        proofCases.cases(),
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
    result.put("boundaryCases", proofCases.summaries());
    result.put("boundaryCaseCount", proofCases.cases().size());
    result.put("coverageGoalCount", proofCases.cases().size() + coverage.unsolved().size());
    result.put("coverageSolvedCount", proofCases.cases().size());
    if (!coverage.unsolved().isEmpty()) {
      result.put("coverageUnsolved", coverageUnsolved(coverage, version));
    }
    result.put("verification", verification);
    if (!verified) {
      result.put("reasonCode", "BOUNDARY_PROOF_FAILED");
      result.put("reason", "The generated semantic proof cases were created, but the validator did not confirm all expected outcomes.");
    }
    result.put("limitations", limitations());
    return result;
  }

  private ProofCases proofCases(
      ConstraintExpression semanticExpression,
      ConstraintModelSynthesizer.ModelBinding binding,
      ConstraintCoveragePlanner.CoveragePlan coverage,
      List<NormalizedRow> rows,
      ConstraintExpression.IliVersion version) {
    List<ConstraintTestTools.TestCase> cases = new ArrayList<>();
    List<Map<String, Object>> summaries = new ArrayList<>();
    int index = 1;
    for (ConstraintCoveragePlanner.CoverageCase coverageCase : coverage.cases()) {
      Map<String, Object> assignment = coverageCase.solution().assignment();
      boolean expectedValid = ConstraintExpressionEngine.evaluateConstraint(
          semanticExpression,
          ConstraintExpressionEngine.EvaluationContext.of(assignment));
      ConstraintModelSynthesizer.ObjectGraph graph = ConstraintModelSynthesizer.synthesize(
          binding,
          assignment,
          "decision_case_" + index);

      ConstraintTestTools.TestCase testCase = toTestCase(
          "boundary " + index + " - " + coverageCase.goal().reason(),
          expectedValid,
          graph);
      cases.add(testCase);

      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("name", testCase.name);
      summary.put("purpose", coverageCase.goal().reason());
      summary.put("source", coverageCase.goal().expression().toInterlis(version));
      summary.put("values", summaryValues(assignment, rows));
      summary.put("expectedConstraintValid", expectedValid);
      summary.put("objectCount", graph.objects().size());
      summary.put("associationLinkCount", graph.links().size());
      summaries.add(summary);
      index++;
    }
    return new ProofCases(cases, summaries);
  }

  private ConstraintTestTools.TestCase toTestCase(
      String name,
      boolean expected,
      ConstraintModelSynthesizer.ObjectGraph graph) {
    ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
    testCase.name = name;
    testCase.expectedConstraintValid = expected;
    testCase.objects = graph.objects().stream().map(object -> {
      ConstraintTestTools.TestObject result = new ConstraintTestTools.TestObject();
      result.classFqn = object.classFqn();
      result.oid = object.oid();
      result.values = object.values();
      return result;
    }).toList();
    testCase.links = graph.links().stream().map(link -> {
      ConstraintTestTools.TestLink result = new ConstraintTestTools.TestLink();
      result.associationFqn = link.associationFqn();
      result.roles = link.roles();
      return result;
    }).toList();
    return testCase;
  }

  private Map<String, Object> summaryValues(
      Map<String, Object> assignment,
      List<NormalizedRow> rows) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String reference : referencedAttributes(rows)) {
      Object raw = assignment.get(reference);
      result.put(reference, summaryAssignmentValue(raw, aggregateForAttribute(rows, reference)));
    }
    return result;
  }

  private Object summaryAssignmentValue(@Nullable Object raw, AggregateKind aggregate) {
    if (raw == null || raw == ConstraintExpressionEngine.Undefined.INSTANCE) {
      return "UNDEFINED";
    }
    if (aggregate == AggregateKind.SUM && raw instanceof Collection<?> collection) {
      if (collection.isEmpty()) {
        return "UNDEFINED";
      }
      BigDecimal total = BigDecimal.ZERO;
      for (Object item : collection) {
        try {
          total = total.add(item instanceof BigDecimal decimal
              ? decimal
              : new BigDecimal(String.valueOf(item)));
        } catch (NumberFormatException ex) {
          return String.valueOf(raw);
        }
      }
      return decimal(total);
    }
    if (raw instanceof BigDecimal decimal) {
      return decimal(decimal);
    }
    return raw;
  }

  private List<Map<String, Object>> coverageUnsolved(
      ConstraintCoveragePlanner.CoveragePlan coverage,
      ConstraintExpression.IliVersion version) {
    return coverage.unsolved().stream().map(solution -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("goalKind", solution.goal().kind().name());
      item.put("purpose", solution.goal().reason());
      item.put("expression", solution.goal().expression().toInterlis(version));
      item.put("reasonCode", solution.reasonCode());
      item.put("reason", solution.reason());
      return item;
    }).toList();
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
        String attribute = requireDecisionPath(condition.attribute);
        AggregateKind aggregate = aggregateKind(condition.aggregate);
        if (aggregate == AggregateKind.SUM && !attribute.contains("->")) {
          throw new IllegalArgumentException("SUM requires exactly one association path Rolle->Attribut.");
        }

        String addAttribute = condition.addAttribute == null || condition.addAttribute.isBlank()
            ? null
            : requireIdentifier(condition.addAttribute, "addAttribute");
        Boolean defined = condition.defined;
        if (defined != null) {
          if (aggregate != AggregateKind.SUM || addAttribute != null) {
            throw new IllegalArgumentException("defined=true/false is supported only for aggregate=SUM without addAttribute.");
          }
          if ((condition.operator != null && !condition.operator.isBlank()) || condition.value != null) {
            throw new IllegalArgumentException("A defined=true/false condition must not provide operator or value.");
          }
          conditions.add(new NormalizedCondition(attribute, aggregate, null, defined, null, null));
          continue;
        }

        String operator = condition.operator == null ? "" : condition.operator.trim();
        if (!OPERATORS.contains(operator)) {
          throw new IllegalArgumentException("Unsupported decision-table operator '" + operator + "'.");
        }
        Literal literal = literalValue(condition.value, name, conditionIndex);
        if (aggregate == AggregateKind.SUM && literal.kind() != ValueKind.NUMERIC) {
          throw new IllegalArgumentException("SUM decision conditions require a numeric comparison value.");
        }
        if (addAttribute != null
            && (aggregate != AggregateKind.SUM
                || literal.kind() != ValueKind.NUMERIC
                || !"==".equals(operator))) {
          throw new IllegalArgumentException(
              "addAttribute is deliberately limited to aggregate=SUM with numeric operator ==.");
        }
        if (literal.kind() != ValueKind.NUMERIC && !Set.of("==", "!=").contains(operator)) {
          throw new IllegalArgumentException(
              "Boolean and enum decision conditions support == and != only; got '" + operator + "'.");
        }
        conditions.add(new NormalizedCondition(attribute, aggregate, addAttribute, null, operator, literal));
      }
      result.add(new NormalizedRow(name, conditions));
    }
    validateAggregateShape(result);
    return result;
  }

  private void validateAggregateShape(List<NormalizedRow> rows) {
    Map<String, AggregateKind> semantics = new LinkedHashMap<>();
    Set<String> sumPaths = new LinkedHashSet<>();
    Set<String> addAttributes = new LinkedHashSet<>();
    for (NormalizedRow row : rows) {
      for (NormalizedCondition condition : row.conditions()) {
        AggregateKind previous = semantics.putIfAbsent(condition.attribute(), condition.aggregate());
        if (previous != null && previous != condition.aggregate()) {
          throw new IllegalArgumentException(
              "The same decision attribute/path cannot be used both directly and with SUM: " + condition.attribute());
        }
        if (condition.aggregate() == AggregateKind.SUM) {
          sumPaths.add(condition.attribute());
        }
        if (condition.addAttribute() != null) {
          addAttributes.add(condition.addAttribute());
        }
      }
    }
    if (sumPaths.size() > 1) {
      throw new IllegalArgumentException("Decision-table SUM support currently allows one distinct aggregate path per table.");
    }
    if (addAttributes.size() > 1) {
      throw new IllegalArgumentException("Decision-table addition support currently allows one direct addAttribute per table.");
    }
  }

  private AggregateKind aggregateKind(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return AggregateKind.NONE;
    }
    if ("SUM".equalsIgnoreCase(value.trim())) {
      return AggregateKind.SUM;
    }
    throw new IllegalArgumentException("Unsupported decision-table aggregate '" + value + "'. Only SUM is supported.");
  }

  private Literal literalValue(@Nullable Object value, String rowName, int conditionIndex) {
    if (value == null) {
      throw new IllegalArgumentException(
          "Decision row '" + rowName + "' condition " + conditionIndex + " requires a value.");
    }
    if (value instanceof Boolean bool) {
      return new Literal(ValueKind.BOOLEAN, bool);
    }
    if (value instanceof Number) {
      return new Literal(ValueKind.NUMERIC, decimalValue(value, rowName, conditionIndex));
    }
    if (value instanceof String text) {
      String normalized = text.trim();
      try {
        return new Literal(ValueKind.NUMERIC, new BigDecimal(normalized));
      } catch (NumberFormatException ignore) {
      }
      if (normalized.startsWith("#")) {
        normalized = normalized.substring(1);
      }
      if (!ENUM_VALUE.matcher(normalized).matches()) {
        throw new IllegalArgumentException(
            "Decision row '" + rowName + "' condition " + conditionIndex
                + " value is neither numeric, boolean nor an enum value: " + value);
      }
      return new Literal(ValueKind.ENUM, normalized);
    }
    throw new IllegalArgumentException(
        "Decision row '" + rowName + "' condition " + conditionIndex
            + " value must be numeric, boolean or an enum string.");
  }

  private BigDecimal decimalValue(Object value, String rowName, int conditionIndex) {
    try {
      return new BigDecimal(String.valueOf(value));
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(
          "Decision row '" + rowName + "' condition " + conditionIndex + " value is not numeric: " + value);
    }
  }

  private ConstraintExpression decisionTableExpression(List<NormalizedRow> rows) {
    List<ConstraintExpression> expressions = rows.stream().map(this::rowExpression).toList();
    return expressions.size() == 1
        ? expressions.getFirst()
        : new ConstraintExpression.Or(expressions);
  }

  private ConstraintExpression rowExpression(NormalizedRow row) {
    List<ConstraintExpression> expressions = row.conditions().stream()
        .map(this::conditionExpression)
        .toList();
    return expressions.size() == 1
        ? expressions.getFirst()
        : new ConstraintExpression.And(expressions);
  }

  private ConstraintExpression conditionExpression(NormalizedCondition condition) {
    ConstraintExpression operand = operandExpression(condition);
    if (condition.defined() != null) {
      ConstraintExpression defined = new ConstraintExpression.Defined(operand);
      return condition.defined() ? defined : new ConstraintExpression.Not(defined);
    }
    if (condition.addAttribute() != null) {
      operand = standardFunctionCall(
          "NUMERIC_ADD",
          operand,
          new ConstraintExpression.Attribute(
              condition.addAttribute(),
              ConstraintExpression.Type.scalar(ConstraintExpression.ScalarKind.NUMERIC)));
    }
    return new ConstraintExpression.Comparison(
        comparisonOperator(condition.operator()),
        operand,
        literalExpression(condition.literal()));
  }

  private ConstraintExpression operandExpression(NormalizedCondition condition) {
    if (condition.aggregate() == AggregateKind.SUM) {
      ConstraintExpression.Path path = new ConstraintExpression.Path(
          condition.attribute(),
          ConstraintExpression.Type.collection(ConstraintExpression.ScalarKind.NUMERIC));
      return standardFunctionCall("COLLECTION_SUM", path);
    }
    ConstraintExpression.Type type = ConstraintExpression.Type.scalar(
        scalarKind(condition.literal() != null ? condition.literal().kind() : ValueKind.NUMERIC));
    return condition.attribute().contains("->")
        ? new ConstraintExpression.Path(condition.attribute(), type)
        : new ConstraintExpression.Attribute(condition.attribute(), type);
  }

  private ConstraintExpression literalExpression(@Nullable Literal literal) {
    if (literal == null) {
      throw new IllegalStateException("Comparison literal is missing.");
    }
    return switch (literal.kind()) {
      case NUMERIC -> new ConstraintExpression.NumericLiteral((BigDecimal) literal.value());
      case BOOLEAN -> new ConstraintExpression.BooleanLiteral((Boolean) literal.value());
      case ENUM -> new ConstraintExpression.EnumLiteral(String.valueOf(literal.value()));
    };
  }

  private ConstraintExpression.FunctionCall standardFunctionCall(
      String semanticId,
      ConstraintExpression... arguments) {
    StandardFunctionRegistry.StandardFunction function = StandardFunctionRegistry.findBySemanticId(semanticId)
        .orElseThrow(() -> new IllegalStateException("Missing standard function semantics: " + semanticId));
    return new ConstraintExpression.FunctionCall(function.definition(), List.of(arguments));
  }

  private ConstraintExpression.ComparisonOperator comparisonOperator(@Nullable String operator) {
    if (operator == null) {
      throw new IllegalStateException("Comparison operator is missing.");
    }
    return switch (operator) {
      case "==" -> ConstraintExpression.ComparisonOperator.EQ;
      case "!=" -> ConstraintExpression.ComparisonOperator.NE;
      case "<" -> ConstraintExpression.ComparisonOperator.LT;
      case "<=" -> ConstraintExpression.ComparisonOperator.LE;
      case ">" -> ConstraintExpression.ComparisonOperator.GT;
      case ">=" -> ConstraintExpression.ComparisonOperator.GE;
      default -> throw new IllegalArgumentException("Unsupported decision-table operator '" + operator + "'.");
    };
  }

  private ConstraintExpression.ScalarKind scalarKind(ValueKind kind) {
    return switch (kind) {
      case NUMERIC -> ConstraintExpression.ScalarKind.NUMERIC;
      case BOOLEAN -> ConstraintExpression.ScalarKind.BOOLEAN;
      case ENUM -> ConstraintExpression.ScalarKind.ENUM;
    };
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
        "(?m)^\\s*(?:(?:CONTRACTED|REFSYSTEM|SYMBOLOGY|TYPE)\\s+)?MODEL\\s+" + Pattern.quote(modelName) + "\\b")
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
    return modelText.substring(0, insertAt) + indentedBlock + "\n\n" + modelText.substring(insertAt);
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

  private Set<String> referencedAttributes(List<NormalizedRow> rows) {
    Set<String> result = new LinkedHashSet<>();
    for (NormalizedRow row : rows) {
      for (NormalizedCondition condition : row.conditions()) {
        result.add(condition.attribute());
        if (condition.addAttribute() != null) {
          result.add(condition.addAttribute());
        }
      }
    }
    return result;
  }

  private AggregateKind aggregateForAttribute(List<NormalizedRow> rows, String attribute) {
    for (NormalizedRow row : rows) {
      for (NormalizedCondition condition : row.conditions()) {
        if (attribute.equals(condition.attribute())) {
          return condition.aggregate();
        }
      }
    }
    return AggregateKind.NONE;
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

  private String requireDecisionPath(@Nullable String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("condition attribute is required.");
    }
    String[] parts = value.trim().split("\\s*->\\s*", -1);
    if (parts.length < 1 || parts.length > 2) {
      throw new IllegalArgumentException(
          "condition attribute must be a direct attribute or exactly one association role path Rolle->Attribut.");
    }
    for (String part : parts) {
      requireIdentifier(part, "condition path part");
    }
    return String.join("->", parts);
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
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("attribute", condition.attribute());
        if (condition.aggregate() == AggregateKind.SUM) {
          summary.put("aggregate", "SUM");
        }
        if (condition.addAttribute() != null) {
          summary.put("addAttribute", condition.addAttribute());
        }
        if (condition.defined() != null) {
          summary.put("defined", condition.defined());
        } else {
          summary.put("operator", condition.operator());
          summary.put("value", summaryValue(condition.literal()));
        }
        conditions.add(summary);
      }
      result.add(Map.of("name", row.name(), "allowed", true, "conditions", conditions));
    }
    return result;
  }

  private Object summaryValue(@Nullable Literal literal) {
    if (literal == null) {
      return "";
    }
    return switch (literal.kind()) {
      case NUMERIC -> decimal((BigDecimal) literal.value());
      case BOOLEAN -> literal.value();
      case ENUM -> "#" + literal.value();
    };
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
        "Decision rows describe allowed combinations only; the generated Mandatory Constraint is their OR-union.",
        "Direct numeric attributes and one single-valued association path Role->Attribute support ==, !=, <, <=, >, >=; BOOLEAN and ENUM endpoints support == and !=.",
        "aggregate=SUM supports one multi-valued Role->NUMERIC path per table. Boundary totals are solved to concrete collection assignments by the shared ConstraintGoalSolver and materialized as real linked objects by ConstraintModelSynthesizer.",
        "defined=true/false is limited to aggregate=SUM; addAttribute is limited to aggregate=SUM with numeric ==. Their logical combination is no longer handled by an AFU-specific proof branch but by the same semantic IR evaluator/solver as every other supported table.",
        "Coverage probes are derived from the semantic expression and bound model domains. Unsolvable finite-domain probes are reported in coverageUnsolved instead of being guessed.",
        "Longer paths, multiple SUM paths, text decision values and geometry remain unsupported rather than guessed.");
  }

  private enum ValueKind {
    NUMERIC,
    BOOLEAN,
    ENUM
  }

  private enum AggregateKind {
    NONE,
    SUM
  }

  private record Literal(ValueKind kind, Object value) {
  }

  private record NormalizedRow(String name, List<NormalizedCondition> conditions) {
  }

  private record NormalizedCondition(
      String attribute,
      AggregateKind aggregate,
      @Nullable String addAttribute,
      @Nullable Boolean defined,
      @Nullable String operator,
      @Nullable Literal literal) {
  }

  private record ProofCases(
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries) {
  }
}
