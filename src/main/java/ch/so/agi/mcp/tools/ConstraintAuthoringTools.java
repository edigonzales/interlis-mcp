package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.Constraint;
import ch.interlis.ili2c.metamodel.Container;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.so.agi.mcp.constraint.ConstraintAstTranslator;
import ch.so.agi.mcp.constraint.ConstraintExpression;
import ch.so.agi.mcp.constraint.StandardFunctionRegistry;
import ch.so.agi.mcp.service.IliCompilerService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

@Component
public class ConstraintAuthoringTools {

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
  private static final Pattern ENUM_VALUE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*");
  private static final Pattern INTERLIS_VERSION = Pattern.compile("(?m)^\\s*INTERLIS\\s+(2\\.3|2\\.4)\\s*;");
  private static final Set<String> COMPARISON_OPERATORS = Set.of("==", "!=", "<", "<=", ">", ">=");

  private final IliCompilerService compilerService;
  private final ConstraintCaseGenerationTools caseGenerationTools;

  public ConstraintAuthoringTools(
      IliCompilerService compilerService,
      ConstraintCaseGenerationTools caseGenerationTools) {
    this.compilerService = compilerService;
    this.caseGenerationTools = caseGenerationTools;
  }

  /** Flat semantic node used by the MCP schema; child expressions are referenced by node ID. */
  public static class ExpressionNode {
    public String id;
    public String kind;
    public @Nullable String name;
    public @Nullable String operator;
    public @Nullable Object value;
    public @Nullable List<String> children;
  }

  @McpTool(
      name = "authorIliMandatoryConstraint",
      description = "Erzeugt einen INTERLIS Mandatory Constraint aus einer strukturierten semantischen Node-Liste und beweist ihn mit der bestehenden AST->IR->Coverage->Solver->Object-Graph->ilivalidator Pipeline. Knotenarten: ATTRIBUTE, PATH, NUMERIC, BOOLEAN, ENUM, TEXT, MTEXT, FUNCTION, DEFINED, NOT, AND, OR, IMPLIES, COMPARE. FUNCTION.name ist die stabile semanticId aus listConstraintFunctions, z.B. NUMERIC_ADD oder COLLECTION_SUM. PATH wird insbesondere fuer ATTRIBUTE_PATH-Argumente wie SUM verwendet. Das Tool kompiliert den Vorschlag mit ili2c, uebersetzt den kompilierten AST erneut in die typisierte ConstraintExpression-IR und liefert Witness-/Counterexample-/Boundary-Beweise des echten ilivalidators."
  )
  public Map<String, Object> authorIliMandatoryConstraint(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext ohne den zu erzeugenden Constraint", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Klassenkontext Model.Topic.Class", required = true) String context,
      @McpToolParam(description = "Technischer Name des zu erzeugenden Mandatory Constraints", required = true) String constraintName,
      @McpToolParam(description = "ID des Wurzelknotens der semantischen Expression", required = true) String rootNodeId,
      @McpToolParam(description = "Flache semantische Node-Liste. Jeder Knoten hat id, kind und je nach kind name/operator/value/children. COMPARE und IMPLIES haben zwei children; DEFINED/NOT eines; AND/OR mindestens eines; FUNCTION verwendet children als Argumente in Signaturreihenfolge.", required = true) List<ExpressionNode> nodes,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition", required = false) @Nullable String modelRepositories) {
    String normalizedContext;
    String normalizedName;
    ConstraintExpression.IliVersion version;
    RenderResult rendered;
    try {
      normalizedContext = requireContext(context);
      normalizedName = requireIdentifier(constraintName, "constraintName");
      version = iliVersion(modelText);
      rendered = render(rootNodeId, nodes, version);
    } catch (IllegalArgumentException ex) {
      return unavailable("INVALID_AUTHORING_SPEC", ex.getMessage(), null, null, null);
    }

    String constraintBlock = renderConstraintBlock(
        normalizedContext,
        normalizedName,
        rendered.expression());
    String proofModelText;
    try {
      proofModelText = insertConstraintBlock(modelText, normalizedContext, constraintBlock);
    } catch (IllegalArgumentException ex) {
      return unavailable(
          "CONSTRAINT_INSERTION_FAILED",
          ex.getMessage(),
          rendered.expression(),
          constraintBlock,
          rendered.requiredFunctionModels());
    }

