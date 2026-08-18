package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.generator.Interlis2Generator;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.AttributeRef;
import ch.interlis.ili2c.metamodel.Cardinality;
import ch.interlis.ili2c.metamodel.CompositionType;
import ch.interlis.ili2c.metamodel.Constant;
import ch.interlis.ili2c.metamodel.Constraint;
import ch.interlis.ili2c.metamodel.Container;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.EnumerationType;
import ch.interlis.ili2c.metamodel.Evaluable;
import ch.interlis.ili2c.metamodel.ExistenceConstraint;
import ch.interlis.ili2c.metamodel.Expression;
import ch.interlis.ili2c.metamodel.FormalArgument;
import ch.interlis.ili2c.metamodel.Function;
import ch.interlis.ili2c.metamodel.FunctionCall;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.ObjectPath;
import ch.interlis.ili2c.metamodel.PathEl;
import ch.interlis.ili2c.metamodel.PathElAbstractClassRole;
import ch.interlis.ili2c.metamodel.PathElAssocRole;
import ch.interlis.ili2c.metamodel.PathElRefAttr;
import ch.interlis.ili2c.metamodel.PlausibilityConstraint;
import ch.interlis.ili2c.metamodel.ReferenceType;
import ch.interlis.ili2c.metamodel.RoleDef;
import ch.interlis.ili2c.metamodel.SetConstraint;
import ch.interlis.ili2c.metamodel.TextType;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Type;
import ch.interlis.ili2c.metamodel.UniquenessConstraint;
import ch.interlis.ili2c.metamodel.Viewable;
import ch.so.agi.mcp.service.IliCompilerService;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ConstraintReviewTools {

  private final IliCompilerService compilerService;
  private final ConstraintKnowledgeTools knowledgeTools;

  public ConstraintReviewTools(IliCompilerService compilerService, ConstraintKnowledgeTools knowledgeTools) {
    this.compilerService = compilerService;
    this.knowledgeTools = knowledgeTools;
  }

  @McpTool(
      name = "reviewIliConstraint",
      description = "Erklaert und prueft einen bestehenden INTERLIS-Constraint aus einem vollstaendigen Modell. Liefert compilerbasierten AST, Kontext, referenzierte Elemente, Funktionen, String-Pfade, Typen und strukturelle Edge Cases. Erzeugt keine Testdaten, Witnesses oder Counterexamples."
  )
  public Map<String, Object> reviewIliConstraint(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Constraint-Name oder vollqualifizierter Constraint-Name", required = true) String constraint,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition", required = false) @Nullable String modelRepositories) {
    IliCompilerService.CompilationResult compilation =
        compilerService.compile(modelText, modelRepositories, "ili2c_constraint_review_");
    if (!compilation.valid() || compilation.transferDescription() == null) {
      return Map.of(
          "valid", false,
          "compilerValid", false,
          "messages", compilation.messages(),
          "reviewStatus", "UNAVAILABLE",
          "reason", "The model must compile before a constraint can be reviewed.",
          "limitations", limitations());
    }

    TransferDescription td = compilation.transferDescription();
    Constraint selected = findConstraint(td, constraint);
    if (selected == null) {
      throw new IllegalArgumentException("Constraint not found: " + constraint);
    }

    Element container = selected.getContainer();
    Viewable<?> viewableContext = container instanceof Viewable<?> viewable ? viewable : null;
    String contextName = container != null ? container.getScopedName() : "";
    String iliVersion = iliVersion(selected);
    ReviewAccumulator accumulator = new ReviewAccumulator(functionCatalog(iliVersion));

    Map<String, Object> ast = describeConstraint(selected, accumulator, viewableContext);
    resolveStringPaths(modelText, modelRepositories, viewableContext, accumulator);

    List<Map<String, Object>> findings = new ArrayList<>();
    for (String unsupported : accumulator.unsupportedAstNodes) {
      findings.add(finding(
          "WARNING",
          "UNSUPPORTED_AST_NODE",
          "AST node is exposed but not structurally decomposed: " + unsupported));
    }
    for (Map<String, Object> path : accumulator.paths.values()) {
      if (Boolean.FALSE.equals(path.get("valid"))) {
        findings.add(finding(
            "ERROR",
            "INVALID_ATTRIBUTE_PATH",
            "Attribute path could not be resolved: " + path.get("path")));
      }
    }
    for (Map<String, Object> function : accumulator.functions.values()) {
      if ("MODEL_FUNCTION".equals(function.get("origin"))) {
        findings.add(finding(
            "INFO",
            "MODEL_FUNCTION_RUNTIME_NOT_VERIFIED",
            "Runtime implementation is not proven by ili2c for function " + function.get("name") + "."));
      }
    }

    String reviewStatus = findings.stream().anyMatch(finding -> "ERROR".equals(finding.get("severity")))
        ? "ISSUES_FOUND"
        : accumulator.unsupportedAstNodes.isEmpty() ? "OK" : "PARTIAL";

    Map<String, Object> constraintInfo = new LinkedHashMap<>();
    constraintInfo.put("name", selected.getName());
    constraintInfo.put("scopedName", selected.getScopedName());
    constraintInfo.put("kind", constraintKind(selected));
    constraintInfo.put("sourceLine", selected.getSourceLine());
    constraintInfo.put("definitionText", definitionText(selected, td));

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("valid", true);
    response.put("compilerValid", true);
    response.put("messages", compilation.messages());
    response.put("reviewStatus", reviewStatus);
    response.put("iliVersion", iliVersion);
    response.put("constraint", constraintInfo);
    response.put("context", Map.of(
        "scopedName", contextName,
        "kind", container != null ? container.getClass().getSimpleName() : "UNKNOWN",
        "pathContextAvailable", viewableContext != null));
    response.put("ast", ast);
    response.put("astComplete", accumulator.unsupportedAstNodes.isEmpty());
    response.put("referencedElements", new ArrayList<>(accumulator.referencedElements.values()));
    response.put("functions", new ArrayList<>(accumulator.functions.values()));
    response.put("paths", new ArrayList<>(accumulator.paths.values()));
    response.put("types", new ArrayList<>(accumulator.types.values()));
    response.put("edgeCases", new ArrayList<>(accumulator.edgeCases.values()));
    response.put("findings", findings);
    response.put("limitations", limitations());
    return response;
  }

  private Map<String, Object> describeConstraint(
      Constraint constraint,
      ReviewAccumulator accumulator,
      @Nullable Viewable<?> context) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("kind", constraintKind(constraint));

    if (constraint instanceof PlausibilityConstraint plausibility) {
      root.put("percentage", plausibility.getPercentage());
      root.put("direction", plausibility.getDirection() == PlausibilityConstraint.DIRECTION_AT_LEAST
          ? "AT_LEAST"
          : "AT_MOST");
    }
    if (constraint instanceof SetConstraint setConstraint) {
      root.put("perBasket", setConstraint.perBasket());
      if (setConstraint.getPreCondition() != null) {
        root.put("preCondition", describeEvaluable(setConstraint.getPreCondition(), accumulator, context));
      }
    }
    if (constraint instanceof UniquenessConstraint uniqueness) {
      root.put("local", uniqueness.getLocal());
      root.put("perBasket", uniqueness.perBasket());
      if (uniqueness.getPreCondition() != null) {
        root.put("preCondition", describeEvaluable(uniqueness.getPreCondition(), accumulator, context));
      }
      if (uniqueness.getPrefix() != null) {
        root.put("prefix", describeEvaluable(uniqueness.getPrefix(), accumulator, context));
      }
      if (uniqueness.getElements() != null) {
        List<Map<String, Object>> uniqueElements = new ArrayList<>();
        for (ObjectPath path : uniqueness.getElements().getAttributes()) {
          uniqueElements.add(describeEvaluable(path, accumulator, context));
        }
        root.put("uniqueElements", uniqueElements);
      }
    }
    if (constraint instanceof ExistenceConstraint existence) {
      if (existence.getRestrictedAttribute() != null) {
        root.put("restrictedAttribute", describeEvaluable(existence.getRestrictedAttribute(), accumulator, context));
      }
      List<Map<String, Object>> requiredIn = new ArrayList<>();
      Iterator<ObjectPath> iterator = existence.iteratorRequiredIn();
      while (iterator.hasNext()) {
        requiredIn.add(describeEvaluable(iterator.next(), accumulator, context));
      }
      root.put("requiredIn", requiredIn);
    }
    if (constraint.getCondition() != null) {
      root.put("condition", describeEvaluable(constraint.getCondition(), accumulator, context));
    }
    return root;
  }

  private Map<String, Object> describeEvaluable(
      Evaluable evaluable,
      ReviewAccumulator accumulator,
      @Nullable Viewable<?> context) {
    Map<String, Object> node = new LinkedHashMap<>();
    Map<String, Object> type = describeType(evaluable.getType());
    node.put("type", type);
    accumulator.addType(type, evaluable.getSourceOfType());

    if (evaluable instanceof Expression.Disjunction expression) {
      node.put("kind", "OR");
      node.put("children", describeMany(expression.getDisjoined(), accumulator, context));
    } else if (evaluable instanceof Expression.Conjunction expression) {
      node.put("kind", "AND");
      node.put("children", describeMany(expression.getConjoined(), accumulator, context));
    } else if (evaluable instanceof Expression.Negation expression) {
      node.put("kind", "NOT");
      node.put("operand", describeEvaluable(expression.getNegated(), accumulator, context));
    } else if (evaluable instanceof Expression.Subexpression expression) {
      node.put("kind", "GROUP");
      node.put("expression", describeEvaluable(expression.getSubexpression(), accumulator, context));
    } else if (evaluable instanceof Expression.DefinedCheck expression) {
      node.put("kind", "DEFINED");
      node.put("argument", describeEvaluable(expression.getArgument(), accumulator, context));
    } else if (evaluable instanceof Expression.Equality expression) {
      binary(node, "==", expression.getLeft(), expression.getRight(), accumulator, context);
    } else if (evaluable instanceof Expression.Inequality expression) {
      binary(node, "!=", expression.getLeft(), expression.getRight(), accumulator, context);
    } else if (evaluable instanceof Expression.GreaterThan expression) {
      binary(node, ">", expression.getLeft(), expression.getRight(), accumulator, context);
    } else if (evaluable instanceof Expression.GreaterThanOrEqual expression) {
      binary(node, ">=", expression.getLeft(), expression.getRight(), accumulator, context);
    } else if (evaluable instanceof Expression.LessThan expression) {
      binary(node, "<", expression.getLeft(), expression.getRight(), accumulator, context);
    } else if (evaluable instanceof Expression.LessThanOrEqual expression) {
      binary(node, "<=", expression.getLeft(), expression.getRight(), accumulator, context);
    } else if (evaluable instanceof Expression.Implication expression) {
      binary(node, "IMPLIES", expression.getLeft(), expression.getRight(), accumulator, context);
    } else if (evaluable instanceof Expression.Addition expression) {
      binary(node, "+", expression.getLeft(), expression.getRight(), accumulator, context);
    } else if (evaluable instanceof Expression.Subtraction expression) {
      binary(node, "-", expression.getLeft(), expression.getRight(), accumulator, context);
    } else if (evaluable instanceof Expression.Multiplication expression) {
      binary(node, "*", expression.getLeft(), expression.getRight(), accumulator, context);
    } else if (evaluable instanceof Expression.Division expression) {
      binary(node, "/", expression.getLeft(), expression.getRight(), accumulator, context);
    } else if (evaluable instanceof FunctionCall functionCall) {
      describeFunctionCall(node, functionCall, accumulator, context);
    } else if (evaluable instanceof ObjectPath objectPath) {
      describeObjectPath(node, objectPath, accumulator, context);
    } else if (evaluable instanceof Constant constant) {
      describeConstant(node, constant);
    } else {
      node.put("kind", "UNSUPPORTED");
      node.put("metamodelType", evaluable.getClass().getName());
      node.put("text", evaluable.toString());
      accumulator.unsupportedAstNodes.add(evaluable.getClass().getName());
    }
    return node;
  }

  private List<Map<String, Object>> describeMany(
      Evaluable[] evaluables,
      ReviewAccumulator accumulator,
      @Nullable Viewable<?> context) {
    List<Map<String, Object>> children = new ArrayList<>();
    for (Evaluable evaluable : evaluables) {
      children.add(describeEvaluable(evaluable, accumulator, context));
    }
    return children;
  }

  private void binary(
      Map<String, Object> node,
      String operator,
      Evaluable left,
      Evaluable right,
      ReviewAccumulator accumulator,
      @Nullable Viewable<?> context) {
    node.put("kind", operator);
    node.put("left", describeEvaluable(left, accumulator, context));
    node.put("right", describeEvaluable(right, accumulator, context));
  }

  private void describeFunctionCall(
      Map<String, Object> node,
      FunctionCall call,
      ReviewAccumulator accumulator,
      @Nullable Viewable<?> context) {
    Function function = call.getFunction();
    String functionName = function.getScopedName();
    node.put("kind", "FUNCTION_CALL");
    node.put("function", functionName);

    Map<String, Object> catalogEntry = accumulator.functionCatalog.get(functionName);
    Map<String, Object> functionInfo = new LinkedHashMap<>();
    if (catalogEntry != null) {
      functionInfo.putAll(catalogEntry);
    } else {
      functionInfo.put("name", functionName);
      functionInfo.put("origin", "MODEL_FUNCTION");
      Element owner = function.getContainer();
      if (owner != null) {
        functionInfo.put("sourceModel", owner.getScopedName());
      }
      functionInfo.put("returns", describeType(function.getDomain()));
    }
    accumulator.addFunction(functionInfo);

    List<Map<String, Object>> parameterMetadata = catalogEntry != null
        ? castMapList(catalogEntry.get("parameters"))
        : List.of();
    FormalArgument[] formalArguments = function.getArguments();
    Evaluable[] arguments = call.getArguments();
    List<Map<String, Object>> argumentNodes = new ArrayList<>();
    for (int i = 0; i < arguments.length; i++) {
      Map<String, Object> argument = new LinkedHashMap<>();
      argument.put("index", i);
      Map<String, Object> parameter = i < parameterMetadata.size()
          ? parameterMetadata.get(i)
          : fallbackParameter(formalArguments, i);
      argument.put("parameter", parameter);
      argument.put("value", describeEvaluable(arguments[i], accumulator, context));
      argumentNodes.add(argument);

      if ("ATTRIBUTE_PATH".equals(parameter.get("semanticType")) && arguments[i] instanceof Constant.Text text) {
        accumulator.addStringPathRequest(functionName, String.valueOf(parameter.get("name")), text.getValue());
      }
    }
    node.put("arguments", argumentNodes);
  }

  private Map<String, Object> fallbackParameter(FormalArgument[] arguments, int index) {
    Map<String, Object> parameter = new LinkedHashMap<>();
    if (index < arguments.length) {
      parameter.put("name", arguments[index].getName());
      parameter.put("type", describeType(arguments[index].getType()));
    } else {
      parameter.put("name", "arg" + index);
    }
    parameter.put("semanticType", "VALUE");
    return parameter;
  }

  private void describeObjectPath(
      Map<String, Object> node,
      ObjectPath objectPath,
      ReviewAccumulator accumulator,
      @Nullable Viewable<?> context) {
    node.put("kind", "OBJECT_PATH");
    node.put("path", objectPath.toString());
    List<Map<String, Object>> steps = new ArrayList<>();
    boolean collection = false;
    for (int i = 0; i < objectPath.getPathElements().length; i++) {
      PathEl pathElement = objectPath.getPathElements()[i];
      Map<String, Object> step = new LinkedHashMap<>();
      step.put("index", i);
      if (pathElement instanceof PathElAssocRole rolePath) {
        collection |= describeRoleStep(step, rolePath.getRole(), accumulator);
      } else if (pathElement instanceof PathElAbstractClassRole rolePath) {
        collection |= describeRoleStep(step, rolePath.getRole(), accumulator);
      } else if (pathElement instanceof PathElRefAttr refPath) {
        describeAttributeStep(step, refPath.getAttr(), "REFERENCE_ATTRIBUTE", accumulator);
        if (refPath.getViewable() != null) {
          step.put("target", refPath.getViewable().getScopedName());
        }
      } else if (pathElement instanceof AttributeRef attributePath) {
        describeAttributeStep(step, attributePath.getAttr(), "ATTRIBUTE", accumulator);
      } else {
        step.put("kind", pathElement.getClass().getSimpleName());
        step.put("name", pathElement.getName());
        if (pathElement.getViewable() != null) {
          step.put("target", pathElement.getViewable().getScopedName());
        }
      }
      steps.add(step);
    }
    node.put("collection", collection);
    node.put("steps", steps);
    accumulator.addDirectPath(context, objectPath.toString(), collection, steps);
  }

  private boolean describeRoleStep(
      Map<String, Object> step,
      RoleDef role,
      ReviewAccumulator accumulator) {
    step.put("kind", "ROLE");
    step.put("name", role.getName());
    if (role.getDestination() != null) {
      step.put("target", role.getDestination().getScopedName());
    }
    Cardinality cardinality = role.getCardinality();
    boolean collection = false;
    if (cardinality != null) {
      step.put("cardinality", cardinality.toString());
      step.put("minimum", cardinality.getMinimum());
      step.put("maximum", cardinality.getMaximum() == Cardinality.UNBOUND ? "*" : cardinality.getMaximum());
      collection = cardinality.getMaximum() > 1;
    }
    step.put("collection", collection);
    accumulator.addReferencedElement("ROLE", role.getName(), role.getScopedName(), step);
    return collection;
  }

  private void describeAttributeStep(
      Map<String, Object> step,
      AttributeDef attribute,
      String kind,
      ReviewAccumulator accumulator) {
    Type type = attribute.getDomainResolvingAliases();
    step.put("kind", kind);
    step.put("name", attribute.getName());
    step.put("type", describeType(type));
    step.put("mandatory", type != null && type.isMandatoryConsideringAliases());
    step.put("collection", type instanceof CompositionType composition
        && composition.getCardinality() != null
        && composition.getCardinality().getMaximum() > 1);
    accumulator.addReferencedElement(kind, attribute.getName(), attribute.getScopedName(), step);
  }

  private void describeConstant(Map<String, Object> node, Constant constant) {
    if (constant instanceof Constant.Text text) {
      node.put("kind", "TEXT_LITERAL");
      node.put("value", text.getValue());
    } else if (constant instanceof Constant.Numeric numeric) {
      node.put("kind", "NUMERIC_LITERAL");
      node.put("value", numeric.getValue().toString());
      if (numeric.getUnit() != null) {
        node.put("unit", numeric.getUnit().getScopedName());
      }
    } else if (constant instanceof Constant.Enumeration enumeration) {
      node.put("kind", "ENUM_LITERAL");
      node.put("value", enumeration.toString());
    } else if (constant instanceof Constant.Undefined) {
      node.put("kind", "UNDEFINED");
    } else {
      node.put("kind", "CONSTANT");
      node.put("value", constant.toString());
    }
  }

  private void resolveStringPaths(
      String modelText,
      @Nullable String modelRepositories,
      @Nullable Viewable<?> context,
      ReviewAccumulator accumulator) {
    for (PathRequest request : accumulator.pathRequests.values()) {
      Map<String, Object> path = new LinkedHashMap<>();
      path.put("source", "FUNCTION_ATTRIBUTE_PATH");
      path.put("function", request.function());
      path.put("parameter", request.parameter());
      path.put("path", request.path());
      path.put("occurrences", request.occurrences());
      if (context == null) {
        path.put("valid", false);
        path.put("reason", "Constraint container is not a viewable path context.");
      } else {
        Map<String, Object> resolved = knowledgeTools.resolveConstraintPath(
            modelText, context.getScopedName(), request.path(), modelRepositories);
        path.putAll(resolved);
        addResolvedPathReferences(path, accumulator);
        addPathEdgeCases(path, accumulator);
      }
      accumulator.paths.put("FUNCTION|" + request.function() + "|" + request.parameter() + "|" + request.path(), path);
    }
  }

  private void addResolvedPathReferences(Map<String, Object> path, ReviewAccumulator accumulator) {
    for (Map<String, Object> step : castMapList(path.get("steps"))) {
      String kind = String.valueOf(step.getOrDefault("kind", "PATH_ELEMENT"));
      String name = String.valueOf(step.getOrDefault("name", ""));
      String key = "PATH|" + path.get("path") + "|" + step.get("index") + "|" + name;
      Map<String, Object> element = new LinkedHashMap<>();
      element.put("kind", kind);
      element.put("name", name);
      element.put("viaPath", path.get("path"));
      if (step.containsKey("target")) {
        element.put("target", step.get("target"));
      }
      if (step.containsKey("type")) {
        element.put("type", step.get("type"));
      }
      accumulator.referencedElements.putIfAbsent(key, element);
    }
  }

  private void addPathEdgeCases(Map<String, Object> path, ReviewAccumulator accumulator) {
    for (Map<String, Object> step : castMapList(path.get("steps"))) {
      Object minimum = step.get("minimum");
      Object maximum = step.get("maximum");
      if (minimum instanceof Number number && number.longValue() == 0) {
        accumulator.addEdgeCase(
            "OPTIONAL_NAVIGATION|" + path.get("path") + "|" + step.get("index"),
            "OPTIONAL_NAVIGATION",
            path.get("path"),
            "Path step " + step.get("name") + " has minimum cardinality 0.");
      }
      if ((maximum instanceof Number number && number.longValue() > 1) || "*".equals(maximum)) {
        accumulator.addEdgeCase(
            "MULTIPLE_TARGETS|" + path.get("path") + "|" + step.get("index"),
            "MULTIPLE_TARGETS",
            path.get("path"),
            "Path step " + step.get("name") + " can reach multiple target objects.");
      }
    }
  }

  private Constraint findConstraint(TransferDescription td, String requestedName) {
    List<Constraint> matches = new ArrayList<>();
    for (Model model : td.getModelsFromLastFile()) {
      collectConstraints(model, requestedName.trim(), matches);
    }
    if (matches.size() > 1) {
      throw new IllegalArgumentException("Constraint name is ambiguous: " + requestedName + "; use a fully qualified name.");
    }
    return matches.isEmpty() ? null : matches.getFirst();
  }

  private void collectConstraints(Container<?> container, String requestedName, List<Constraint> matches) {
    Iterator<?> iterator = container.iterator();
    while (iterator.hasNext()) {
      Object child = iterator.next();
      if (child instanceof Constraint constraint) {
        if (requestedName.equals(constraint.getScopedName()) || requestedName.equals(constraint.getName())) {
          matches.add(constraint);
        }
      } else if (child instanceof Container<?> nested) {
        collectConstraints(nested, requestedName, matches);
      }
    }
  }

  private String definitionText(Constraint constraint, TransferDescription td) {
    StringWriter writer = new StringWriter();
    Interlis2Generator generator = Interlis2Generator.generateElements(writer, td);
    generator.printConstraint(constraint, true);
    return writer.toString().strip();
  }

  private String iliVersion(Constraint constraint) {
    Element current = constraint;
    while (current != null) {
      if (current instanceof Model model && model.getIliVersion() != null && !model.getIliVersion().isBlank()) {
        return model.getIliVersion();
      }
      current = current.getContainer();
    }
    return "2.4";
  }

  private Map<String, Map<String, Object>> functionCatalog(String iliVersion) {
    Map<String, Map<String, Object>> result = new LinkedHashMap<>();
    Object raw = knowledgeTools.listConstraintFunctions(iliVersion).get("functions");
    for (Map<String, Object> function : castMapList(raw)) {
      result.put(String.valueOf(function.get("name")), function);
    }
    return result;
  }

  private String constraintKind(Constraint constraint) {
    return constraint.getClass().getSimpleName()
        .replace("Constraint", "_CONSTRAINT")
        .toUpperCase(Locale.ROOT);
  }

  private Map<String, Object> describeType(@Nullable Type type) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (type == null) {
      result.put("kind", "UNKNOWN");
      return result;
    }
    Type real = type.resolveAliases();
    if (real.isBoolean()) {
      result.put("kind", "BOOLEAN");
      result.put("typeText", "BOOLEAN");
    } else if (real instanceof NumericType numeric) {
      result.put("kind", "NUMERIC");
      result.put("typeText", numeric.getMinimum() != null && numeric.getMaximum() != null
          ? numeric.getMinimum() + ".." + numeric.getMaximum()
          : "NUMERIC");
      if (numeric.getUnit() != null) {
        result.put("unit", numeric.getUnit().getScopedName());
      }
    } else if (real instanceof TextType text) {
      String kind = text.isNormalized() ? "TEXT" : "MTEXT";
      result.put("kind", kind);
      result.put("typeText", text.getMaxLength() < 0 ? kind : kind + "*" + text.getMaxLength());
    } else if (real instanceof EnumerationType enumeration) {
      result.put("kind", "ENUM");
      result.put("values", enumeration.getValues());
    } else if (real instanceof CompositionType composition) {
      result.put("kind", "COMPOSITION");
      result.put("target", composition.getComponentType().getScopedName());
      result.put("cardinality", composition.getCardinality().toString());
    } else if (real instanceof ReferenceType reference) {
      result.put("kind", "REFERENCE");
      result.put("target", reference.getReferred().getScopedName());
    } else {
      result.put("kind", real.getClass().getSimpleName());
    }
    return result;
  }

  private Map<String, Object> finding(String severity, String code, String message) {
    return Map.of("severity", severity, "code", code, "message", message);
  }

  private List<String> limitations() {
    return List.of(
        "No test data is generated in this roadmap step.",
        "No witness or counterexample search is performed.",
        "Compiler validity and structural review do not prove that the constraint matches the intended business rule.",
        "Model-defined function runtime implementations are outside ili2c and therefore cannot be proven by this review.");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> castMapList(@Nullable Object value) {
    return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }

  private record PathRequest(String function, String parameter, String path, int occurrences) {
    private PathRequest incremented() {
      return new PathRequest(function, parameter, path, occurrences + 1);
    }
  }

  private static final class ReviewAccumulator {
    private final Map<String, Map<String, Object>> functionCatalog;
    private final Map<String, Map<String, Object>> referencedElements = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> functions = new LinkedHashMap<>();
    private final Map<String, PathRequest> pathRequests = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> paths = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> types = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> edgeCases = new LinkedHashMap<>();
    private final List<String> unsupportedAstNodes = new ArrayList<>();

    private ReviewAccumulator(Map<String, Map<String, Object>> functionCatalog) {
      this.functionCatalog = functionCatalog;
    }

    private void addFunction(Map<String, Object> function) {
      String name = String.valueOf(function.get("name"));
      Map<String, Object> existing = functions.get(name);
      if (existing == null) {
        Map<String, Object> copy = new LinkedHashMap<>(function);
        copy.put("callCount", 1);
        functions.put(name, copy);
      } else {
        existing.put("callCount", ((Number) existing.get("callCount")).intValue() + 1);
      }
      Object edgeCasesValue = function.get("edgeCases");
      if (edgeCasesValue instanceof List<?> list) {
        for (Object edgeCase : list) {
          String text = String.valueOf(edgeCase);
          addEdgeCase("FUNCTION|" + name + "|" + text, "FUNCTION_EDGE_CASE", name, text);
        }
      }
    }

    private void addStringPathRequest(String function, String parameter, String path) {
      String key = function + "|" + parameter + "|" + path;
      PathRequest request = pathRequests.get(key);
      pathRequests.put(key, request == null
          ? new PathRequest(function, parameter, path, 1)
          : request.incremented());
    }

    private void addDirectPath(
        @Nullable Viewable<?> context,
        String path,
        boolean collection,
        List<Map<String, Object>> steps) {
      String contextName = context != null ? context.getScopedName() : "";
      String key = "OBJECT|" + contextName + "|" + path;
      if (!paths.containsKey(key)) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source", "OBJECT_PATH");
        item.put("valid", true);
        item.put("context", contextName);
        item.put("path", path);
        item.put("collection", collection);
        item.put("steps", steps);
        item.put("occurrences", 1);
        paths.put(key, item);
      } else {
        Map<String, Object> existing = paths.get(key);
        existing.put("occurrences", ((Number) existing.get("occurrences")).intValue() + 1);
      }
    }

    private void addReferencedElement(
        String kind,
        String name,
        String scopedName,
        Map<String, Object> details) {
      Map<String, Object> element = new LinkedHashMap<>();
      element.put("kind", kind);
      element.put("name", name);
      element.put("scopedName", scopedName);
      if (details.containsKey("type")) {
        element.put("type", details.get("type"));
      }
      if (details.containsKey("cardinality")) {
        element.put("cardinality", details.get("cardinality"));
      }
      if (details.containsKey("target")) {
        element.put("target", details.get("target"));
      }
      referencedElements.putIfAbsent(kind + "|" + scopedName, element);
    }

    private void addType(Map<String, Object> type, @Nullable Element source) {
      Map<String, Object> item = new LinkedHashMap<>(type);
      if (source != null) {
        item.put("source", source.getScopedName());
      }
      String key = item.toString();
      types.putIfAbsent(key, item);
    }

    private void addEdgeCase(String key, String kind, Object subject, String description) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("kind", kind);
      item.put("subject", subject);
      item.put("description", description);
      edgeCases.putIfAbsent(key, item);
    }
  }
}
