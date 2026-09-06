package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.generator.Interlis2Generator;
import ch.interlis.ili2c.metamodel.ExistenceConstraint;
import ch.interlis.ili2c.metamodel.AttributeRef;
import ch.interlis.ili2c.metamodel.MultiAreaType;
import ch.interlis.ili2c.metamodel.MultiCoordType;
import ch.interlis.ili2c.metamodel.MultiPolylineType;
import ch.interlis.ili2c.metamodel.MultiSurfaceType;
import ch.interlis.ili2c.metamodel.PathElRefAttr;
import ch.interlis.ili2c.metamodel.Type;
import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.constraint.ConstraintCoveragePlanner;
import ch.so.agi.mcp.constraint.ConstraintExpression;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer;
import ch.so.agi.mcp.constraint.SemanticConstraint;
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
import org.springframework.stereotype.Component;

@Component
public class ConstraintCaseGenerationTools {

  private final ConstraintContextService contextService;
  private final ConstraintTestTools testTools;

  public ConstraintCaseGenerationTools(
      ConstraintContextService contextService,
      ConstraintTestTools testTools) {
    this.contextService = contextService;
    this.testTools = testTools;
  }

  @McpTool(
      name = "generateIliConstraintCases",
      description = "Erzeugt fuer INTERLIS Mandatory-, UNIQUE-, EXISTENCE-, PLAUSIBILITY- und SET-Constraints modellbewusste Witness-, Counterexample-, Boundary- und Scope-Faelle. Verwendet einen einmal kompilierten Constraint-Kontext fuer AST/semantische IR, Solver, Object-Graph-Synthese, TypedValueFixtureFactory, NavigationGraphSynthesizer und Validator-Fixtures. UNIQUE prueft GLOBAL/WHERE/(BASKET)/LOCAL sowie direkte REFERENCE-, STRUCTURE-/COMPOSITION- und Geometrieschlüssel. EXISTENCE prueft skalare, Struktur-, REFERENCE-, COORD-, Linien-, Flaechen- und Multigeometriewerte; Validatorgrenzen werden mit Safety-Reason-Codes zurückgehalten. PLAUSIBILITY erzeugt echte Populationen an der Prozentgrenze. SET unterstützt OBJECT_COUNT einschließlich objectCount(ALL), navigierte Objektmengen, boolesche Ausdrücke und Scope-Semantik, soweit alles materialisierbar ist. Alle freigegebenen Faelle sind vom realen ilivalidator bestätigt.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true)
  )
  public Map<String, Object> generateIliConstraintCases(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Constraint-Name oder vollqualifizierter Constraint-Name", required = true) String constraint) {
    ConstraintContextService.Resolution resolution = contextService.compileAndResolve(
        modelText,
        constraint,
        null,
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
   * Internal entry point for callers that already own the compiled model and resolved constraint.
   * No ili2c compilation is performed by this method.
   */
  public Map<String, Object> generateCompiledConstraintCases(CompiledConstraintContext context) {
    Objects.requireNonNull(context, "context");
    if (context.semantics() instanceof SemanticConstraint.Mandatory mandatory) {
      return generateMandatoryConstraintCases(context, mandatory);
    }
    if (context.semantics() instanceof SemanticConstraint.Unique unique) {
      return generateUniqueConstraintCases(context, unique);
    }
    if (context.semantics() instanceof SemanticConstraint.Existence existence) {
      return generateExistenceConstraintCases(context, existence);
    }
    if (context.semantics() instanceof SemanticConstraint.Plausibility plausibility) {
      return generatePlausibilityConstraintCases(context, plausibility);
    }
    if (context.semantics() instanceof SemanticConstraint.Set set) {
      return generateSetConstraintCases(context, set);
    }
    return unavailable(
        "UNSUPPORTED_CONSTRAINT_KIND",
        "Automatic semantic generation supports the five INTERLIS constraint kinds for their documented proof-capable subsets; got "
            + context.semantics().kind() + ".",
        context,
        context.compilation().messages());
  }

  private Map<String, Object> generateMandatoryConstraintCases(
      CompiledConstraintContext context,
      SemanticConstraint.Mandatory mandatory) {
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
      addVerificationFailure(
          response,
          verification,
          "GENERATED_CASES_NOT_VERIFIED",
          "Semantic cases were generated, but the real validator did not confirm all expected outcomes.");
    }
    response.put("limitations", limitations());
    return response;
  }

  private Map<String, Object> generateUniqueConstraintCases(
      CompiledConstraintContext context,
      SemanticConstraint.Unique unique) {
    UniqueConstraintCasePlanner.Plan plan;
    try {
      plan = UniqueConstraintCasePlanner.plan(context, unique);
    } catch (IllegalArgumentException ex) {
      return unavailable(
          "UNIQUE_PROOF_PLANNING_FAILED",
          ex.getMessage(),
          context,
          context.compilation().messages());
    }

    if (plan.cases().isEmpty()) {
      Map<String, Object> first = plan.unsolved().isEmpty() ? Map.of() : plan.unsolved().getFirst();
      return unavailable(
          String.valueOf(first.getOrDefault("reasonCode", "NO_UNIQUE_PROOF_CASES")),
          String.valueOf(first.getOrDefault(
              "reason",
              "No validator-backed UNIQUE proof case could be synthesized for this model shape.")),
          context,
          context.compilation().messages());
    }

    Map<String, Object> verification = verifyUsingCompiledContext(context, plan.cases());
    boolean verified = Boolean.TRUE.equals(verification.get("allPassed"));

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("automaticCasesAvailable", verified);
    response.put("automaticCasesGenerated", true);
    response.put("generationVerified", verified);
    response.put("pattern", "UNIQUE_SEMANTIC_PROOF");
    response.put("constraint", constraintSummary(context));
    response.put("context", contextSummary(context));
    response.put("generatedCases", plan.summaries());
    response.put("coverageGoalCount", plan.goalCount());
    response.put("coverageSolvedCount", plan.cases().size());
    response.put("coverageComplete", plan.complete());
    if (!plan.unsolved().isEmpty()) {
      response.put("coverageUnsolved", plan.unsolved());
    }
    response.put("verification", verification);
    if (!verified) {
      addVerificationFailure(
          response,
          verification,
          "GENERATED_CASES_NOT_VERIFIED",
          "UNIQUE proof cases were generated, but the real validator did not confirm all expected outcomes.");
    }
    response.put("limitations", limitations());
    return response;
  }

  private Map<String, Object> generateExistenceConstraintCases(
      CompiledConstraintContext context,
      SemanticConstraint.Existence existence) {
    ExistenceConstraintCasePlanner.Plan plan;
    try {
      plan = ExistenceConstraintCasePlanner.plan(context, existence);
    } catch (IllegalArgumentException ex) {
      return unavailable(
          "EXISTENCE_PROOF_PLANNING_FAILED",
          ex.getMessage(),
          context,
          context.compilation().messages());
    }

    if (plan.cases().isEmpty()) {
      Map<String, Object> first = plan.unsolved().isEmpty() ? Map.of() : plan.unsolved().getFirst();
      return unavailable(
          String.valueOf(first.getOrDefault("reasonCode", "NO_EXISTENCE_PROOF_CASES")),
          String.valueOf(first.getOrDefault(
              "reason",
              "No validator-backed EXISTENCE proof case could be synthesized for this model shape.")),
          context,
          context.compilation().messages());
    }

    Map<String, Object> verification = verifyUsingCompiledContext(context, plan.cases());
    boolean verified = Boolean.TRUE.equals(verification.get("allPassed"));

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("automaticCasesAvailable", verified);
    response.put("automaticCasesGenerated", true);
    response.put("generationVerified", verified);
    response.put("pattern", "EXISTENCE_SEMANTIC_PROOF");
    response.put("constraint", constraintSummary(context));
    response.put("context", contextSummary(context));
    response.put("generatedCases", plan.summaries());
    response.put("coverageGoalCount", plan.goalCount());
    response.put("coverageSolvedCount", plan.cases().size());
    response.put("coverageComplete", plan.complete());
    if (!plan.unsolved().isEmpty()) {
      response.put("coverageUnsolved", plan.unsolved());
    }
    response.put("verification", verification);
    if (!verified) {
      Map<String, Object> fixtureFailure = firstFixturePreparationFailure(verification);
      boolean referenceEquality = context.constraint() instanceof ExistenceConstraint raw
          && raw.getRestrictedAttribute() != null
          && raw.getRestrictedAttribute().getLastPathEl() instanceof PathElRefAttr;
      boolean multigeometryEquality = context.constraint() instanceof ExistenceConstraint raw
          && raw.getRestrictedAttribute() != null
          && raw.getRestrictedAttribute().getLastPathEl() instanceof AttributeRef ref
          && isMultigeometry(ref.getAttr().getDomainOrDerivedDomain());
      response.put("reasonCode", fixtureFailure != null
          ? fixtureFailure.get("fixturePreparationReasonCode")
          : referenceEquality
          ? "REFERENCE_EQUALITY_VALIDATOR_FAILURE"
          : multigeometryEquality
              ? "GEOMETRY_EQUALITY_VALIDATOR_FAILURE"
              : "GENERATED_CASES_NOT_VERIFIED");
      response.put("reason", fixtureFailure != null
          ? fixtureFailure.get("reason")
          : referenceEquality
          ? "The installed ilivalidator cannot execute REFERENCE-valued EXISTENCE equality without an internal error; the OID fixtures remain unproved."
          : multigeometryEquality
              ? "The installed ilivalidator cannot execute multigeometry-valued EXISTENCE equality without an internal error; the valid fixtures remain unproved."
              : "EXISTENCE proof cases were generated, but the real validator did not confirm all expected outcomes.");
      if (fixtureFailure != null) {
        response.put("proofIncomplete", true);
      }
    }
    response.put("limitations", limitations());
    return response;
  }

  private Map<String, Object> generatePlausibilityConstraintCases(
      CompiledConstraintContext context,
      SemanticConstraint.Plausibility plausibility) {
    PlausibilityConstraintCasePlanner.Plan plan;
    try {
      plan = PlausibilityConstraintCasePlanner.plan(context, plausibility);
    } catch (IllegalArgumentException ex) {
      return unavailable(
          "PLAUSIBILITY_PROOF_PLANNING_FAILED",
          ex.getMessage(),
          context,
          context.compilation().messages());
    }

    if (plan.cases().isEmpty()) {
      Map<String, Object> first = plan.unsolved().isEmpty() ? Map.of() : plan.unsolved().getFirst();
      return unavailable(
          String.valueOf(first.getOrDefault("reasonCode", "NO_PLAUSIBILITY_PROOF_CASES")),
          String.valueOf(first.getOrDefault(
              "reason",
              "No validator-backed PLAUSIBILITY population case could be synthesized for this model shape.")),
          context,
          context.compilation().messages());
    }

    Map<String, Object> verification = verifyUsingCompiledContext(context, plan.cases());
    boolean verified = Boolean.TRUE.equals(verification.get("allPassed"));

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("automaticCasesAvailable", verified);
    response.put("automaticCasesGenerated", true);
    response.put("generationVerified", verified);
    response.put("pattern", "PLAUSIBILITY_POPULATION_PROOF");
    response.put("constraint", constraintSummary(context));
    response.put("context", contextSummary(context));
    response.put("plausibility", Map.of(
        "direction", plausibility.direction().name(),
        "percentage", plausibility.percentage().stripTrailingZeros().toPlainString(),
        "condition", plausibility.condition().toInterlis(plausibility.version())));
    response.put("generatedCases", plan.summaries());
    response.put("coverageGoalCount", plan.goalCount());
    response.put("coverageSolvedCount", plan.cases().size());
    response.put("coverageComplete", plan.complete());
    if (!plan.unsolved().isEmpty()) {
      response.put("coverageUnsolved", plan.unsolved());
    }
    response.put("verification", verification);
    if (!verified) {
      addVerificationFailure(
          response,
          verification,
          "GENERATED_CASES_NOT_VERIFIED",
          "PLAUSIBILITY population cases were generated, but the real validator did not confirm all expected outcomes.");
    }
    response.put("limitations", limitations());
    return response;
  }

  private Map<String, Object> generateSetConstraintCases(
      CompiledConstraintContext context,
      SemanticConstraint.Set set) {
    SetConstraintCasePlanner.Plan plan;
    try {
      plan = SetConstraintCasePlanner.plan(context, set);
    } catch (IllegalArgumentException ex) {
      return unavailable(
          "SET_PROOF_PLANNING_FAILED",
          ex.getMessage(),
          context,
          context.compilation().messages());
    }

    if (plan.cases().isEmpty()) {
      Map<String, Object> first = plan.unsolved().isEmpty() ? Map.of() : plan.unsolved().getFirst();
      return unavailable(
          String.valueOf(first.getOrDefault("reasonCode", "NO_SET_PROOF_CASES")),
          String.valueOf(first.getOrDefault(
              "reason",
              "No validator-backed SET objectCount proof case could be synthesized for this model shape.")),
          context,
          context.compilation().messages());
    }

    Map<String, Object> verification = verifyUsingCompiledContext(context, plan.cases());
    boolean verified = Boolean.TRUE.equals(verification.get("allPassed"));

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("automaticCasesAvailable", verified);
    response.put("automaticCasesGenerated", true);
    response.put("generationVerified", verified);
    response.put("pattern", "SET_OBJECT_COUNT_PROOF");
    response.put("constraint", constraintSummary(context));
    response.put("context", contextSummary(context));
    response.put("set", setSummary(set));
    response.put("generatedCases", plan.summaries());
    response.put("coverageGoalCount", plan.goalCount());
    response.put("coverageSolvedCount", plan.cases().size());
    response.put("coverageComplete", plan.complete());
    if (!plan.unsolved().isEmpty()) {
      response.put("coverageUnsolved", plan.unsolved());
    }
    response.put("verification", verification);
    if (!verified) {
      addVerificationFailure(
          response,
          verification,
          "GENERATED_CASES_NOT_VERIFIED",
          "SET objectCount cases were generated, but the real validator did not confirm all expected outcomes.");
    }
    response.put("limitations", limitations());
    return response;
  }

  private Map<String, Object> setSummary(SemanticConstraint.Set set) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("perBasket", set.perBasket());
    result.put("wherePresent", set.preCondition() != null);
    if (set.preCondition() != null) {
      result.put("where", set.preCondition().toInterlis(set.version()));
    }
    result.put("conditionKind", set.condition().getClass().getSimpleName());
    if (set.condition() instanceof SemanticConstraint.ObjectCountSetCondition objectCount) {
      result.put("operator", switch (objectCount.operator()) {
        case EQ -> "==";
        case NE -> "!=";
        case LT -> "<";
        case LE -> "<=";
        case GT -> ">";
        case GE -> ">=";
      });
      result.put("threshold", objectCount.threshold().stripTrailingZeros().toPlainString());
      if (objectCount.objects() instanceof SemanticConstraint.AllObjects all) {
        result.put("objectSet", "ALL");
        result.put("allContextFqn", all.contextFqn());
        if (all.baseFqn() != null) {
          result.put("allBaseFqn", all.baseFqn());
        }
        if (!all.restrictedToFqns().isEmpty()) {
          result.put("allRestrictedToFqns", all.restrictedToFqns());
        }
      } else if (objectCount.objects() instanceof SemanticConstraint.NavigatedObjects navigated) {
        result.put("objectSet", "PATH");
        result.put("objectPath", navigated.path().path());
      }
    }
    return Map.copyOf(result);
  }

