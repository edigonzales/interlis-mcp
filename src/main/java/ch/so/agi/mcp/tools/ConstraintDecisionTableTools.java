package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.AssociationDef;
import ch.interlis.ili2c.metamodel.AttributeRef;
import ch.interlis.ili2c.metamodel.Cardinality;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.EnumerationType;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.ObjectPath;
import ch.interlis.ili2c.metamodel.PathEl;
import ch.interlis.ili2c.metamodel.PathElAbstractClassRole;
import ch.interlis.ili2c.metamodel.PathElAssocRole;
import ch.interlis.ili2c.metamodel.RoleDef;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Type;
import ch.interlis.ili2c.metamodel.Viewable;
import ch.interlis.ili2c.parser.Ili23Parser;
import ch.so.agi.mcp.service.IliCompilerService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ConstraintDecisionTableTools {

  private static final Set<String> OPERATORS = Set.of("==", "!=", "<", "<=", ">", ">=");
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
  private static final Pattern ENUM_VALUE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*");
  private static final int MAX_AGGREGATE_OBJECTS = 5;

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
    public String operator;
    public Object value;
  }

  @McpTool(
      name = "generateIliConstraintFromDecisionTable",
      description = "Erzeugt aus einer strukturierten Entscheidungstabelle einen INTERLIS Mandatory Constraint, leitet automatisch Boundary-/Kategoriefaelle ab und beweist den erzeugten Constraint mit testIliConstraint und dem echten ilivalidator. Jede Tabellenzeile beschreibt eine erlaubte Kombination; Bedingungen einer Zeile werden mit AND, mehrere Zeilen mit OR verbunden. Unterstuetzt direkte NUMERIC/BOOLEAN/ENUM-Attribute, einen einzelnen hoechstens einwertigen Association-Pfad Rolle->Attribut sowie SUM auf einem mehrwertigen numerischen Association-Pfad."
  )
  public Map<String, Object> generateIliConstraintFromDecisionTable(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext ohne den zu erzeugenden Constraint", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Klassenkontext Model.Topic.Class", required = true) String context,
      @McpToolParam(description = "Technischer Name des zu erzeugenden Constraints", required = true) String constraintName,
      @McpToolParam(description = "Erlaubte Entscheidungszeilen. Jede Bedingung hat attribute, operator und value; optional aggregate=SUM fuer einen mehrwertigen numerischen Rolle->Attribut-Pfad. Numerisch: Zahl; Boolean: true/false; Enum: String wie active oder #active.", required = true) List<DecisionRow> rows,
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

    Map<String, AttributeDomain> domains = attributeDomains(
        map(review.get("ast")),
        proofModelText,
        normalizedContext,
        normalizedRows,
        modelRepositories);
    if (domains.size() != referencedAttributes(normalizedRows).size()) {
      return unavailable(
          "UNSUPPORTED_ATTRIBUTE_PATH_OR_TYPE",
          "Decision-table proof requires a direct NUMERIC/BOOLEAN/ENUM attribute, exactly one single-valued association role followed by such an attribute, or aggregate=SUM on one multi-valued bounded non-negative NUMERIC association path.",
          expression,
          constraintBlock,
          normalizedRows,
          review);
    }

    String domainProblem = validateConditionDomains(normalizedRows, domains);
    if (domainProblem != null) {
      return unavailable(
          "CONDITION_VALUE_TYPE_MISMATCH",
          domainProblem,
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
      result.put("reason", "The generated boundary/category cases were created, but the validator did not confirm all expected decision-table outcomes.");
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
        String attribute = requireDecisionPath(condition.attribute);
        AggregateKind aggregate = aggregateKind(condition.aggregate);
        if (aggregate == AggregateKind.SUM && !attribute.contains("->")) {
          throw new IllegalArgumentException("SUM requires exactly one association path Rolle->Attribut.");
        }
        String operator = condition.operator == null ? "" : condition.operator.trim();
        if (!OPERATORS.contains(operator)) {
          throw new IllegalArgumentException("Unsupported decision-table operator '" + operator + "'.");
        }
        Literal literal = literalValue(condition.value, name, conditionIndex);
        if (aggregate == AggregateKind.SUM && literal.kind() != ValueKind.NUMERIC) {
          throw new IllegalArgumentException("SUM decision conditions require a numeric comparison value.");
        }
        if (literal.kind() != ValueKind.NUMERIC && !Set.of("==", "!=").contains(operator)) {
          throw new IllegalArgumentException(
              "Boolean and enum decision conditions support == and != only; got '" + operator + "'.");
        }
        conditions.add(new NormalizedCondition(attribute, aggregate, operator, literal));
      }
      result.add(new NormalizedRow(name, conditions));
    }
    validateAggregateShape(result);
    return result;
  }

  private void validateAggregateShape(List<NormalizedRow> rows) {
    Map<String, AggregateKind> semantics = new LinkedHashMap<>();
    Set<String> sumPaths = new LinkedHashSet<>();
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
      }
    }
    if (sumPaths.size() > 1) {
      throw new IllegalArgumentException(
          "Decision-table SUM support currently allows one distinct aggregate path per table.");
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
          "Decision row '" + rowName + "' condition " + conditionIndex
              + " value is not numeric: " + value);
    }
  }

  private String renderExpression(List<NormalizedRow> rows) {
    List<String> rowExpressions = new ArrayList<>();
    for (NormalizedRow row : rows) {
      List<String> conditions = row.conditions().stream()
          .map(this::renderCondition)
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

  private String renderCondition(NormalizedCondition condition) {
    String operand = condition.aggregate() == AggregateKind.SUM
        ? "Math.sum(\"" + condition.attribute() + "\")"
        : condition.attribute();
    return operand + " " + condition.operator() + " " + renderLiteral(condition.literal());
  }

  private String renderLiteral(Literal literal) {
    return switch (literal.kind()) {
      case NUMERIC -> decimal((BigDecimal) literal.value());
      case BOOLEAN -> "#" + literal.value().toString().toLowerCase();
      case ENUM -> "#" + literal.value();
    };
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

  private Map<String, AttributeDomain> attributeDomains(
      Map<String, Object> ast,
      String modelText,
      String context,
      List<NormalizedRow> rows,
      @Nullable String modelRepositories) {
    Map<String, Map<String, Object>> directTypes = new LinkedHashMap<>();
    collectDirectAttributeTypes(map(ast.get("condition")), directTypes);
    Map<String, AssociationPath> associationPaths = resolveAssociationPaths(
        modelText,
        context,
        referencedAttributes(rows),
        modelRepositories);

    Map<String, AttributeDomain> result = new LinkedHashMap<>();
    Map<String, Integer> literalScales = literalScales(rows);
    for (String attribute : referencedAttributes(rows)) {
      AggregateKind aggregate = aggregateForAttribute(rows, attribute);
      int literalScale = literalScales.getOrDefault(attribute, 0);

      if (!attribute.contains("->")) {
        if (aggregate != AggregateKind.NONE) {
          continue;
        }
        AttributeDomain domain = directDomain(directTypes.get(attribute), literalScale);
        if (domain != null) {
          result.put(attribute, domain);
        }
        continue;
      }

      AssociationPath path = associationPaths.get(attribute);
      if (path == null) {
        continue;
      }
      if (aggregate == AggregateKind.NONE) {
        if (path.unbounded() || path.maximum() > 1) {
          continue;
        }
        AttributeDomain domain = pathDomain(path, literalScale, AggregateKind.NONE);
        if (domain != null) {
          result.put(attribute, domain);
        }
      } else if (aggregate == AggregateKind.SUM) {
        AttributeDomain domain = sumDomain(path, literalScale);
        if (domain != null) {
          result.put(attribute, domain);
        }
      }
    }
    return result;
  }

  private @Nullable AttributeDomain directDomain(
      @Nullable Map<String, Object> type,
      int literalScale) {
    if (type == null || type.isEmpty()) {
      return null;
    }
    String kind = String.valueOf(type.getOrDefault("kind", ""));
    if ("NUMERIC".equals(kind)) {
      return new AttributeDomain(
          ValueKind.NUMERIC,
          numericDomain(type, literalScale),
          List.of(),
          null,
          AggregateKind.NONE,
          null);
    }
    if ("BOOLEAN".equals(kind)) {
      return new AttributeDomain(
          ValueKind.BOOLEAN,
          null,
          List.of("false", "true"),
          null,
          AggregateKind.NONE,
          null);
    }
    if ("ENUM".equals(kind)) {
      List<String> values = enumValues(type.get("values"));
      return new AttributeDomain(
          ValueKind.ENUM,
          null,
          values,
          null,
          AggregateKind.NONE,
          null);
    }
    return null;
  }

  private @Nullable AttributeDomain pathDomain(
      AssociationPath path,
      int literalScale,
      AggregateKind aggregate) {
    Type declared = path.endpointType();
    Type real = Type.findReal(declared);
    if (real instanceof NumericType numeric) {
      NumericDomain numericDomain = numericDomain(numeric, literalScale);
      return new AttributeDomain(
          ValueKind.NUMERIC,
          numericDomain,
          List.of(),
          path,
          aggregate,
          numericDomain);
    }
    if (declared.isBoolean() || real.isBoolean()) {
      return new AttributeDomain(
          ValueKind.BOOLEAN,
          null,
          List.of("false", "true"),
          path,
          aggregate,
          null);
    }
    if (real instanceof EnumerationType enumeration) {
      return new AttributeDomain(
          ValueKind.ENUM,
          null,
          new ArrayList<>(enumeration.getValues()),
          path,
          aggregate,
          null);
    }
    return null;
  }

  private @Nullable AttributeDomain sumDomain(AssociationPath path, int literalScale) {
    if ((!path.unbounded() && path.maximum() <= 1) || path.minimum() > 1) {
      return null;
    }
    Type real = Type.findReal(path.endpointType());
    if (!(real instanceof NumericType numeric)) {
      return null;
    }
    NumericDomain endpoint = numericDomain(numeric, literalScale);
    if (endpoint.minimum() == null
        || endpoint.maximum() == null
        || endpoint.minimum().compareTo(BigDecimal.ZERO) < 0) {
      return null;
    }

    int maxObjects = path.effectiveMaximumObjects();
    BigDecimal aggregateMaximum = endpoint.maximum().multiply(BigDecimal.valueOf(maxObjects));
    NumericDomain aggregate = new NumericDomain(
        endpoint.minimum(),
        aggregateMaximum,
        endpoint.step());
    return new AttributeDomain(
        ValueKind.NUMERIC,
        aggregate,
        List.of(),
        path,
        AggregateKind.SUM,
        endpoint);
  }

  private List<String> enumValues(@Nullable Object rawValues) {
    List<String> values = new ArrayList<>();
    if (rawValues instanceof List<?> list) {
      for (Object raw : list) {
        String value = String.valueOf(raw);
        values.add(value.startsWith("#") ? value.substring(1) : value);
      }
    }
    return values;
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

  private Map<String, AssociationPath> resolveAssociationPaths(
      String modelText,
      String context,
      Set<String> attributes,
      @Nullable String modelRepositories) {
    Set<String> paths = new LinkedHashSet<>();
    for (String attribute : attributes) {
      if (attribute.contains("->")) {
        paths.add(attribute);
      }
    }
    if (paths.isEmpty()) {
      return Map.of();
    }

    IliCompilerService.CompilationResult compilation = compilerService.compile(
        modelText,
        modelRepositories,
        "ili2c_constraint_decision_path_");
    if (!compilation.valid() || compilation.transferDescription() == null) {
      return Map.of();
    }
    TransferDescription td = compilation.transferDescription();
    Element contextElement = td.getElement(context);
    if (!(contextElement instanceof Viewable<?> root)) {
      return Map.of();
    }

    Map<String, AssociationPath> result = new LinkedHashMap<>();
    for (String path : paths) {
      try {
        ObjectPath objectPath = Ili23Parser.parseObjectOrAttributePath(td, root, path);
        if (!matchesParsedPath(objectPath, path)) {
          continue;
        }
        PathEl[] elements = objectPath.getPathElements();
        if (elements.length != 2 || !(elements[1] instanceof AttributeRef attributeRef)) {
          continue;
        }
        RoleDef role;
        if (elements[0] instanceof PathElAssocRole associationRole) {
          role = associationRole.getRole();
        } else if (elements[0] instanceof PathElAbstractClassRole classRole) {
          role = classRole.getRole();
        } else {
          continue;
        }
        if (!(role.getContainer() instanceof AssociationDef association)
            || role.getOppEnd() == null
            || role.getDestination() == null) {
          continue;
        }
        Cardinality cardinality = role.getCardinality();
        long minimum = cardinality != null ? cardinality.getMinimum() : 1;
        boolean unbounded = cardinality != null && cardinality.getMaximum() == Cardinality.UNBOUND;
        long maximum = cardinality != null ? cardinality.getMaximum() : 1;
        result.put(path, new AssociationPath(
            association.getScopedName(null),
            role.getName(),
            role.getOppEnd().getName(),
            role.getDestination().getScopedName(null),
            attributeRef.getAttr().getName(),
            attributeRef.getAttr().getDomainOrDerivedDomain(),
            minimum,
            maximum,
            unbounded));
      } catch (Exception ignore) {
      }
    }
    return result;
  }

  private boolean matchesParsedPath(ObjectPath objectPath, String path) {
    if (objectPath == null || objectPath.isDirty()) {
      return false;
    }
    String[] segments = path.split("->", -1);
    PathEl[] parsedElements = objectPath.getPathElements();
    if (parsedElements == null || parsedElements.length != segments.length) {
      return false;
    }
    for (int i = 0; i < segments.length; i++) {
      if (parsedElements[i] == null || !segments[i].trim().equals(parsedElements[i].getName())) {
        return false;
      }
    }
    return true;
  }

  private @Nullable String validateConditionDomains(
      List<NormalizedRow> rows,
      Map<String, AttributeDomain> domains) {
    for (NormalizedRow row : rows) {
      for (NormalizedCondition condition : row.conditions()) {
        AttributeDomain domain = domains.get(condition.attribute());
        if (domain == null
            || domain.kind() != condition.literal().kind()
            || domain.aggregate() != condition.aggregate()) {
          return "Decision condition '" + renderCondition(condition)
              + "' does not match the model attribute/path type or aggregate semantics.";
        }
        if (domain.kind() == ValueKind.ENUM
            && !domain.values().contains(String.valueOf(condition.literal().value()))) {
          return "Enum value '" + condition.literal().value() + "' is not declared for attribute/path '"
              + condition.attribute() + "'.";
        }
      }
    }
    return null;
  }

  private BoundaryGeneration generateBoundaryCases(
      String context,
      List<NormalizedRow> rows,
      Map<String, AttributeDomain> domains) {
    List<Candidate> candidates = new ArrayList<>();

    for (NormalizedRow row : rows) {
      Map<String, Object> baseline = representative(row, domains);
      if (baseline == null) {
        return BoundaryGeneration.unavailable(
            "UNSATISFIABLE_DECISION_ROW",
            "No in-domain assignment satisfies allowed decision row '" + row.name() + "'.");
      }
      candidates.add(new Candidate("ROW_WITNESS", row.name(), baseline));

      for (NormalizedCondition condition : row.conditions()) {
        AttributeDomain domain = domains.get(condition.attribute());
        for (BoundaryValue boundary : boundaryValues(condition, domain)) {
          Map<String, Object> values = new LinkedHashMap<>(baseline);
          values.put(condition.attribute(), boundary.value());
          if (allValuesInDomain(values, domains)) {
            candidates.add(new Candidate(
                boundary.purpose(),
                row.name() + ": " + renderCondition(condition),
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
      Fixture fixture = fixture(context, candidate.values(), domains, index);
      if (fixture == null) {
        return BoundaryGeneration.unavailable(
            "AGGREGATE_FIXTURE_UNAVAILABLE",
            "A derived SUM value could not be represented with the supported number of association target objects.");
      }

      ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
      testCase.name = "boundary " + index + " - " + candidate.purpose();
      testCase.expectedConstraintValid = expectedValid;
      testCase.objects = fixture.objects();
      testCase.links = fixture.links();
      cases.add(testCase);

      Map<String, Object> summaryValues = new LinkedHashMap<>();
      candidate.values().forEach((attribute, value) ->
          summaryValues.put(attribute, fixtureValue(value)));
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("name", testCase.name);
      summary.put("purpose", candidate.purpose());
      summary.put("source", candidate.source());
      summary.put("values", summaryValues);
      summary.put("expectedConstraintValid", expectedValid);
      if (!fixture.links().isEmpty()) {
        summary.put("objectCount", fixture.objects().size());
        summary.put("associationLinkCount", fixture.links().size());
      }
      summaries.add(summary);
      index++;
    }

    if (cases.isEmpty()) {
      return BoundaryGeneration.unavailable(
          "NO_BOUNDARY_CASES",
          "No in-domain boundary/category cases could be derived from the decision table.");
    }
    return new BoundaryGeneration(true, cases, summaries, "", "");
  }

  private @Nullable Fixture fixture(
      String context,
      Map<String, Object> values,
      Map<String, AttributeDomain> domains,
      int caseIndex) {
    String rootOid = "decision_case_" + caseIndex + "_root";
    ConstraintTestTools.TestObject root = new ConstraintTestTools.TestObject();
    root.classFqn = context;
    root.oid = rootOid;
    Map<String, Object> rootValues = new LinkedHashMap<>();
    root.values = rootValues;

    Map<String, TargetFixture> singleTargets = new LinkedHashMap<>();
    List<TargetFixture> aggregateTargets = new ArrayList<>();
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      AttributeDomain domain = domains.get(entry.getKey());
      if (domain.associationPath() == null) {
        rootValues.put(entry.getKey(), fixtureValue(entry.getValue()));
        continue;
      }
      AssociationPath path = domain.associationPath();
      if (domain.aggregate() == AggregateKind.SUM) {
        if (!(entry.getValue() instanceof BigDecimal total) || domain.endpointNumeric() == null) {
          return null;
        }
        List<BigDecimal> terms = aggregateTerms(total, domain.endpointNumeric(), path);
        if (terms.isEmpty()) {
          return null;
        }
        for (int i = 0; i < terms.size(); i++) {
          Map<String, Object> targetValues = new LinkedHashMap<>();
          targetValues.put(path.targetAttribute(), fixtureValue(terms.get(i)));
          aggregateTargets.add(new TargetFixture(
              path,
              "decision_case_" + caseIndex + "_sum_" + (i + 1),
              targetValues));
        }
      } else {
        String targetKey = path.associationFqn() + "|" + path.roleName();
        TargetFixture target = singleTargets.computeIfAbsent(targetKey, key -> new TargetFixture(
            path,
            "decision_case_" + caseIndex + "_path_" + (singleTargets.size() + 1),
            new LinkedHashMap<>()));
        target.values().put(path.targetAttribute(), fixtureValue(entry.getValue()));
      }
    }

    List<ConstraintTestTools.TestObject> objects = new ArrayList<>();
    objects.add(root);
    List<ConstraintTestTools.TestLink> links = new ArrayList<>();
    for (TargetFixture target : singleTargets.values()) {
      addTargetFixture(objects, links, target, rootOid);
    }
    for (TargetFixture target : aggregateTargets) {
      addTargetFixture(objects, links, target, rootOid);
    }
    return new Fixture(objects, links);
  }

  private void addTargetFixture(
      List<ConstraintTestTools.TestObject> objects,
      List<ConstraintTestTools.TestLink> links,
      TargetFixture target,
      String rootOid) {
    ConstraintTestTools.TestObject targetObject = new ConstraintTestTools.TestObject();
    targetObject.classFqn = target.path().targetClassFqn();
    targetObject.oid = target.oid();
    targetObject.values = target.values();
    objects.add(targetObject);

    ConstraintTestTools.TestLink link = new ConstraintTestTools.TestLink();
    link.associationFqn = target.path().associationFqn();
    link.roles = Map.of(
        target.path().roleName(), target.oid(),
        target.path().oppositeRoleName(), rootOid);
    links.add(link);
  }

  private List<BigDecimal> aggregateTerms(
      BigDecimal total,
      NumericDomain endpoint,
      AssociationPath path) {
    int maximumObjects = path.effectiveMaximumObjects();
    int firstCount = maximumObjects > 1 ? 2 : 1;
    for (int count = firstCount; count <= maximumObjects; count++) {
      List<BigDecimal> terms = distributeAggregate(total, endpoint, count);
      if (!terms.isEmpty()) {
        return terms;
      }
    }
    if (firstCount > 1) {
      return distributeAggregate(total, endpoint, 1);
    }
    return List.of();
  }

  private List<BigDecimal> distributeAggregate(
      BigDecimal total,
      NumericDomain endpoint,
      int count) {
    BigDecimal base = endpoint.minimum() != null
        ? endpoint.minimum()
        : BigDecimal.ZERO.setScale(endpoint.step().scale());
    BigDecimal maximum = endpoint.maximum();
    if (base.compareTo(BigDecimal.ZERO) < 0 || maximum == null) {
      return List.of();
    }

    BigDecimal remaining = total.subtract(base.multiply(BigDecimal.valueOf(count)));
    if (remaining.compareTo(BigDecimal.ZERO) < 0) {
      return List.of();
    }

    List<BigDecimal> terms = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      BigDecimal capacity = maximum.subtract(base);
      BigDecimal addition = remaining.min(capacity);
      BigDecimal value = base.add(addition);
      terms.add(value);
      remaining = remaining.subtract(addition);
    }
    if (remaining.compareTo(BigDecimal.ZERO) != 0
        || terms.stream().anyMatch(value -> !endpoint.contains(value))) {
      return List.of();
    }
    return terms;
  }

  private @Nullable Map<String, Object> representative(
      NormalizedRow row,
      Map<String, AttributeDomain> domains) {
    Map<String, Object> result = new LinkedHashMap<>();
    domains.forEach((attribute, domain) -> result.put(attribute, defaultValue(domain)));

    Map<String, List<NormalizedCondition>> byAttribute = new LinkedHashMap<>();
    for (NormalizedCondition condition : row.conditions()) {
      byAttribute.computeIfAbsent(condition.attribute(), key -> new ArrayList<>()).add(condition);
    }

    for (Map.Entry<String, List<NormalizedCondition>> entry : byAttribute.entrySet()) {
      AttributeDomain domain = domains.get(entry.getKey());
      Object selected = switch (domain.kind()) {
        case NUMERIC -> numericRepresentative(entry.getValue(), domain.numeric());
        case BOOLEAN -> List.of(false, true).stream()
            .filter(value -> entry.getValue().stream().allMatch(condition -> matches(condition, value)))
            .findFirst()
            .orElse(null);
        case ENUM -> domain.values().stream()
            .filter(value -> entry.getValue().stream().allMatch(condition -> matches(condition, value)))
            .findFirst()
            .orElse(null);
      };
      if (selected == null) {
        return null;
      }
      result.put(entry.getKey(), selected);
    }
    return result;
  }

  private @Nullable BigDecimal numericRepresentative(
      List<NormalizedCondition> conditions,
      NumericDomain domain) {
    LinkedHashSet<BigDecimal> candidates = new LinkedHashSet<>();
    for (NormalizedCondition condition : conditions) {
      BigDecimal literal = (BigDecimal) condition.literal().value();
      candidates.add(literal);
      candidates.add(literal.subtract(domain.step()));
      candidates.add(literal.add(domain.step()));
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

    return candidates.stream()
        .filter(domain::contains)
        .filter(value -> conditions.stream().allMatch(condition -> matches(condition, value)))
        .findFirst()
        .orElse(null);
  }

  private Object defaultValue(AttributeDomain domain) {
    return switch (domain.kind()) {
      case NUMERIC -> defaultNumericValue(domain.numeric());
      case BOOLEAN -> false;
      case ENUM -> domain.values().isEmpty() ? "" : domain.values().getFirst();
    };
  }

  private BigDecimal defaultNumericValue(NumericDomain domain) {
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
      AttributeDomain domain) {
    if (domain.kind() == ValueKind.BOOLEAN) {
      return List.of(
          new BoundaryValue("BOOLEAN_FALSE", false),
          new BoundaryValue("BOOLEAN_TRUE", true));
    }
    if (domain.kind() == ValueKind.ENUM) {
      return domain.values().stream()
          .map(value -> new BoundaryValue("ENUM_VALUE", value))
          .toList();
    }

    NumericDomain numeric = domain.numeric();
    BigDecimal literal = (BigDecimal) condition.literal().value();
    BigDecimal below = literal.subtract(numeric.step());
    BigDecimal above = literal.add(numeric.step());
    List<BoundaryValue> result = new ArrayList<>();
    switch (condition.operator()) {
      case "==" -> {
        addIfInDomain(result, numeric, "AT_EQUALITY", literal);
        if (numeric.contains(below)) {
          result.add(new BoundaryValue("BELOW_EQUALITY", below));
        } else {
          addIfInDomain(result, numeric, "ABOVE_EQUALITY", above);
        }
      }
      case "!=" -> {
        addIfInDomain(result, numeric, "AT_EXCLUDED_VALUE", literal);
        if (numeric.contains(below)) {
          result.add(new BoundaryValue("BELOW_EXCLUDED_VALUE", below));
        } else {
          addIfInDomain(result, numeric, "ABOVE_EXCLUDED_VALUE", above);
        }
      }
      case "<" -> {
        addIfInDomain(result, numeric, "JUST_BELOW_UPPER_BOUND", below);
        addIfInDomain(result, numeric, "AT_EXCLUSIVE_UPPER_BOUND", literal);
      }
      case "<=" -> {
        addIfInDomain(result, numeric, "AT_INCLUSIVE_UPPER_BOUND", literal);
        addIfInDomain(result, numeric, "JUST_ABOVE_UPPER_BOUND", above);
      }
      case ">" -> {
        addIfInDomain(result, numeric, "AT_EXCLUSIVE_LOWER_BOUND", literal);
        addIfInDomain(result, numeric, "JUST_ABOVE_LOWER_BOUND", above);
      }
      case ">=" -> {
        addIfInDomain(result, numeric, "JUST_BELOW_LOWER_BOUND", below);
        addIfInDomain(result, numeric, "AT_INCLUSIVE_LOWER_BOUND", literal);
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

  private boolean tableMatches(List<NormalizedRow> rows, Map<String, Object> values) {
    return rows.stream().anyMatch(row -> row.conditions().stream()
        .allMatch(condition -> values.containsKey(condition.attribute())
            && matches(condition, values.get(condition.attribute()))));
  }

  private boolean matches(NormalizedCondition condition, Object actual) {
    Literal literal = condition.literal();
    if (literal.kind() == ValueKind.NUMERIC) {
      if (!(actual instanceof BigDecimal number)) {
        return false;
      }
      int cmp = number.compareTo((BigDecimal) literal.value());
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

    boolean equal = literal.value().equals(actual);
    return "==".equals(condition.operator()) ? equal : !equal;
  }

  private boolean allValuesInDomain(
      Map<String, Object> values,
      Map<String, AttributeDomain> domains) {
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      AttributeDomain domain = domains.get(entry.getKey());
      if (domain == null) {
        return false;
      }
      if (domain.kind() == ValueKind.NUMERIC) {
        if (!(entry.getValue() instanceof BigDecimal number) || !domain.numeric().contains(number)) {
          return false;
        }
      } else if (domain.kind() == ValueKind.BOOLEAN) {
        if (!(entry.getValue() instanceof Boolean)) {
          return false;
        }
      } else if (!domain.values().contains(String.valueOf(entry.getValue()))) {
        return false;
      }
    }
    return true;
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

  private NumericDomain numericDomain(NumericType numeric, int literalScale) {
    BigDecimal minimum = numeric.getMinimum() != null
        ? new BigDecimal(numeric.getMinimum().toString())
        : null;
    BigDecimal maximum = numeric.getMaximum() != null
        ? new BigDecimal(numeric.getMaximum().toString())
        : null;
    int scale = Math.max(0, literalScale);
    if (minimum != null) {
      scale = Math.max(scale, Math.max(0, minimum.scale()));
    }
    if (maximum != null) {
      scale = Math.max(scale, Math.max(0, maximum.scale()));
    }
    return new NumericDomain(minimum, maximum, BigDecimal.ONE.movePointLeft(scale));
  }

  private Map<String, Integer> literalScales(List<NormalizedRow> rows) {
    Map<String, Integer> result = new LinkedHashMap<>();
    for (NormalizedRow row : rows) {
      for (NormalizedCondition condition : row.conditions()) {
        if (condition.literal().kind() == ValueKind.NUMERIC) {
          BigDecimal value = (BigDecimal) condition.literal().value();
          result.merge(condition.attribute(), Math.max(0, value.scale()), Math::max);
        }
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

  private String candidateKey(Map<String, Object> values) {
    return values.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> entry.getKey() + "=" + fixtureValue(entry.getValue()))
        .reduce((left, right) -> left + "|" + right)
        .orElse("");
  }

  private Object fixtureValue(Object value) {
    return value instanceof BigDecimal number ? decimal(number) : value;
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
        summary.put("operator", condition.operator());
        summary.put("value", summaryValue(condition.literal()));
        conditions.add(summary);
      }
      result.add(Map.of("name", row.name(), "allowed", true, "conditions", conditions));
    }
    return result;
  }

  private Object summaryValue(Literal literal) {
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
        "aggregate=SUM supports one multi-valued Role->NUMERIC path per table. The endpoint must have a bounded non-negative numeric domain; generated proof fixtures use at most five linked target objects.",
        "SUM boundary values are aggregate totals. The fixture generator distributes each total over real target objects and association links before testIliConstraint validates the XTF.",
        "Empty-collection/undefined SUM semantics, paths with more than one navigation step, other functions or arithmetic expressions, text values and geometry remain unsupported.");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(@Nullable Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> mapList(@Nullable Object value) {
    return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
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
      String operator,
      Literal literal) {
  }

  private record AssociationPath(
      String associationFqn,
      String roleName,
      String oppositeRoleName,
      String targetClassFqn,
      String targetAttribute,
      Type endpointType,
      long minimum,
      long maximum,
      boolean unbounded) {
    private int effectiveMaximumObjects() {
      if (unbounded || maximum > MAX_AGGREGATE_OBJECTS) {
        return MAX_AGGREGATE_OBJECTS;
      }
      return (int) Math.max(1, maximum);
    }
  }

  private record AttributeDomain(
      ValueKind kind,
      @Nullable NumericDomain numeric,
      List<String> values,
      @Nullable AssociationPath associationPath,
      AggregateKind aggregate,
      @Nullable NumericDomain endpointNumeric) {
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

  private record BoundaryValue(String purpose, Object value) {
  }

  private record Candidate(String purpose, String source, Map<String, Object> values) {
  }

  private record TargetFixture(AssociationPath path, String oid, Map<String, Object> values) {
  }

  private record Fixture(
      List<ConstraintTestTools.TestObject> objects,
      List<ConstraintTestTools.TestLink> links) {
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
