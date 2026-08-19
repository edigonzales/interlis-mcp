package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.generator.Interlis2Generator;
import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.constraint.ConstraintCoveragePlanner;
import ch.so.agi.mcp.constraint.ConstraintExpression;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer;
import ch.so.agi.mcp.constraint.SemanticConstraint;
import ch.so.agi.mcp.service.IliCompilerService;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ConstraintCaseGenerationTools {

  private final ConstraintContextService contextService;

  @Autowired
  public ConstraintCaseGenerationTools(
      IliCompilerService compilerService,
      ConstraintContextService contextService) {
    this.contextService = contextService;
  }

  /** Compatibility constructor used by existing focused tests. */
  public ConstraintCaseGenerationTools(
      ConstraintReviewTools reviewTools,
      ConstraintTestTools testTools,
      IliCompilerService compilerService) {
    this(compilerService, new ConstraintContextService(compilerService));
  }

  @McpTool(
      name = "generateIliConstraintCases",
      description = "Erzeugt fuer unterstuetzte INTERLIS Mandatory Constraints automatisch modellbewusste Witness-, Counterexample- und Boundary-/Kategoriefaelle. Verwendet einen einmal kompilierten Constraint-Kontext fuer AST/semantische IR, Coverage Planner, Solver, Object-Graph-Synthese und Validator-Fixtures. Unterstuetzt damit insbesondere logische Kombinationen, NUMERIC/BOOLEAN/ENUM/TEXT, DEFINED, Standardfunktionen, mehrstufige skalare Pfade ueber Associations/Referenzattribute/Structures sowie SUM auf geeigneten mehrwertigen numerischen Pfaden, soweit IR, Solver und Synthesizer die Semantik abdecken."
  )
  public Map<String, Object> generateIliConstraintCases(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Constraint-Name oder vollqualifizierter Constraint-Name", required = true) String constraint,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition", required = false) @Nullable String modelRepositories) {
    ConstraintContextService.Resolution resolution = contextService.compileAndResolve(
        modelText,
        constraint,
        modelRepositories,
        "ili2c_constraint_cases_");
    if (!resolution.available()) {
      return unavailable(
          resolution.reasonCode() != null ? resolution.reasonCode() : "MODEL_OR_CONSTRAINT_REVIEW_UNAVAILABLE",
          resolution.reason() != null ? resolution.reason() : "The constraint could not be resolved.",
          null,
          resolution.compilation().messages());
    }
    return generateCompiledConstraintCases(resolution.context());
  }

  /**
   * Internal B2 entry point for callers that already own the compiled model and resolved constraint.
   * No ili2c compilation is performed by this method.
   */
  Map<String, Object> generateCompiledConstraintCases(CompiledConstraintContext context) {
    Objects.requireNonNull(context, "context");
    if (!(context.semantics() instanceof SemanticConstraint.Mandatory mandatory)) {
      return unavailable(
          "UNSUPPORTED_CONSTRAINT_KIND",
          "Automatic semantic generation currently supports MANDATORY CONSTRAINT only; got "
              + context.semantics().kind() + ".",
          context,
          context.compilation().messages());
    }

    ConstraintExpression expression = mandatory.condition();
    ConstraintModelSynthesizer.ModelBinding binding;
    try {
      binding = ConstraintModelSynthesizer.bind(
          context.transferDescription(), mandatory.contextFqn(), expression);
    } catch (IllegalArgumentException ex) {
      return unavailable("MODEL_BINDING_UNAVAILABLE", ex.getMessage(), context, context.compilation().messages());
    }

    ConstraintCoveragePlanner.CoveragePlan coverage = ConstraintCoveragePlanner.solve(expression, binding);
    if (coverage.cases().isEmpty()) {
      String reasonCode = coverage.unsolved().isEmpty()
          ? "NO_COVERAGE_CASES"
          : coverage.unsolved().getFirst().reasonCode();
      String reason = coverage.unsolved().isEmpty()
          ? "No semantic coverage cases could be derived for the constraint."
          : coverage.unsolved().getFirst().reason();
      return unavailable(reasonCode, reason, context, context.compilation().messages());
    }

    GeneratedCases generated;
    try {
      generated = generateCases(expression, mandatory.version(), binding, coverage);
    } catch (IllegalArgumentException ex) {
      return unavailable("OBJECT_GRAPH_SYNTHESIS_FAILED", ex.getMessage(), context, context.compilation().messages());
    }

    Map<String, Object> verification = verifyUsingCompiledContext(context, generated.cases());
    boolean verified = Boolean.TRUE.equals(verification.get("allPassed"));

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("automaticCasesAvailable", verified);
    response.put("automaticCasesGenerated", true);
    response.put("generationVerified", verified);
    response.put("pattern", "SEMANTIC_IR_COVERAGE");
    response.put("constraint", constraintSummary(context));
    response.put("context", contextSummary(context));
    response.put("generatedCases", generated.summaries());
    response.put("coverageGoalCount", coverage.cases().size() + coverage.unsolved().size());
    response.put("coverageSolvedCount", coverage.cases().size());
    response.put("coverageComplete", coverage.unsolved().isEmpty());
    if (!coverage.unsolved().isEmpty()) {
      response.put("coverageUnsolved", coverageUnsolved(coverage, mandatory.version()));
    }
    response.put("verification", verification);
    if (!verified) {
      response.put("reasonCode", "GENERATED_CASES_NOT_VERIFIED");
      response.put(
          "reason",
          "Semantic cases were generated, but the real validator did not confirm all expected outcomes.");
    }
    response.put("limitations", limitations());
    return response;
  }

  private Map<String, Object> verifyUsingCompiledContext(
      CompiledConstraintContext context,
      List<ConstraintTestTools.TestCase> cases) {
    IliCompilerService precompiledCompiler = new PrecompiledCompiler(context);
    ConstraintTestTools validator = new ConstraintTestTools(precompiledCompiler);
    return validator.testIliConstraint(
        context.modelText(),
        context.constraintFqn(),
        cases,
        context.modelRepositories());
  }

  private GeneratedCases generateCases(
      ConstraintExpression expression,
      ConstraintExpression.IliVersion version,
      ConstraintModelSynthesizer.ModelBinding binding,
      ConstraintCoveragePlanner.CoveragePlan coverage) {
    List<ConstraintTestTools.TestCase> cases = new ArrayList<>();
    List<Map<String, Object>> summaries = new ArrayList<>();
    int index = 1;
    for (ConstraintCoveragePlanner.CoverageCase coverageCase : coverage.cases()) {
      Map<String, Object> assignment = coverageCase.solution().assignment();
      boolean expectedValid = ConstraintExpressionEngine.evaluateConstraint(
          expression, ConstraintExpressionEngine.EvaluationContext.of(assignment));
      ConstraintModelSynthesizer.ObjectGraph graph = ConstraintModelSynthesizer.synthesize(
          binding, assignment, "auto_case_" + index);

      ConstraintTestTools.TestCase testCase = toTestCase(
          "automatic case " + index + " - " + coverageCase.goal().reason(),
          expectedValid,
          graph);
      cases.add(testCase);

      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("purpose", expectedValid ? "WITNESS" : "COUNTEREXAMPLE");
      summary.put("name", testCase.name);
      summary.put("reason", coverageCase.goal().reason());
      summary.put("source", coverageCase.goal().expression().toInterlis(version));
      summary.put("expectedConstraintValid", expectedValid);
      summary.put("values", summaryAssignment(assignment));
      summary.put("objectCount", graph.objects().size());
      summary.put("associationLinkCount", graph.links().size());
      addSingleReferenceCompatibility(summary, assignment);
      summaries.add(summary);
      index++;
    }
    return new GeneratedCases(cases, summaries);
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
      result.references = object.references();
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

  private Map<String, Object> summaryAssignment(Map<String, Object> assignment) {
    Map<String, Object> result = new LinkedHashMap<>();
    assignment.forEach((name, value) -> result.put(name, summaryValue(value)));
    return result;
  }

  private Object summaryValue(@Nullable Object value) {
    if (value == null || value == ConstraintExpressionEngine.Undefined.INSTANCE) {
      return "UNDEFINED";
    }
    if (value instanceof BigDecimal number) {
      return number.stripTrailingZeros().toPlainString();
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(this::summaryValue).toList();
    }
    return value;
  }

  private void addSingleReferenceCompatibility(
      Map<String, Object> summary,
      Map<String, Object> assignment) {
    if (assignment.size() != 1) {
      return;
    }
    Map.Entry<String, Object> entry = assignment.entrySet().iterator().next();
    summary.put("attribute", entry.getKey());
    Object value = entry.getValue();
    if (value == null || value == ConstraintExpressionEngine.Undefined.INSTANCE) {
      summary.put("attributeOmitted", true);
    } else {
      summary.put("value", summaryValue(value));
    }
  }

  private List<Map<String, Object>> coverageUnsolved(
      ConstraintCoveragePlanner.CoveragePlan coverage,
      ConstraintExpression.IliVersion version) {
    return coverage.unsolved().stream().map(solution -> {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("goal", solution.goal().kind().name());
      result.put("reason", solution.goal().reason());
      result.put("expression", solution.goal().expression().toInterlis(version));
      result.put("reasonCode", solution.reasonCode());
      result.put("solverReason", solution.reason());
      return result;
    }).toList();
  }

  private Map<String, Object> constraintSummary(CompiledConstraintContext context) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", context.constraint().getName());
    result.put("scopedName", context.constraint().getScopedName());
    result.put("kind", switch (context.semantics().kind()) {
      case MANDATORY -> "MANDATORY_CONSTRAINT";
      case UNIQUE -> "UNIQUENESS_CONSTRAINT";
      case EXISTENCE -> "EXISTENCE_CONSTRAINT";
      case PLAUSIBILITY -> "PLAUSIBILITY_CONSTRAINT";
      case SET -> "SET_CONSTRAINT";
    });
    result.put("sourceLine", context.constraint().getSourceLine());
    result.put("definitionText", definitionText(context));
    return result;
  }

  private String definitionText(CompiledConstraintContext context) {
    StringWriter writer = new StringWriter();
    Interlis2Generator generator =
        Interlis2Generator.generateElements(writer, context.transferDescription());
    generator.printConstraint(context.constraint(), true);
    return writer.toString().strip();
  }

  private Map<String, Object> contextSummary(CompiledConstraintContext context) {
    Object container = context.constraint().getContainer();
    return Map.of(
        "scopedName", context.contextFqn(),
        "kind", container != null ? container.getClass().getSimpleName() : "UNKNOWN",
        "pathContextAvailable", container instanceof ch.interlis.ili2c.metamodel.Viewable<?>);
  }

  private Map<String, Object> unavailable(
      String reasonCode,
      String reason,
      @Nullable CompiledConstraintContext context,
      List<?> compilerMessages) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("automaticCasesAvailable", false);
    response.put("automaticCasesGenerated", false);
    response.put("generationVerified", false);
    response.put("reasonCode", reasonCode);
    response.put("reason", reason);
    if (context != null) {
      response.put("constraint", constraintSummary(context));
      response.put("context", contextSummary(context));
    }
    if (compilerMessages != null && !compilerMessages.isEmpty()) {
      response.put("compilerMessages", compilerMessages);
    }
    response.put("limitations", limitations());
    return response;
  }

  private List<String> limitations() {
    return List.of(
        "Automatic semantic generation currently supports MANDATORY CONSTRAINT only.",
        "Multi-step scalar paths can mix association roles, reference attributes and structures. A path currently supports at most one multi-valued navigation step; cross-topic/cross-basket graphs, geometry and unsupported custom function semantics remain explicit limitations.",
        "The finite-domain solver is deliberately not complete; coverageComplete=false and coverageUnsolved expose goals that could not be solved.",
        "automaticCasesAvailable=true is returned only after every generated case passes the real ilivalidator with the expected outcome.");
  }

  private record GeneratedCases(
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries) {
  }

  /**
   * Adapter for the legacy explicit-case tool while B2 moves compilation ownership to the shared
   * context. It rejects any attempt to compile a different model, so hidden recompilation cannot
   * silently re-enter the pipeline.
   */
  private static final class PrecompiledCompiler extends IliCompilerService {
    private final CompiledConstraintContext context;

    private PrecompiledCompiler(CompiledConstraintContext context) {
      this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public CompilationResult compile(
        String modelText,
        String modelRepositories,
        String tempPrefix) {
      if (!context.modelText().equals(modelText)) {
        throw new IllegalStateException("Compiled constraint pipeline attempted to compile a different model text.");
      }
      String expectedRepos = normalize(context.modelRepositories());
      String actualRepos = normalize(modelRepositories);
      if (!Objects.equals(expectedRepos, actualRepos)) {
        throw new IllegalStateException("Compiled constraint pipeline changed modelRepositories.");
      }
      return context.compilation();
    }

    private String normalize(@Nullable String value) {
      return value == null || value.isBlank() ? null : value.trim();
    }
  }
}