  private Map<String, Object> verifyUsingCompiledContext(
      CompiledConstraintContext context,
      List<ConstraintTestTools.TestCase> cases) {
    return testTools.testCompiledConstraint(context, cases);
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

  private void addVerificationFailure(
      Map<String, Object> response,
      Map<String, Object> verification,
      String defaultReasonCode,
      String defaultReason) {
    Map<String, Object> fixtureFailure = firstFixturePreparationFailure(verification);
    if (fixtureFailure == null) {
      response.put("reasonCode", defaultReasonCode);
      response.put("reason", defaultReason);
      return;
    }
    response.put("reasonCode", fixtureFailure.get("fixturePreparationReasonCode"));
    response.put("reason", fixtureFailure.get("reason"));
    response.put("proofIncomplete", true);
  }

  private @Nullable Map<String, Object> firstFixturePreparationFailure(
      Map<String, Object> verification) {
    Object rawCases = verification.get("cases");
    if (!(rawCases instanceof List<?> cases)) {
      return null;
    }
    for (Object rawCase : cases) {
      if (rawCase instanceof Map<?, ?> map
          && map.get("fixturePreparationReasonCode") != null) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          if (entry.getKey() instanceof String key) {
            result.put(key, entry.getValue());
          }
        }
        return result;
      }
    }
    return null;
  }

  private List<String> limitations() {
    return List.of(
        "Automatic semantic generation covers all five INTERLIS constraint kinds. SET supports OBJECT_COUNT over ALL and a typed navigated object path plus boolean expressions; every unmaterializable route remains explicit coverageUnsolved.",
        "SET preserves ili2c base/RESTRICTION and polymorphy metadata. Geometry-aware SET functions such as INTERLIS.areAreas/areAreas2 remain unsupported unless executable semantics are registered.",
        "SET WHERE and navigated object-set graphs are merged only when cardinalities and all concrete target routes can be synthesized without changing the population silently.",
        "PLAUSIBILITY proof uses the real population semantics: condition TRUE and validator skipEvaluation count as successful members, and successful/total*100 is compared with the declared >= or <= threshold. Boundary populations are capped at 20 context objects per generated case.",
        "PLAUSIBILITY population synthesis requires each generated condition member graph to contain exactly one object of the constraint context so the percentage denominator cannot change silently; unsupported graph shapes remain coverageUnsolved.",
        "EXISTENCE supports scalar NUMERIC, BOOLEAN, ENUM, TEXT and MTEXT plus direct STRUCTURE/COMPOSITION, REFERENCE-OID, COORD, POLYLINE, SURFACE, AREA and INTERLIS-2.4-Multigeometrie-Fixtures.",
        "The installed ilivalidator currently fails while comparing REFERENCE and multigeometry EXISTENCE values. Such valid fixtures are withheld with REFERENCE_EQUALITY_VALIDATOR_FAILURE or GEOMETRY_EQUALITY_VALIDATOR_FAILURE.",
        "Navigated non-scalar EXISTENCE paths are reported as unsupported instead of approximated.",
        "Global UNIQUE supports scalar/navigated keys plus direct references, structures, COORD, lines, surfaces and multigeometries. AREA duplicates that cannot isolate UNIQUE from topology are withheld.",
        "LOCAL UNIQUE proof currently requires a direct structure/composition prefix and direct scalar member keys. Navigated LOCAL member keys are reported as unsolved rather than approximated.",
        "UNIQUE WHERE uses the finite-domain expression solver. If the predicate cannot be solved both true and false, or the false branch cannot preserve the same key, coverageComplete=false exposes the missing proof goal.",
        "The finite-domain solver is deliberately not complete; coverageComplete=false and coverageUnsolved expose goals that could not be solved.",
        "automaticCasesAvailable=true is returned only after every generated case passes the real ilivalidator with the expected outcome.");
  }

  private boolean isMultigeometry(Type declared) {
    Type real = Type.findReal(declared);
    return real instanceof MultiCoordType
        || real instanceof MultiPolylineType
        || real instanceof MultiSurfaceType
        || real instanceof MultiAreaType;
  }

  private record GeneratedCases(
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries) {
  }

}
