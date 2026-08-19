package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.Constraint;
import ch.interlis.ili2c.metamodel.Container;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.so.agi.mcp.constraint.ConstraintAstTranslator;
import ch.so.agi.mcp.constraint.ConstraintCoveragePlanner;
import ch.so.agi.mcp.constraint.ConstraintExpression;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer;
import ch.so.agi.mcp.service.IliCompilerService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ConstraintCaseGenerationTools {

  private final ConstraintReviewTools reviewTools;
  private final ConstraintTestTools testTools;
  private final IliCompilerService compilerService;

  public ConstraintCaseGenerationTools(
      ConstraintReviewTools reviewTools,
      ConstraintTestTools testTools,
      IliCompilerService compilerService) {
    this.reviewTools = reviewTools;
    this.testTools = testTools;
    this.compilerService = compilerService;
  }

  @McpTool(
      name = "generateIliConstraintCases",
      description = "Erzeugt fuer unterstuetzte INTERLIS Mandatory Constraints automatisch modellbewusste Witness-, Counterexample- und Boundary-/Kategoriefaelle. Verwendet die gemeinsame Pipeline ili2c AST -> semantische IR -> Coverage Planner -> Solver -> Object-Graph-Synthese und beweist alle erzeugten Faelle mit testIliConstraint und dem echten ilivalidator. Unterstuetzt damit insbesondere logische Kombinationen, NUMERIC/BOOLEAN/ENUM/TEXT, DEFINED, Standardfunktionen, mehrstufige skalare Pfade ueber Associations/Referenzattribute/Structures sowie SUM auf geeigneten mehrwertigen numerischen Pfaden, soweit IR, Solver und Synthesizer die Semantik abdecken."
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

    IliCompilerService.CompilationResult compilation = compilerService.compile(
        modelText, modelRepositories, "ili2c_constraint_cases_");
    if (!compilation.valid() || compilation.transferDescription() == null) {
      return unavailable(
          "MODEL_COMPILATION_FAILED",
          "The model could not be compiled for semantic constraint case generation.",
          review);
    }

    Constraint compiledConstraint = findConstraint(compilation.transferDescription(), constraint);
    if (compiledConstraint == null) {
      return unavailable(
          "CONSTRAINT_LOOKUP_FAILED",
          "The reviewed constraint could not be resolved uniquely in the compiled model.",
          review);
    }

    ConstraintAstTranslator.Translation translation;
    try {
      translation = ConstraintAstTranslator.translate(compiledConstraint);
    } catch (ConstraintAstTranslator.TranslationException ex) {
      return unavailable(ex.reasonCode(), ex.getMessage(), review);
    }

    ConstraintExpression expression = translation.expression();
    ConstraintModelSynthesizer.ModelBinding binding;
    try {
      binding = ConstraintModelSynthesizer.bind(
          compilation.transferDescription(), translation.contextFqn(), expression);
    } catch (IllegalArgumentException ex) {
      return unavailable("MODEL_BINDING_UNAVAILABLE", ex.getMessage(), review);
    }

    ConstraintCoveragePlanner.CoveragePlan coverage = ConstraintCoveragePlanner.solve(expression, binding);
    if (coverage.cases().isEmpty()) {
      String reasonCode = coverage.unsolved().isEmpty()
          ? "NO_COVERAGE_CASES"
          : coverage.unsolved().getFirst().reasonCode();
      String reason = coverage.unsolved().isEmpty()
          ? "No semantic coverage cases could be derived for the constraint."
          : coverage.unsolved().getFirst().reason();
      return unavailable(reasonCode, reason, review);
    }

    GeneratedCases generated;
    try {
      generated = generateCases(expression, translation.version(), binding, coverage);
    } catch (IllegalArgumentException ex) {
      return unavailable("OBJECT_GRAPH_SYNTHESIS_FAILED", ex.getMessage(), review);
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
    response.put("pattern", "SEMANTIC_IR_COVERAGE");
    response.put("constraint", review.get("constraint"));
    response.put("context", review.get("context"));
    response.put("generatedCases", generated.summaries());
    response.put("coverageGoalCount", coverage.cases().size() + coverage.unsolved().size());
    response.put("coverageSolvedCount", coverage.cases().size());
    response.put("coverageComplete", coverage.unsolved().isEmpty());
    if (!coverage.unsolved().isEmpty()) {
      response.put("coverageUnsolved", coverageUnsolved(coverage, translation.version()));
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
        "Automatic semantic generation currently supports MANDATORY CONSTRAINT only.",
        "Multi-step scalar paths can mix association roles, reference attributes and structures. A path currently supports at most one multi-valued navigation step; cross-topic/cross-basket graphs, geometry and unsupported custom function semantics remain explicit limitations.",
        "The finite-domain solver is deliberately not complete; coverageComplete=false and coverageUnsolved expose goals that could not be solved.",
        "automaticCasesAvailable=true is returned only after every generated case passes testIliConstraint with the expected outcome using the real ilivalidator.");
  }

  private record GeneratedCases(
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries) {
  }
}
