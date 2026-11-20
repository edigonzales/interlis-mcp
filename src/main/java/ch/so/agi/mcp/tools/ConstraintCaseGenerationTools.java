package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.generator.Interlis2Generator;
import ch.interlis.ili2c.metamodel.AttributeRef;
import ch.interlis.ili2c.metamodel.ExistenceConstraint;
import ch.interlis.ili2c.metamodel.MultiAreaType;
import ch.interlis.ili2c.metamodel.MultiCoordType;
import ch.interlis.ili2c.metamodel.MultiPolylineType;
import ch.interlis.ili2c.metamodel.MultiSurfaceType;
import ch.interlis.ili2c.metamodel.PathElRefAttr;
import ch.interlis.ili2c.metamodel.Type;
import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.ConstraintAnalysisService;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.constraint.ConstraintCoveragePlanner;
import ch.so.agi.mcp.constraint.ConstraintExplanation;
import ch.so.agi.mcp.constraint.ConstraintExpression;
import ch.so.agi.mcp.constraint.ConstraintExpressionEngine;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer;
import ch.so.agi.mcp.constraint.SemanticConstraint;
import ch.so.agi.mcp.model.IliAuthoringResult.ConstraintProof;
import ch.so.agi.mcp.model.IliAuthoringResult.CoverageExclusion;
import ch.so.agi.mcp.model.IliAuthoringResult.CoverageGap;
import ch.so.agi.mcp.model.IliAuthoringResult.ProofCase;
import ch.so.agi.mcp.model.IliAuthoringResult.ProofCaseVerification;
import ch.so.agi.mcp.model.IliAuthoringResult.ProofStatus;
import ch.so.agi.mcp.model.IliAuthoringResult.ProofVerification;
import ch.so.agi.mcp.model.ModelHashes;
import ch.so.agi.mcp.model.TestXtfOutput;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ConstraintCaseGenerationTools {

  private static final String EXTERNAL = "EXTERNAL_FUNCTION_SEMANTICS_REQUIRED";
  private static final String EXTERNAL_REASON =
      "Executable semantics for model-defined or validator-extension functions are unavailable.";
  private static final String EXECUTION_FAILED = "PROOF_EXECUTION_FAILED";

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
      description = "Leitet Testfälle aus einem bestehenden Constraint ab und prüft sie mit ilivalidator. Meldet Coverage-Lücken und Nachweisgrenzen.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true)
  )
  public Map<String, Object> generateIliConstraintCases(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Constraint-Name oder vollqualifizierter Constraint-Name", required = true) String constraint,
      @McpToolParam(description = TestXtfOutput.PARAMETER, required = false) @org.jspecify.annotations.Nullable Boolean includeSuccessfulTestXtf) {
    var resolution = contextService.compileAndResolve(modelText, constraint, null, "ili2c_constraint_cases_");
    var proof = resolution.available()
        ? generateCompiledConstraintCases(resolution.context())
        : unavailable(resolution.reasonCode(), resolution.reason(), null);
    var response = generationResponse(proof, resolution.context(), Boolean.TRUE.equals(includeSuccessfulTestXtf));
    response.put("compilerValid", resolution.compilation().valid());
    response.put("compilerMessages", resolution.compilation().messages());
    return ModelHashes.attach(response, ModelHashes.model(modelText));
  }

  public Map<String, Object> generateIliConstraintCases(String modelText, String constraint) {
    return generateIliConstraintCases(modelText, constraint, null);
  }

  public ConstraintProof prove(String fqn, ConstraintContextService.Resolution resolution, boolean declaredExternal) {
    ConstraintProof proof = new ConstraintProof();
    if (declaredExternal && !resolution.lookupFailed()) {
      proof.reasonCode = EXTERNAL;
      proof.reason = EXTERNAL_REASON;
    } else if (!resolution.available()) {
      proof.reasonCode = resolution.reasonCode();
      proof.reason = resolution.reason();
    } else {
      try {
        proof = generateCompiledConstraintCases(resolution.context());
      } catch (IllegalArgumentException ex) {
        proof.reasonCode = EXECUTION_FAILED;
        proof.reason = ex.getMessage();
      }
    }
    proof.constraintFqn = fqn;
    proof.status = proofStatus(proof);
    if (proof.status == ProofStatus.INCOMPLETE && proof.reasonCode == null) {
      var fixtureFailure = firstFixtureFailure(proof.verification);
      if (fixtureFailure != null) {
        proof.reasonCode = fixtureFailure.fixturePreparationReasonCode;
        proof.reason = fixtureFailure.reason;
      } else if (!proof.coverageGaps.isEmpty()) {
        var gap = proof.coverageGaps.getFirst();
        proof.reasonCode = gap.reasonCode;
        proof.reason = gap.reason;
      }
    }
    return proof;
  }

  private static ProofStatus proofStatus(ConstraintProof proof) {
    if (EXTERNAL.equals(proof.reasonCode)) return ProofStatus.EXTERNAL_FUNCTION_SEMANTICS_REQUIRED;
    if (EXECUTION_FAILED.equals(proof.reasonCode)) return ProofStatus.FAILED;
    if (proof.verification == null || proof.verification.cases.isEmpty()) return ProofStatus.INCOMPLETE;
    for (var test : proof.verification.cases) {
      if (test.validatorRan && !Boolean.TRUE.equals(test.passed)
          && (Boolean.FALSE.equals(test.fixtureValid) || Boolean.TRUE.equals(test.constraintExercised))) {
        return ProofStatus.FAILED;
      }
    }
    return proof.generationVerified() && Boolean.TRUE.equals(proof.coverageComplete) && proof.coverageGaps.isEmpty()
        ? ProofStatus.PASSED : ProofStatus.INCOMPLETE;
  }

  /** Reuses the compiled constraint and its AST for proof and explanation. */
  private ConstraintProof generateCompiledConstraintCases(CompiledConstraintContext context) {
    var review = new ConstraintAnalysisService().reviewCompiled(context.transferDescription(), context.constraintFqn());
    var explanation = (ConstraintExplanation) review.get("explanation");
    var result = explanation.requiresExternalSemantics()
        ? unavailable(EXTERNAL, EXTERNAL_REASON, context) : generateCompiledCases(context);
    result.explanation = explanation;
    return result;
  }

  private ConstraintProof generateCompiledCases(CompiledConstraintContext context) {
    try {
      var result = generateScopedConstraintCases(context);
      var verification = result.verification;
      if (verification != null && verification.viewScopeChecked) {
        result.generatedCases = Stream.concat(result.generatedCases.stream(), verification.viewScopeGoals.stream()).toList();
        result.coverageGoalCount += verification.viewScopeGoals.size() + verification.viewScopeGaps.size();
        result.coverageSolvedCount += verification.viewScopeGoals.size();
        result.coverageExcludedGoals = Stream.concat(
            result.coverageExcludedGoals.stream(), verification.viewScopeExcluded.stream()).toList();
        result.coverageExcludedCount = result.coverageExcludedGoals.size();
        if (!verification.viewScopeGaps.isEmpty()) {
          result.coverageComplete = false;
          result.reportIncomplete = true;
          result.reasonCode = "VIEW_SCOPE_UNSOLVED";
          result.reason = "View filter coverage has unresolved reached-state goals.";
          result.coverageGaps = Stream.concat(result.coverageGaps.stream(), verification.viewScopeGaps.stream()).toList();
        }
      }
      return result;
    } catch (ch.so.agi.mcp.constraint.ViewProofScope.ScopeException ex) {
      return unavailable(ex.reasonCode(), ex.getMessage(), context);
    } catch (IllegalArgumentException ex) {
      if(ex.getMessage()!=null && ex.getMessage().startsWith("OBJECT_PATH_") && ex.getMessage().contains(":"))
        return unavailable(ex.getMessage().substring(0,ex.getMessage().indexOf(':')),ex.getMessage(),context);
      if (!(context.constraint().getContainer() instanceof ch.interlis.ili2c.metamodel.View)) throw ex;
      return unavailable("VIEW_FIXTURE_MATERIALIZATION_FAILED", ex.getMessage(), context);
    }
  }

  private ConstraintProof generateScopedConstraintCases(CompiledConstraintContext context) {
    Objects.requireNonNull(context, "context");
    if (ch.so.agi.mcp.constraint.ConstraintValidatorCompatibility.hasNativeImplication(context)) {
      var response = unavailable(
          ch.so.agi.mcp.constraint.ConstraintValidatorCompatibility.NATIVE_IMPLICATION,
          "The pinned iox-ili 1.24.4 runtime does not reliably evaluate native =>. "
              + "Automatic proof is unavailable; author implications using NOT(antecedent) OR consequent.",
          context);
      response.reportIncomplete = true;
      response.coverageComplete = false;
      return response;
    }
    var scope = ch.so.agi.mcp.constraint.ViewProofScope.resolve(context.transferDescription(), context.constraint());
    if (scope != null) context = scope.planningContext(context);
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
        context);
  }

  private ConstraintProof generateMandatoryConstraintCases(
      CompiledConstraintContext context,
      SemanticConstraint.Mandatory mandatory) {
    List<Map<String,String>> routes;
    try { routes = ch.so.agi.mcp.constraint.ObjectPathRoutes.resolve(context.transferDescription(), mandatory.contextFqn(), mandatory.condition()); }
    catch (IllegalArgumentException ex) {
      String code = ex.getMessage().contains(":") ? ex.getMessage().substring(0,ex.getMessage().indexOf(':')) : "OBJECT_PATH_BINDING_UNAVAILABLE";
      return unavailable(code,ex.getMessage(),context);
    }
    var results = routes.stream().map(route -> generateMandatoryRoute(context, mandatory, route, routes)).toList();
    return ObjectPathProofResults.merge(results, routes);
  }

  private ConstraintProof generateMandatoryRoute(CompiledConstraintContext context,
      SemanticConstraint.Mandatory mandatory, Map<String, String> route, List<Map<String, String>> routes) {
    ConstraintExpression expression = mandatory.condition();
    ConstraintModelSynthesizer.ModelBinding binding;
    try {
      binding = ch.so.agi.mcp.constraint.ViewProofScope.bind(
          context, mandatory.contextFqn(), expression, route);
    } catch (IllegalArgumentException ex) {
      String code=ex.getMessage()!=null && ex.getMessage().startsWith("OBJECT_PATH_") && ex.getMessage().contains(":")
          ? ex.getMessage().substring(0,ex.getMessage().indexOf(':')) : "MODEL_BINDING_UNAVAILABLE";
      return unavailable(code, ex.getMessage(), context);
    }

    ConstraintCoveragePlanner.CoveragePlan coverage = ConstraintCoveragePlanner.solve(expression, binding);
    if (coverage.cases().isEmpty()) {
      String reasonCode = coverage.unsolved().isEmpty()
          ? "NO_COVERAGE_CASES"
          : coverage.unsolved().getFirst().reasonCode();
      String reason = coverage.unsolved().isEmpty()
          ? "No semantic coverage cases could be derived for the constraint."
          : coverage.unsolved().getFirst().reason();
      var response = unavailable(reasonCode, reason, context);
      addCoverage(response, coverage, mandatory.version());
      return response;
    }

    GeneratedCases generated;
    try {
      generated = generateCases(expression, mandatory.version(), binding, coverage);
    } catch (IllegalArgumentException ex) {
      return unavailable("OBJECT_GRAPH_SYNTHESIS_FAILED", ex.getMessage(), context);
    }

    var topology = ObjectCountTopologyPlanner.plan(context.transferDescription(), expression, binding, routes);
    var combinedCases = new ArrayList<>(generated.cases()); combinedCases.addAll(topology.cases());
    var combinedSummaries = new ArrayList<>(generated.summaries()); combinedSummaries.addAll(topology.summaries());
    generated = new GeneratedCases(combinedCases, combinedSummaries);
    VerifiedMandatory verifiedCases;
    try {
      verifiedCases = verifyMandatoryCases(context, generated);
    } catch (ConstraintFixtureException ex) {
      var response = unavailable(ex.reasonCode(), ex.getMessage(), context);
      addCoverage(response, coverage, mandatory.version());
      return response;
    }
    generated = verifiedCases.generated();
    var verification = verifiedCases.verification();
    var response = verifiedResponse(context, "SEMANTIC_IR_COVERAGE", generated.summaries(), verification);
    addCoverage(response, coverage, mandatory.version());
    response.objectPathGoals = topology.summaries();
    response.objectPathGaps = topology.gaps();
    response.objectPathExcluded = topology.excluded();
    response.coverageGoalCount += topology.cases().size() + topology.gaps().size();
    response.coverageSolvedCount += topology.cases().size();
    response.coverageExcludedGoals = Stream.concat(response.coverageExcludedGoals.stream(), topology.excluded().stream()).toList();
    response.coverageExcludedCount = response.coverageExcludedGoals.size();
    if (!topology.gaps().isEmpty()) {
      response.coverageGaps = Stream.concat(response.coverageGaps.stream(), topology.gaps().stream()).toList();
      response.coverageComplete = false;
      response.reasonCode = "OBJECT_PATH_TOPOLOGY_INCOMPLETE";
      response.reason = "Object-path topology obligations remain unresolved.";
    }
    if (!verification.allPassed()) {
      addVerificationFailure(response,
          verifiedCases.noViableRoute() ? "STRUCTURE_MATERIALIZATION_FAILED" : "GENERATED_CASES_NOT_VERIFIED",
          "Semantic cases were generated, but the real validator did not confirm all expected outcomes.");
    }
    return response;
  }

  private ConstraintProof generateUniqueConstraintCases(
      CompiledConstraintContext context,
      SemanticConstraint.Unique unique) {
    UniqueConstraintCasePlanner.Plan plan;
    try {
      plan = UniqueConstraintCasePlanner.plan(context, unique);
    } catch (IllegalArgumentException ex) {
      return unavailable(
          "UNIQUE_PROOF_PLANNING_FAILED",
          ex.getMessage(),
          context);
    }

    return verifyPlan(context, "UNIQUE_SEMANTIC_PROOF", "UNIQUE proof",
        plan.cases(), plan.summaries(), plan.cases().size(), plan.unsolved());
  }

  private ConstraintProof generateExistenceConstraintCases(
      CompiledConstraintContext context,
      SemanticConstraint.Existence existence) {
    ExistenceConstraintCasePlanner.Plan plan;
    try {
      plan = ExistenceConstraintCasePlanner.plan(context, existence);
    } catch (IllegalArgumentException ex) {
      return unavailable(
          "EXISTENCE_PROOF_PLANNING_FAILED",
          ex.getMessage(),
          context);
    }

    return verifyPlan(context, "EXISTENCE_SEMANTIC_PROOF", "EXISTENCE proof",
        plan.cases(), plan.summaries(), plan.cases().size(), plan.unsolved());
  }

  private ConstraintProof generatePlausibilityConstraintCases(
      CompiledConstraintContext context,
      SemanticConstraint.Plausibility plausibility) {
    PlausibilityConstraintCasePlanner.Plan plan;
    try {
      plan = PlausibilityConstraintCasePlanner.plan(context, plausibility);
    } catch (IllegalArgumentException ex) {
      return unavailable(
          "PLAUSIBILITY_PROOF_PLANNING_FAILED",
          ex.getMessage(),
          context);
    }

    var response = verifyPlan(context, "PLAUSIBILITY_POPULATION_PROOF", "PLAUSIBILITY population",
        plan.cases(), plan.summaries(), plan.cases().size(), plan.unsolved());
    if (!plan.cases().isEmpty()) {
      response.generationDetails.put("plausibility", Map.of(
          "direction", plausibility.direction().name(),
          "percentage", plausibility.percentage().stripTrailingZeros().toPlainString(),
          "condition", plausibility.condition().toInterlis(plausibility.version())));
    }
    return response;
  }

  private ConstraintProof generateSetConstraintCases(
      CompiledConstraintContext context,
      SemanticConstraint.Set set) {
    SetConstraintCasePlanner.Plan plan;
    try {
      plan = SetConstraintCasePlanner.plan(context, set);
    } catch (IllegalArgumentException ex) {
      return unavailable(
          "SET_PROOF_PLANNING_FAILED",
          ex.getMessage(),
          context);
    }

    var response = verifyPlan(context, "SET_OBJECT_COUNT_PROOF", "SET objectCount",
        plan.cases(), plan.summaries(), plan.solvedGoalCount(), plan.unsolved());
    if (!plan.cases().isEmpty()) {
      response.generationDetails.put("set", setSummary(set));
      response.coverageExcludedGoals = plan.excluded();
      response.coverageExcludedCount = plan.excluded().size();
    }
    return response;
  }

  private ConstraintProof verifyPlan(
      CompiledConstraintContext context,
      String pattern,
      String description,
      List<ConstraintTestTools.TestCase> cases,
      List<ProofCase> summaries,
      int solvedGoalCount,
      List<CoverageGap> unsolved) {
    if (cases.isEmpty()) {
      if (!unsolved.isEmpty()) {
        var gap = unsolved.getFirst();
        return unavailable(gap.reasonCode, gap.reason, context);
      }
      return unavailable("NO_" + context.semantics().kind().name() + "_PROOF_CASES",
          "No validator-backed " + description + " case could be synthesized for this model shape.", context);
    }

    var verification = ViewProofCoverage.verify(context, cases, testTools);
    var response = verifiedResponse(context, pattern, summaries, verification);
    response.coverageGoalCount = solvedGoalCount + unsolved.size();
    response.coverageSolvedCount = solvedGoalCount;
    response.coverageComplete = unsolved.isEmpty();
    response.coverageGaps = unsolved;
    if (!verification.allPassed()) {
      boolean fixtureFailure = addVerificationFailure(response, "GENERATED_CASES_NOT_VERIFIED",
          description + " cases were generated, but the real validator did not confirm all expected outcomes.");
      if (!fixtureFailure) addExistenceValidatorFailure(context, response);
    }
    return response;
  }

  private void addExistenceValidatorFailure(CompiledConstraintContext context, ConstraintProof response) {
    if (!(context.constraint() instanceof ExistenceConstraint raw) || raw.getRestrictedAttribute() == null) {
      return;
    }
    var lastPathElement = raw.getRestrictedAttribute().getLastPathEl();
    if (lastPathElement instanceof PathElRefAttr) {
      response.reasonCode = "REFERENCE_EQUALITY_VALIDATOR_FAILURE";
      response.reason = "The installed ilivalidator cannot execute REFERENCE-valued EXISTENCE equality without an internal error; the OID fixtures remain unproved.";
    } else if (lastPathElement instanceof AttributeRef ref && isMultigeometry(ref.getAttr().getDomainOrDerivedDomain())) {
      response.reasonCode = "GEOMETRY_EQUALITY_VALIDATOR_FAILURE";
      response.reason = "The installed ilivalidator cannot execute multigeometry-valued EXISTENCE equality without an internal error; the valid fixtures remain unproved.";
    }
  }

  private ConstraintProof verifiedResponse(CompiledConstraintContext context, String pattern,
      List<ProofCase> summaries, ProofVerification verification) {
    var result = unavailable(null, null, context);
    result.pattern = pattern;
    result.casesGenerated = true;
    result.generatedCases = summaries;
    result.verification = verification;
    return result;
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
      result.put("operator", objectCount.operator().interlis());
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

  private record VerifiedMandatory(
      GeneratedCases generated, ProofVerification verification, boolean noViableRoute) {}

  private VerifiedMandatory verifyMandatoryCases(CompiledConstraintContext context, GeneratedCases generated) {
    if (!(context.constraint().getContainer() instanceof ch.interlis.ili2c.metamodel.Table table)
        || table.isIdentifiable()) {
      return new VerifiedMandatory(generated, ViewProofCoverage.verify(context, generated.cases(), testTools), false);
    }
    var routes = ConstraintFixtureContextResolver.resolve(context.transferDescription(), table);
    VerifiedMandatory last = null;
    ConstraintFixtureException failure = null;
    for (var route : routes) {
      GeneratedCases embedded;
      try {
        var cases = generated.cases().stream().map(route::embed).toList();
        embedded = new GeneratedCases(cases, generated.summaries());
      } catch (ConstraintFixtureException ex) {
        failure = ex;
        continue;
      }
      var verification = ViewProofCoverage.verify(context, embedded.cases(), testTools);
      var results = verification.cases;
      boolean fixtureValid = results.stream().allMatch(test -> Boolean.TRUE.equals(test.fixtureValid));
      boolean outcomeMismatch = results.stream().anyMatch(test -> Boolean.TRUE.equals(test.fixtureValid)
          && !Objects.equals(test.expectedConstraintValid, test.actualConstraintValid));
      for (var summary : embedded.summaries()) {
        summary.ownerClassFqn = route.ownerFqn();
        summary.structurePath = route.path();
      }
      last = new VerifiedMandatory(embedded, verification, !fixtureValid);
      // A different owner must never be used to hide an observed semantic mismatch.
      if (fixtureValid || outcomeMismatch) return last;
    }
    if (last != null) return last;
    if (failure != null) throw failure;
    throw new ConstraintFixtureException("STRUCTURE_MATERIALIZATION_FAILED", "No structure fixture route could be materialized.");
  }

  private GeneratedCases generateCases(
      ConstraintExpression expression,
      ConstraintExpression.IliVersion version,
      ConstraintModelSynthesizer.ModelBinding binding,
      ConstraintCoveragePlanner.CoveragePlan coverage) {
    List<ConstraintTestTools.TestCase> cases = new ArrayList<>();
    List<ProofCase> summaries = new ArrayList<>();
    int index = 1;
    for (ConstraintCoveragePlanner.CoverageCase coverageCase : coverage.cases()) {
      Map<String, Object> assignment = coverageCase.solution().assignment();
      boolean expectedValid = ConstraintExpressionEngine.evaluateConstraint(
          expression, ConstraintExpressionEngine.EvaluationContext.of(assignment));
      ConstraintModelSynthesizer.ObjectGraph graph = ConstraintModelSynthesizer.synthesize(
          binding, assignment, "auto_case_" + index);

      ConstraintTestTools.TestCase testCase = ObjectCountTopologyPlanner.toCountedCase(
          "automatic case " + index + " - " + coverageCase.goal().reason(),
          expectedValid, graph, binding, assignment);
      cases.add(testCase);

      var summary = new ProofCase(testCase.name, expectedValid ? "WITNESS" : "COUNTEREXAMPLE", expectedValid);
      summary.reason = coverageCase.goal().reason();
      summary.details.put("source", coverageCase.goal().expression().toInterlis(version));
      summary.coveredGoals = coverageCase.coveredGoals().stream()
          .map(goal -> ConstraintCoveragePlanner.describeGoal(goal, version)).toList();
      summary.setSummaryValues(summaryAssignment(assignment));
      summary.objectCount = graph.objects().size();
      summary.associationLinkCount = graph.links().size();
      addSingleReferenceCompatibility(summary.details, assignment);
      summaries.add(summary);
      index++;
    }
    return new GeneratedCases(cases, summaries);
  }

  static Map<String, Object> summaryAssignment(Map<String, Object> assignment) {
    Map<String, Object> result = new LinkedHashMap<>();
    assignment.forEach((name, value) -> result.put(name, summaryValue(value)));
    return result;
  }

  static Object summaryValue(@Nullable Object value) {
    if (value == null || value == ConstraintExpressionEngine.Undefined.INSTANCE) {
      return "UNDEFINED";
    }
    if (value instanceof BigDecimal number) {
      return number.stripTrailingZeros().toPlainString();
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(ConstraintCaseGenerationTools::summaryValue).toList();
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

  private void addCoverage(ConstraintProof response, ConstraintCoveragePlanner.CoveragePlan coverage,
      ConstraintExpression.IliVersion version) {
    response.coverageSolvedCount = ConstraintCoveragePlanner.solvedGoalCount(coverage);
    response.coverageGoalCount = response.coverageSolvedCount + coverage.unsolved().size();
    response.coverageExcludedGoals = ConstraintCoveragePlanner.excludedGoals(coverage, version);
    response.coverageExcludedCount = response.coverageExcludedGoals.size();
    response.coverageComplete = coverage.unsolved().isEmpty();
    response.coverageGaps = coverage.unsolved().stream().map(solution -> {
      var goal = ConstraintCoveragePlanner.describeGoal(solution.goal(), version);
      var gap = new CoverageGap(solution.reasonCode(), goal.reason, goal.goal);
      gap.expression = goal.expression;
      gap.solverReason = solution.reason();
      return gap;
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

  private Map<String, Object> contextSummary(CompiledConstraintContext context, String planningContextFqn) {
    Object container = context.constraint().getContainer();
    return Map.of(
        "scopedName", planningContextFqn,
        "kind", container != null ? container.getClass().getSimpleName() : "UNKNOWN",
        "pathContextAvailable", container instanceof ch.interlis.ili2c.metamodel.Viewable<?>);
  }

  private ConstraintProof unavailable(@Nullable String reasonCode, @Nullable String reason,
      @Nullable CompiledConstraintContext context) {
    var result = new ConstraintProof();
    result.reasonCode = reasonCode;
    result.reason = reason;
    if (context != null) result.planningContextFqn = context.contextFqn();
    result.limitations = limitations();
    return result;
  }

  private @Nullable ProofCaseVerification firstFixtureFailure(@Nullable ProofVerification verification) {
    return verification == null ? null : verification.cases.stream()
        .filter(test -> test.fixturePreparationReasonCode != null).findFirst().orElse(null);
  }

  private boolean addVerificationFailure(ConstraintProof result, String defaultReasonCode, String defaultReason) {
    var failure = firstFixtureFailure(result.verification);
    result.reasonCode = failure == null ? defaultReasonCode : failure.fixturePreparationReasonCode;
    result.reason = failure == null ? defaultReason : failure.reason;
    result.reportIncomplete = failure != null;
    return failure != null;
  }

  private Map<String, Object> generationResponse(ConstraintProof proof, @Nullable CompiledConstraintContext context,
      boolean includeSuccessfulTestXtf) {
    var response = new LinkedHashMap<>(proof.generationDetails);
    if (context != null && proof.planningContextFqn != null) {
      response.put("constraint", constraintSummary(context));
      response.put("context", contextSummary(context, proof.planningContextFqn));
    }
    boolean verified = proof.generationVerified();
    response.put("automaticCasesAvailable", verified);
    response.put("automaticCasesGenerated", proof.casesGenerated);
    response.put("generationVerified", verified);
    response.put("limitations", proof.limitations);
    if (proof.explanation != null) response.put("explanation", proof.explanation);
    if (proof.pattern != null) response.put("pattern", proof.pattern);
    if (proof.reasonCode != null) {
      response.put("reasonCode", proof.reasonCode);
      response.put("reason", proof.reason);
    }
    if (proof.reportIncomplete) response.put("proofIncomplete", true);
    if (proof.coverageComplete != null) response.put("coverageComplete", proof.coverageComplete);
    if (proof.coverageGoalCount != null) response.put("coverageGoalCount", proof.coverageGoalCount);
    if (proof.coverageSolvedCount != null) response.put("coverageSolvedCount", proof.coverageSolvedCount);
    if (proof.coverageExcludedCount != null) {
      response.put("coverageExcludedCount", proof.coverageExcludedCount);
      response.put("coverageExcludedGoals", proof.coverageExcludedGoals.stream().map(CoverageExclusion::toMap).toList());
    }
    if (proof.multipleRoutes || !proof.coverageGaps.isEmpty()) {
      response.put("coverageUnsolved", proof.coverageGaps.stream().map(CoverageGap::toMap).toList());
    }
    if (proof.multipleRoutes || !proof.objectPathGoals.isEmpty() || !proof.objectPathGaps.isEmpty()
        || !proof.objectPathExcluded.isEmpty()) {
      response.put("objectPathGoals", proof.objectPathGoals.stream().map(goal -> goal.toMap(false)).toList());
      response.put("objectPathGaps", proof.objectPathGaps.stream().map(CoverageGap::toMap).toList());
      response.put("objectPathExcluded", proof.objectPathExcluded.stream().map(CoverageExclusion::toMap).toList());
    }
    if (proof.verification != null) {
      var verification = proof.verification;
      response.put("generatedCases", proof.generatedCases.stream().map(ProofCase::toMap).toList());
      var check = proof.multipleRoutes ? TestXtfOutput.verificationResponse(verification, includeSuccessfulTestXtf)
          : ConstraintTestTools.testResponse(context.constraint(), verification, includeSuccessfulTestXtf);
      if (proof.multipleRoutes) check.put("allPassed", verified);
      if (verification.viewScopeChecked) {
        check.put("viewScopeGoals", verification.viewScopeGoals.stream().map(ProofCase::toMap).toList());
        check.put("viewScopeGaps", verification.viewScopeGaps.stream().map(CoverageGap::toMap).toList());
        check.put("viewScopeExcluded", verification.viewScopeExcluded.stream().map(CoverageExclusion::toMap).toList());
      }
      response.put("verification", check);
      TestXtfOutput.addOmittedCount(response, verification, includeSuccessfulTestXtf);
    }
    return response;
  }

  private List<String> limitations() {
    return List.of(
        "Mandatory structure proofs embed the context in an existing concrete owner class; ownerClassFqn and structurePath describe the fixture route. Missing, abstract or excessive routes remain explicit proof boundaries.",
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
        "Scalar/boolean coverage counts goals independently of deduplicated fixtures; coveredGoals retains their mapping. coverageExcludedGoals contains only independently proven unreachable structural goals; search limits and required witness/counterexample goals are never excused.",
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
      List<ProofCase> summaries) {
  }

}