    IliCompilerService.CompilationResult compilation = compilerService.compile(
        proofModelText,
        modelRepositories,
        "ili2c_constraint_authoring_");
    if (!compilation.valid() || compilation.transferDescription() == null) {
      Map<String, Object> result = unavailable(
          "GENERATED_CONSTRAINT_NOT_COMPILABLE",
          "The structured Mandatory proposal could not be compiled in the supplied model.",
          rendered.expression(),
          constraintBlock,
          rendered.requiredFunctionModels());
      result.put("compilerMessages", compilation.messages());
      return result;
    }

    Constraint compiledConstraint = findConstraint(compilation.transferDescription(), normalizedName);
    if (compiledConstraint == null) {
      return unavailable(
          "CONSTRAINT_LOOKUP_FAILED",
          "The generated Mandatory Constraint could not be resolved uniquely after compilation.",
          rendered.expression(),
          constraintBlock,
          rendered.requiredFunctionModels());
    }

    ConstraintAstTranslator.Translation translation;
    try {
      translation = ConstraintAstTranslator.translate(compiledConstraint);
    } catch (ConstraintAstTranslator.TranslationException ex) {
      return unavailable(
          ex.reasonCode(),
          ex.getMessage(),
          rendered.expression(),
          constraintBlock,
          rendered.requiredFunctionModels());
    }
    if (!normalizedContext.equals(translation.contextFqn())) {
      return unavailable(
          "CONTEXT_ROUND_TRIP_MISMATCH",
          "Compiled constraint context differs from the requested context: " + translation.contextFqn(),
          rendered.expression(),
          constraintBlock,
          rendered.requiredFunctionModels());
    }

