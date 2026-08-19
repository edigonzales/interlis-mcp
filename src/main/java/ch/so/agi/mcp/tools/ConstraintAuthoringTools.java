package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.constraint.ConstraintExpression;
import ch.so.agi.mcp.constraint.ConstraintSourceEditService;
import ch.so.agi.mcp.constraint.SemanticConstraint;
import ch.so.agi.mcp.constraint.StandardFunctionRegistry;
import ch.so.agi.mcp.service.IliCompilerService;
import java.math.BigDecimal;
import java.util.ArrayList;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ConstraintAuthoringTools {

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
  private static final Pattern ENUM_VALUE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*");
  private static final Pattern INTERLIS_VERSION = Pattern.compile("(?m)^\\s*INTERLIS\\s+(2\\.3|2\\.4)\\s*;");
  private static final Set<String> COMPARISON_OPERATORS = Set.of("==", "!=", "<", "<=", ">", ">=");

  private final IliCompilerService compilerService;
  private final ConstraintCaseGenerationTools caseGenerationTools;
  private final ConstraintContextService contextService;
  private final ConstraintSourceEditService sourceEditService;

  @Autowired
  public ConstraintAuthoringTools(
      IliCompilerService compilerService,
      ConstraintCaseGenerationTools caseGenerationTools,
      ConstraintContextService contextService,
      ConstraintSourceEditService sourceEditService) {
    this.compilerService = compilerService;
    this.caseGenerationTools = caseGenerationTools;
    this.contextService = contextService;
    this.sourceEditService = sourceEditService;
  }

  /** Compatibility constructor used by existing focused tests. */
  public ConstraintAuthoringTools(
      IliCompilerService compilerService,
      ConstraintCaseGenerationTools caseGenerationTools) {
    this(
        compilerService,
        caseGenerationTools,
        new ConstraintContextService(compilerService),
        new ConstraintSourceEditService());
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
      description = "Erzeugt einen INTERLIS Mandatory Constraint aus einer strukturierten semantischen Node-Liste, fuegt ihn source-preserving in das bestehende Modell ein und beweist ihn mit der AST->IR->Coverage->Solver->Object-Graph->ilivalidator Pipeline. Knotenarten: ATTRIBUTE, PATH, NUMERIC, BOOLEAN, ENUM, TEXT, MTEXT, FUNCTION, DEFINED, NOT, AND, OR, IMPLIES, COMPARE. FUNCTION.name ist die stabile semanticId aus listConstraintFunctions. Das Modell wird im Erfolgsfall genau als Before und After kompiliert; Proof-Stufen verwenden danach denselben kompilierten After-Kontext weiter."
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

    IliCompilerService.CompilationResult beforeCompilation = compilerService.compile(
        modelText,
        modelRepositories,
        "ili2c_constraint_authoring_before_");
    if (!beforeCompilation.valid() || beforeCompilation.transferDescription() == null) {
      Map<String, Object> result = unavailable(
          "BEFORE_MODEL_NOT_COMPILABLE",
          "The supplied model must compile before a source-preserving constraint can be inserted.",
          rendered.expression(),
          null,
          rendered.requiredFunctionModels());
      result.put("compilerMessages", beforeCompilation.messages());
      return result;
    }

    String constraintBlock = renderConstraintBlock(
        normalizedContext,
        normalizedName,
        rendered.expression());
    ConstraintSourceEditService.PreparedInsertion insertion;
    try {
      insertion = sourceEditService.insertConstraintBlock(
          modelText,
          beforeCompilation,
          normalizedContext,
          constraintBlock);
    } catch (IllegalArgumentException ex) {
      return unavailable(
          "CONSTRAINT_INSERTION_FAILED",
          ex.getMessage(),
          rendered.expression(),
          constraintBlock,
          rendered.requiredFunctionModels());
    }

    String expectedConstraintFqn = normalizedContext + "." + normalizedName;
    ConstraintContextService.Resolution afterResolution = contextService.compileAndResolve(
        insertion.updatedModelText(),
        expectedConstraintFqn,
        modelRepositories,
        "ili2c_constraint_authoring_after_");
    if (!afterResolution.available()) {
      Map<String, Object> result = unavailable(
          afterResolution.compilation().valid()
              ? afterResolution.reasonCode()
              : "GENERATED_CONSTRAINT_NOT_COMPILABLE",
          afterResolution.compilation().valid()
              ? afterResolution.reason()
              : "The structured Mandatory proposal could not be compiled in the supplied model.",
          rendered.expression(),
          constraintBlock,
          rendered.requiredFunctionModels());
      result.put("compilerMessages", afterResolution.compilation().messages());
      result.put("candidateModelText", insertion.updatedModelText());
      result.put("sourceEdit", insertion.sourceEdit());
      return result;
    }

    CompiledConstraintContext compiled = afterResolution.context();
    if (!(compiled.semantics() instanceof SemanticConstraint.Mandatory mandatory)) {
      return unavailable(
          "CONSTRAINT_KIND_ROUND_TRIP_MISMATCH",
          "Compiled constraint is not a Mandatory Constraint: " + compiled.semantics().kind(),
          rendered.expression(),
          constraintBlock,
          rendered.requiredFunctionModels());
    }
    if (!normalizedContext.equals(mandatory.contextFqn())) {
      return unavailable(
          "CONTEXT_ROUND_TRIP_MISMATCH",
          "Compiled constraint context differs from the requested context: " + mandatory.contextFqn(),
          rendered.expression(),
          constraintBlock,
          rendered.requiredFunctionModels());
    }

    ConstraintExpression typedExpression = mandatory.condition();
    Map<String, Object> proof = caseGenerationTools.generateCompiledConstraintCases(compiled);
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
    result.put("updatedModelText", insertion.updatedModelText());
    result.put("sourceEdit", insertion.sourceEdit());
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

  @McpTool(
      name = "authorIliPlausibilityConstraint",
      description = "Erzeugt einen INTERLIS PLAUSIBILITY Constraint aus direction (AT_LEAST/>= oder AT_MOST/<=), percentage und derselben strukturierten semantischen Node-Liste wie authorIliMandatoryConstraint. Fuegt den Constraint source-preserving ein, kompiliert Before/After je genau einmal, prueft Direction/Percentage/Context ueber die constraint-level IR und beweist die Population-Semantik mit echten Mehrfachobjekt-XTF-Faellen und ilivalidator."
  )
  public Map<String, Object> authorIliPlausibilityConstraint(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext ohne den zu erzeugenden PLAUSIBILITY Constraint", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Klassenkontext Model.Topic.Class", required = true) String context,
      @McpToolParam(description = "Technischer Name des zu erzeugenden PLAUSIBILITY Constraints", required = true) String constraintName,
      @McpToolParam(description = "AT_LEAST bzw. >= oder AT_MOST bzw. <=", required = true) String direction,
      @McpToolParam(description = "Prozentgrenze zwischen 0 und 100", required = true) BigDecimal percentage,
      @McpToolParam(description = "ID des Wurzelknotens der semantischen Bedingung", required = true) String rootNodeId,
      @McpToolParam(description = "Flache semantische Node-Liste wie bei authorIliMandatoryConstraint. Die gerenderte Wurzel muss eine BOOLEAN-Bedingung ergeben.", required = true) List<ExpressionNode> nodes,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition", required = false) @Nullable String modelRepositories) {
    String normalizedContext;
    String normalizedName;
    SemanticConstraint.PlausibilityDirection normalizedDirection;
    BigDecimal normalizedPercentage;
    ConstraintExpression.IliVersion version;
    RenderResult rendered;
    try {
      normalizedContext = requireContext(context);
      normalizedName = requireIdentifier(constraintName, "constraintName");
      normalizedDirection = requirePlausibilityDirection(direction);
      normalizedPercentage = requirePercentage(percentage);
      version = iliVersion(modelText);
      rendered = render(rootNodeId, nodes, version);
    } catch (IllegalArgumentException ex) {
      return unavailable("INVALID_AUTHORING_SPEC", ex.getMessage(), null, null, null);
    }

    IliCompilerService.CompilationResult beforeCompilation = compilerService.compile(
        modelText,
        modelRepositories,
        "ili2c_plausibility_authoring_before_");
    if (!beforeCompilation.valid() || beforeCompilation.transferDescription() == null) {
      Map<String, Object> result = unavailable(
          "BEFORE_MODEL_NOT_COMPILABLE",
          "The supplied model must compile before a source-preserving PLAUSIBILITY constraint can be inserted.",
          rendered.expression(),
          null,
          rendered.requiredFunctionModels());
      result.put("compilerMessages", beforeCompilation.messages());
      return result;
    }

    String constraintBlock = renderPlausibilityConstraintBlock(
        normalizedContext,
        normalizedName,
        normalizedDirection,
        normalizedPercentage,
        rendered.expression());
    ConstraintSourceEditService.PreparedInsertion insertion;
    try {
      insertion = sourceEditService.insertConstraintBlock(
          modelText,
          beforeCompilation,
          normalizedContext,
          constraintBlock);
    } catch (IllegalArgumentException ex) {
      return unavailable(
          "CONSTRAINT_INSERTION_FAILED",
          ex.getMessage(),
          rendered.expression(),
          constraintBlock,
          rendered.requiredFunctionModels());
    }

    String expectedConstraintFqn = normalizedContext + "." + normalizedName;
    ConstraintContextService.Resolution afterResolution = contextService.compileAndResolve(
        insertion.updatedModelText(),
        expectedConstraintFqn,
        modelRepositories,
        "ili2c_plausibility_authoring_after_");
    if (!afterResolution.available()) {
      Map<String, Object> result = unavailable(
          afterResolution.compilation().valid()
              ? afterResolution.reasonCode()
              : "GENERATED_CONSTRAINT_NOT_COMPILABLE",
          afterResolution.compilation().valid()
              ? afterResolution.reason()
              : "The structured PLAUSIBILITY proposal could not be compiled in the supplied model.",
          rendered.expression(),
          constraintBlock,
          rendered.requiredFunctionModels());
      result.put("compilerMessages", afterResolution.compilation().messages());
      result.put("candidateModelText", insertion.updatedModelText());
      result.put("sourceEdit", insertion.sourceEdit());
      return result;
    }

    CompiledConstraintContext compiled = afterResolution.context();
    if (!(compiled.semantics() instanceof SemanticConstraint.Plausibility plausibility)) {
      return unavailable(
          "CONSTRAINT_KIND_ROUND_TRIP_MISMATCH",
          "Compiled constraint is not a PLAUSIBILITY Constraint: " + compiled.semantics().kind(),
          rendered.expression(),
          constraintBlock,
          rendered.requiredFunctionModels());
    }
    if (!normalizedContext.equals(plausibility.contextFqn())) {
      return unavailable(
          "CONTEXT_ROUND_TRIP_MISMATCH",
          "Compiled PLAUSIBILITY context differs from the requested context: " + plausibility.contextFqn(),
          rendered.expression(),
          constraintBlock,
          rendered.requiredFunctionModels());
    }
    if (normalizedDirection != plausibility.direction()) {
      return unavailable(
          "PLAUSIBILITY_DIRECTION_ROUND_TRIP_MISMATCH",
          "Compiled PLAUSIBILITY direction differs from the request: " + plausibility.direction(),
          rendered.expression(),
          constraintBlock,
          rendered.requiredFunctionModels());
    }
    if (normalizedPercentage.compareTo(plausibility.percentage()) != 0) {
      return unavailable(
          "PLAUSIBILITY_PERCENTAGE_ROUND_TRIP_MISMATCH",
          "Compiled PLAUSIBILITY percentage differs from the request: " + plausibility.percentage(),
          rendered.expression(),
          constraintBlock,
          rendered.requiredFunctionModels());
    }

    ConstraintExpression typedExpression = plausibility.condition();
    Map<String, Object> proof = caseGenerationTools.generateCompiledConstraintCases(compiled);
    boolean verified = Boolean.TRUE.equals(proof.get("generationVerified"));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("generated", true);
    result.put("proofVerified", verified);
    result.put("constraintName", normalizedName);
    result.put("context", normalizedContext);
    result.put("iliVersion", version.text());
    result.put("direction", normalizedDirection.name());
    result.put("percentage", normalizedPercentage.stripTrailingZeros().toPlainString());
    result.put("constraintExpression", rendered.expression());
    result.put("typedCanonicalExpression", typedExpression.toInterlis(version));
    result.put("constraintBlock", constraintBlock);
    result.put("updatedModelText", insertion.updatedModelText());
    result.put("sourceEdit", insertion.sourceEdit());
    result.put("typedReferences", typedReferences(typedExpression));
    result.put("requiredFunctionModels", rendered.requiredFunctionModels());
    result.put("proof", proof);
    copyIfPresent(proof, result, "coverageGoalCount");
    copyIfPresent(proof, result, "coverageSolvedCount");
    copyIfPresent(proof, result, "coverageComplete");
    copyIfPresent(proof, result, "coverageUnsolved");
    if (!verified) {
      result.put("reasonCode", proof.getOrDefault("reasonCode", "PLAUSIBILITY_PROOF_NOT_VERIFIED"));
      result.put("reason", proof.getOrDefault(
          "reason",
          "The PLAUSIBILITY Constraint compiled and translated to typed IR, but its generated population proof was not fully verified."));
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
    if (value instanceof String text
        && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
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

  private String renderPlausibilityConstraintBlock(
      String context,
      String constraintName,
      SemanticConstraint.PlausibilityDirection direction,
      BigDecimal percentage,
      String expression) {
    String operator = direction == SemanticConstraint.PlausibilityDirection.AT_LEAST ? ">=" : "<=";
    return "CONSTRAINTS OF " + context + " =\n"
        + "  !!@ name = \"" + constraintName + "\"\n"
        + "  CONSTRAINT\n"
        + "    " + operator + " " + percentage.stripTrailingZeros().toPlainString()
        + "% " + expression + ";\n"
        + "END;";
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

  private SemanticConstraint.PlausibilityDirection requirePlausibilityDirection(
      @Nullable String direction) {
    String normalized = requireText(direction, "direction").toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "AT_LEAST", ">=" -> SemanticConstraint.PlausibilityDirection.AT_LEAST;
      case "AT_MOST", "<=" -> SemanticConstraint.PlausibilityDirection.AT_MOST;
      default -> throw new IllegalArgumentException(
          "direction must be AT_LEAST/>= or AT_MOST/<=.");
    };
  }

  private BigDecimal requirePercentage(@Nullable BigDecimal percentage) {
    if (percentage == null) {
      throw new IllegalArgumentException("percentage is required.");
    }
    BigDecimal normalized = percentage.stripTrailingZeros();
    if (normalized.compareTo(BigDecimal.ZERO) < 0
        || normalized.compareTo(BigDecimal.valueOf(100)) > 0) {
      throw new IllegalArgumentException("percentage must be between 0 and 100.");
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
    result.put("reasonCode", reasonCode != null ? reasonCode : "CONSTRAINT_AUTHORING_UNAVAILABLE");
    result.put("reason", reason != null ? reason : "Constraint authoring is unavailable.");
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
        "The structured expression authoring frontend creates MANDATORY and PLAUSIBILITY constraints; UNIQUE, EXISTENCE and SET use or require their own constraint-level authoring semantics.",
        "PLAUSIBILITY authoring proves population percentages with generated multi-object fixtures; boundary populations are intentionally bounded and unsolved TRUE/FALSE condition branches remain visible as incomplete coverage.",
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