    ConstraintExpression typedExpression = translation.expression();
    Map<String, Object> proof = caseGenerationTools.generateIliConstraintCases(
        proofModelText,
        normalizedName,
        modelRepositories);
    boolean verified = Boolean.TRUE.equals(proof.get("generationVerified"));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("generated", true);
    result.put("proofVerified", verified);
    result.put("constraintName", normalizedName);
    result.put("context", normalizedContext);
    result.put("iliVersion", version.text());
    result.put("constraintExpression", rendered.expression());
    result.put("typedCanonicalExpression", typedExpression.toInterlis(version));
    result.put("constraintBlock", constraintBlock);
    result.put("typedReferences", typedReferences(typedExpression));
    result.put("requiredFunctionModels", rendered.requiredFunctionModels());
    result.put("proof", proof);
    copyIfPresent(proof, result, "coverageGoalCount");
    copyIfPresent(proof, result, "coverageSolvedCount");
    copyIfPresent(proof, result, "coverageComplete");
    copyIfPresent(proof, result, "coverageUnsolved");
    if (!verified) {
      result.put("reasonCode", proof.getOrDefault("reasonCode", "MANDATORY_PROOF_NOT_VERIFIED"));
      result.put("reason", proof.getOrDefault(
          "reason",
          "The Mandatory Constraint compiled and translated to typed IR, but its generated proof was not fully verified."));
    }
    result.put("limitations", limitations());
    return result;
  }

  private RenderResult render(
      String rootNodeId,
      @Nullable List<ExpressionNode> nodes,
      ConstraintExpression.IliVersion version) {
    if (nodes == null || nodes.isEmpty()) {
      throw new IllegalArgumentException("nodes must contain at least one semantic expression node.");
    }
    String root = requireNodeId(rootNodeId, "rootNodeId");
    Map<String, ExpressionNode> byId = new LinkedHashMap<>();
    for (int i = 0; i < nodes.size(); i++) {
      ExpressionNode node = nodes.get(i);
      if (node == null) {
        throw new IllegalArgumentException("nodes[" + i + "] must not be null.");
      }
      String id = requireNodeId(node.id, "nodes[" + i + "].id");
      if (byId.putIfAbsent(id, node) != null) {
        throw new IllegalArgumentException("Duplicate expression node id: " + id);
      }
    }
    if (!byId.containsKey(root)) {
      throw new IllegalArgumentException("rootNodeId does not reference a supplied node: " + root);
    }

    RenderState state = new RenderState(byId, version);
    String expression = renderNode(root, state);
    if (state.rendered.size() != byId.size()) {
      Set<String> unused = new LinkedHashSet<>(byId.keySet());
      unused.removeAll(state.rendered.keySet());
      throw new IllegalArgumentException("Unused expression nodes are not allowed: " + unused);
    }
    return new RenderResult(expression, List.copyOf(state.requiredFunctionModels));
  }

  private String renderNode(String id, RenderState state) {
    String cached = state.rendered.get(id);
    if (cached != null) {
      return cached;
    }
    if (!state.visiting.add(id)) {
      throw new IllegalArgumentException("Expression graph contains a cycle at node: " + id);
    }
    ExpressionNode node = state.nodes.get(id);
    if (node == null) {
      throw new IllegalArgumentException("Expression node references unknown child id: " + id);
    }
    NodeKind kind = nodeKind(node.kind, id);
    List<String> children = normalizedChildren(node.children);
    String rendered = switch (kind) {
      case ATTRIBUTE -> {
        requireArity(kind, children, 0, 0, id);
        yield requireIdentifier(node.name, "ATTRIBUTE.name");
      }
      case PATH -> {
        requireArity(kind, children, 0, 0, id);
        yield requirePath(node.name);
      }
      case NUMERIC -> {
        requireArity(kind, children, 0, 0, id);
        yield numericLiteral(node.value).toInterlis(state.version);
      }
      case BOOLEAN -> {
        requireArity(kind, children, 0, 0, id);
        yield booleanLiteral(node.value).toInterlis(state.version);
      }
      case ENUM -> {
        requireArity(kind, children, 0, 0, id);
        yield enumLiteral(node.value).toInterlis(state.version);
      }
      case TEXT -> {
        requireArity(kind, children, 0, 0, id);
        yield textLiteral(node.value, ConstraintExpression.ScalarKind.TEXT).toInterlis(state.version);
      }
      case MTEXT -> {
        requireArity(kind, children, 0, 0, id);
        yield textLiteral(node.value, ConstraintExpression.ScalarKind.MTEXT).toInterlis(state.version);
      }
      case DEFINED -> {
        requireArity(kind, children, 1, 1, id);
        yield "DEFINED(" + renderNode(children.getFirst(), state) + ")";
      }
      case NOT -> {
        requireArity(kind, children, 1, 1, id);
        yield "NOT(" + renderNode(children.getFirst(), state) + ")";
      }
      case AND -> {
        requireArity(kind, children, 1, Integer.MAX_VALUE, id);
        yield "(" + join(children, state, " AND ") + ")";
      }
      case OR -> {
        requireArity(kind, children, 1, Integer.MAX_VALUE, id);
        yield "(" + join(children, state, " OR ") + ")";
      }
      case IMPLIES -> {
        requireArity(kind, children, 2, 2, id);
        yield "(" + renderNode(children.get(0), state)
            + " IMPLIES " + renderNode(children.get(1), state) + ")";
      }
      case COMPARE -> {
        requireArity(kind, children, 2, 2, id);
        String operator = node.operator == null ? "" : node.operator.trim();
        if (!COMPARISON_OPERATORS.contains(operator)) {
          throw new IllegalArgumentException("COMPARE node '" + id + "' has unsupported operator: " + operator);
        }
        yield "(" + renderNode(children.get(0), state)
            + " " + operator + " " + renderNode(children.get(1), state) + ")";
      }
      case FUNCTION -> renderFunction(id, node, children, state);
    };
    state.visiting.remove(id);
    state.rendered.put(id, rendered);
    return rendered;
  }

  private String renderFunction(
      String id,
      ExpressionNode node,
      List<String> children,
      RenderState state) {
    String semanticId = requireText(node.name, "FUNCTION.name");
    StandardFunctionRegistry.StandardFunction function = StandardFunctionRegistry.findBySemanticId(semanticId)
        .orElseThrow(() -> new IllegalArgumentException(
            "FUNCTION node '" + id + "' uses unknown standard semanticId: " + semanticId));
    if (children.size() != function.parameters().size()) {
      throw new IllegalArgumentException(
          "FUNCTION " + semanticId + " expects " + function.parameters().size()
              + " arguments, got " + children.size() + ".");
    }

    List<String> arguments = new ArrayList<>();
    for (int i = 0; i < children.size(); i++) {
      String childId = children.get(i);
      StandardFunctionRegistry.Parameter parameter = function.parameters().get(i);
      if (parameter.semantics() == ConstraintExpression.ArgumentSemantics.ATTRIBUTE_PATH) {
        ExpressionNode child = state.nodes.get(childId);
        if (child == null || nodeKind(child.kind, childId) != NodeKind.PATH) {
          throw new IllegalArgumentException(
              "FUNCTION " + semanticId + " argument " + i + " requires a PATH node.");
        }
        String path = renderNode(childId, state);
        arguments.add("\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
      } else {
        arguments.add(renderNode(childId, state));
      }
    }

    ConstraintExpression.SurfaceSyntax syntax = function.definition().syntax(state.version);
    if (syntax instanceof ConstraintExpression.InfixSyntax infix) {
      if (arguments.size() != 2) {
        throw new IllegalArgumentException("Infix function " + semanticId + " requires exactly two arguments.");
      }
      return "(" + arguments.get(0) + " " + infix.symbol() + " " + arguments.get(1) + ")";
    }
    if (syntax instanceof ConstraintExpression.FunctionSyntax) {
      state.requiredFunctionModels.add(function.modelName(state.version));
      return function.qualifiedName(state.version) + "(" + String.join(", ", arguments) + ")";
    }
    throw new IllegalArgumentException("Unsupported surface syntax for function: " + semanticId);
  }

  private String join(List<String> children, RenderState state, String delimiter) {
    List<String> rendered = children.stream().map(child -> renderNode(child, state)).toList();
    return String.join(delimiter, rendered);
  }

  private ConstraintExpression.NumericLiteral numericLiteral(@Nullable Object value) {
    if (value == null) {
      throw new IllegalArgumentException("NUMERIC.value is required.");
    }
    try {
      return new ConstraintExpression.NumericLiteral(new BigDecimal(String.valueOf(value)));
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("NUMERIC.value is not a number: " + value);
    }
  }

  private ConstraintExpression.BooleanLiteral booleanLiteral(@Nullable Object value) {
    if (value instanceof Boolean bool) {
      return new ConstraintExpression.BooleanLiteral(bool);
    }
    if (value instanceof String text && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
      return new ConstraintExpression.BooleanLiteral(Boolean.parseBoolean(text));
    }
    throw new IllegalArgumentException("BOOLEAN.value must be true or false.");
  }

  private ConstraintExpression.EnumLiteral enumLiteral(@Nullable Object value) {
    String text = requireText(value == null ? null : String.valueOf(value), "ENUM.value");
    if (text.startsWith("#")) {
      text = text.substring(1);
    }
    if (!ENUM_VALUE.matcher(text).matches()) {
      throw new IllegalArgumentException("ENUM.value must be an INTERLIS enumeration value.");
    }
    return new ConstraintExpression.EnumLiteral(text);
  }

  private ConstraintExpression.TextLiteral textLiteral(
      @Nullable Object value,
      ConstraintExpression.ScalarKind kind) {
    if (value == null) {
      throw new IllegalArgumentException(kind + ".value is required.");
    }
    return new ConstraintExpression.TextLiteral(String.valueOf(value), kind);
  }

  private List<Map<String, Object>> typedReferences(ConstraintExpression expression) {
    return expression.references().stream().map(reference -> {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("name", reference.name());
      result.put("referenceKind", reference.kind().name());
      result.put("scalarKind", reference.type().scalarKind().name());
      result.put("collection", reference.type().collection());
      result.put("nullable", reference.type().nullable());
      return result;
    }).toList();
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

  private @Nullable Constraint findConstraint(TransferDescription td, String requestedName) {
    List<Constraint> matches = new ArrayList<>();
    for (Model model : td.getModelsFromLastFile()) {
      collectConstraints(model, requestedName, matches);
    }
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  private void collectConstraints(Container<?> container, String requestedName, List<Constraint> sink) {
    Iterator<?> iterator = container.iterator();
    while (iterator.hasNext()) {
      Object child = iterator.next();
      if (child instanceof Constraint constraint) {
        if (requestedName.equals(constraint.getName())
            || requestedName.equals(constraint.getScopedName())) {
          sink.add(constraint);
        }
      } else if (child instanceof Container<?> nested) {
        collectConstraints(nested, requestedName, sink);
      }
    }
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

  private String requireContext(@Nullable String context) {
    String normalized = requireText(context, "context");
    String[] parts = normalized.split("\\.");
    if (parts.length != 3) {
      throw new IllegalArgumentException("context must have the form Model.Topic.Class.");
    }
    for (String part : parts) {
      requireIdentifier(part, "context part");
    }
    return normalized;
  }

  private String requirePath(@Nullable String path) {
    String normalized = requireText(path, "PATH.name");
    String[] parts = normalized.split("\\s*->\\s*", -1);
    if (parts.length < 2) {
      throw new IllegalArgumentException("PATH.name must contain at least one navigation step using ->.");
    }
    for (String part : parts) {
      requireIdentifier(part, "PATH segment");
    }
    return String.join("->", parts);
  }

  private String requireIdentifier(@Nullable String value, String label) {
    String normalized = requireText(value, label);
    if (!IDENTIFIER.matcher(normalized).matches()) {
      throw new IllegalArgumentException(label + " must be a simple INTERLIS identifier.");
    }
    return normalized;
  }

  private String requireNodeId(@Nullable String value, String label) {
    String normalized = requireText(value, label);
    if (!Pattern.matches("[A-Za-z0-9_.-]+", normalized)) {
      throw new IllegalArgumentException(label + " contains unsupported characters.");
    }
    return normalized;
  }

  private String requireText(@Nullable String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " is required.");
    }
    return value.trim();
  }

  private List<String> normalizedChildren(@Nullable List<String> children) {
    if (children == null) {
      return List.of();
    }
    return children.stream().map(child -> requireNodeId(child, "child node id")).toList();
  }

  private NodeKind nodeKind(@Nullable String value, String nodeId) {
    String normalized = requireText(value, "Node kind for " + nodeId).toUpperCase(Locale.ROOT);
    try {
      return NodeKind.valueOf(normalized);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unsupported expression node kind '" + value + "' at " + nodeId + ".");
    }
  }

  private void requireArity(
      NodeKind kind,
      List<String> children,
      int minimum,
      int maximum,
      String id) {
    if (children.size() < minimum || children.size() > maximum) {
      String expected = minimum == maximum ? String.valueOf(minimum) : minimum + ".." + maximum;
      throw new IllegalArgumentException(
          kind + " node '" + id + "' expects " + expected + " children, got " + children.size() + ".");
    }
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
      @Nullable List<String> requiredFunctionModels) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("generated", false);
    result.put("proofVerified", false);
    result.put("reasonCode", reasonCode);
    result.put("reason", reason);
    if (expression != null) {
      result.put("constraintExpression", expression);
    }
    if (constraintBlock != null) {
      result.put("constraintBlock", constraintBlock);
    }
    if (requiredFunctionModels != null) {
      result.put("requiredFunctionModels", requiredFunctionModels);
    }
    result.put("limitations", limitations());
    return result;
  }

  private List<String> limitations() {
    return List.of(
        "The authoring frontend creates MANDATORY CONSTRAINT only; other INTERLIS constraint kinds require their own constraint-level semantics.",
        "FUNCTION nodes intentionally accept only semantic IDs from the StandardFunctionRegistry; unsupported custom/model functions remain explicit rather than being rendered heuristically.",
        "Standard function models listed in requiredFunctionModels must be available/imported by the supplied model when their INTERLIS surface syntax requires a function call.",
        "Proof generation inherits the bounded solver/object-graph limits exposed by generateIliConstraintCases; unsolved coverage goals remain visible instead of being treated as proof.",
        "Geometry/AREA-specific authoring and synthesis remain outside the current priority scope.");
  }

  private enum NodeKind {
    ATTRIBUTE,
    PATH,
    NUMERIC,
    BOOLEAN,
    ENUM,
    TEXT,
    MTEXT,
    FUNCTION,
    DEFINED,
    NOT,
    AND,
    OR,
    IMPLIES,
    COMPARE
  }

  private static final class RenderState {
    private final Map<String, ExpressionNode> nodes;
    private final ConstraintExpression.IliVersion version;
    private final Map<String, String> rendered = new LinkedHashMap<>();
    private final Set<String> visiting = new LinkedHashSet<>();
    private final Set<String> requiredFunctionModels = new LinkedHashSet<>();

    private RenderState(Map<String, ExpressionNode> nodes, ConstraintExpression.IliVersion version) {
      this.nodes = nodes;
      this.version = version;
    }
  }

  private record RenderResult(String expression, List<String> requiredFunctionModels) {
  }
}
